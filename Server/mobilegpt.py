import json
import os
from enum import Enum
import time

import pandas as pd

from agents.derive_agent import DeriveAgent
from agents.explore_agent import ExploreAgent
from agents.select_agent import SelectAgent
from memory.memory_manager import Memory
from log_config import log
from utils.utils import parse_completion_rate
from utils.mongo_utils import load_dataframe, save_dataframe
from utils.local_store import get_screen_bundle_dir


class Status(Enum):
    LEARN = 0 # 学习模式（新任务，需记录流程）
    RECALL = 1 # 回忆模式（复用历史任务流程）
    WAIT = 2  # 等待状态（子任务执行中或未开始）


class MobileGPT:
    def __init__(self, socket):
        self.socket = socket

        self.encoded_xml = ""
        self.hierarchy_xml = ""
        self.parsed_xml = ""

        self.instruction = ""
        self.task = None
        self.memory = None

        self.current_subtask = None
        self.current_screen_xml = ""
        self.current_page_index = -1
        self.current_subtask_data = {}

        self.subtask_history = []
        self.task_path = []
        self.qa_history = []

        self.explore_agent = None
        self.select_agent = None
        self.derive_agent = None

        # 0 = Learning, 1 = Recalling
        self.task_status = Status.RECALL
        self.subtask_status = Status.WAIT

    def init(self, instruction: str, task: dict, is_new_task: bool):
        self.instruction = instruction
        self.task = task
        self.memory = Memory(instruction, task['name'])
        self.explore_agent = ExploreAgent(self.memory)
        self.select_agent = SelectAgent(self.memory, self.instruction)
        self.derive_agent = DeriveAgent(self.memory, self.instruction)
        
        self.start_time = time.time()
        self.end_time = 0
        # 若为新任务，设为学习模式（需记录流程到内存）
        if is_new_task:
            self.task_status = Status.LEARN
            log(f"❄️ 冷启动: 任务 '{task['name']}' 初始化为学习模式", "yellow")
        else:
            log(f"🔥 热启动: 任务 '{task['name']}' 初始化为回忆模式", "green")

        log('Mobile Agent Initialized for Task: ' + task['name'])

    def get_next_action(self, parsed_xml=None, hierarchy_xml=None, encoded_xml=None, subtask_failed=False, action_failed=False, suggestions=None):
        log(":::::::::MobileGPT received new screen:::::::::", 'blue')
        
        try:
            parsed_xml = parsed_xml or self.parsed_xml
            hierarchy_xml = hierarchy_xml or self.hierarchy_xml
            encoded_xml = encoded_xml or self.encoded_xml

            # 参数验证
            if not parsed_xml or not hierarchy_xml or not encoded_xml:
                log(f"get_next_action参数无效: parsed_xml={bool(parsed_xml)}, hierarchy_xml={bool(hierarchy_xml)}, encoded_xml={bool(encoded_xml)}", "red")
                return None

            self.parsed_xml = parsed_xml
            self.hierarchy_xml = hierarchy_xml
            self.encoded_xml = encoded_xml

            self.current_screen_xml = encoded_xml
            
            # 检查内存管理器状态
            if not hasattr(self, 'memory') or self.memory is None:
                log("内存管理器未初始化", "red")
                return None
            
            log(f"开始搜索节点，参数长度: parsed={len(parsed_xml)}, hierarchy={len(hierarchy_xml)}, encoded={len(encoded_xml)}", "blue")
            # 检查当前界面是否匹配历史页面（调用内存的search_node方法）
            page_index, new_subtasks = self.memory.search_node(parsed_xml, hierarchy_xml, encoded_xml)
            log(f"搜索节点完成: page_index={page_index}, new_subtasks={len(new_subtasks) if new_subtasks else 0}", "blue")
            
        except Exception as e:
            import traceback
            log(f"get_next_action初始化阶段异常: {e}", "red")
            log(f"异常类型: {type(e).__name__}", "red")
            log(f"异常堆栈: {traceback.format_exc()}", "red")
            return None

        # 若未匹配到历史页面（page_index == -1），调用ExploreAgent探索新界面
        if page_index == -1:
            try:
                log("开始探索新界面", "blue")
                page_index = self.explore_agent.explore(parsed_xml, hierarchy_xml, encoded_xml)
                log(f"探索完成: page_index={page_index}", "blue")
            except Exception as e:
                import traceback
                log(f"探索新界面异常: {e}", "red")
                log(f"异常类型: {type(e).__name__}", "red")
                log(f"异常堆栈: {traceback.format_exc()}", "red")
                return None

        # 若页面索引变化（进入新页面），初始化页面管理器并结束当前子任务
        if page_index != self.current_page_index:
            log(f"页面切换: {self.current_page_index} -> {page_index}", "blue")
            # 页面切换前先尝试将上一页的数据写入上一页目录，避免错位
            try:
                if self.current_page_index is not None and self.current_page_index >= 0:
                    buf = getattr(self, '_local_buffer', None)
                    if buf:
                        xml_idx_prev = sorted({it.get('index') for it in buf.get('xmls', []) if 'index' in it})
                        shot_idx_prev = sorted({it.get('index') for it in buf.get('shots', []) if 'index' in it})
                        common_prev = sorted(list(set(xml_idx_prev).intersection(shot_idx_prev)))
                        task_name = getattr(getattr(self, 'memory', None), 'task_name', 'task') or 'task'
                        log(f"[page] change prev={self.current_page_index} -> curr={page_index}, task={task_name}, prev_xml_idx={xml_idx_prev}, prev_shot_idx={shot_idx_prev}, prev_common={common_prev}, dest_prev=memory/log/{task_name}/pages/{self.current_page_index}/screen", "blue")
                        if common_prev:
                            # 落到上一页
                            self.__flush_buffer_to_page(self.current_page_index)
            except Exception:
                pass
            self.memory.init_page_manager(page_index)
            self.current_page_index = page_index
            try:
                # 确保每个新页面的层级信息都被持久化（存在则更新，不存在则追加）
                # 这样不依赖于是否走到了 Explore 分支
                self.memory.add_hierarchy_xml(hierarchy_xml, page_index)
            except Exception:
                pass
            # 切到新页后再记录当前页缓冲概况
            try:
                task_name = getattr(getattr(self, 'memory', None), 'task_name', 'task') or 'task'
                buf = getattr(self, '_local_buffer', None)
                xml_idx = sorted({it.get('index') for it in (buf.get('xmls') if buf else []) if 'index' in it})
                shot_idx = sorted({it.get('index') for it in (buf.get('shots') if buf else []) if 'index' in it})
                common = sorted(list(set(xml_idx).intersection(shot_idx)))
                log(f"[page] now at curr={page_index}, task={task_name}, xml_idx={xml_idx}, shot_idx={shot_idx}, common={common}, dest_curr=memory/log/{task_name}/pages/{page_index}/screen", "blue")
            except Exception:
                pass

            if self.subtask_status == Status.LEARN:
                self.__finish_subtask()

        # 获取当前页面的可用子任务（含新生成的子任务）
        available_subtasks = self.memory.get_available_subtasks(page_index)
        if len(new_subtasks) > 0:
            available_subtasks += new_subtasks
        # 若子任务选择出错，清楚当前子任务状态
        if subtask_failed:
            # self.memory.delete_subtask(self.current_subtask['name'])
            self.current_subtask = None

        # 若当前无子任务，选择下一个子任务
        if self.current_subtask is None:
            # 从内存中获取下一步子任务（优先复用历史）
            next_subtask = self.memory.get_next_subtask(page_index, self.qa_history, self.current_screen_xml)

            # 若内存中无可用子任务，调用SelectAgent从可用子任务中选择
            if not next_subtask:
                # 调用SelectAgent.select：结合历史和当前界面选择子任务
                response, new_action = self.select_agent.select(available_subtasks, self.subtask_history,
                                                                self.qa_history,
                                                                encoded_xml, subtask_failed, suggestions)
                # 若生成了新动作，添加到内存（供后续复用）
                if new_action:
                    self.memory.add_new_action(new_action, page_index)
                    available_subtasks = self.memory.get_available_subtasks(page_index)

                next_subtask = response['action']# 提取选择的子任务
                if next_subtask['name'] != 'read_screen':
                    msg = response['speak']
                    if not self.__send_speak_action(msg):
                        # Socket closed by client; stop processing this loop
                        return None
            # 记录当前子任务数据（页面索引、名称、动作等）
            if self.current_subtask_data:# 若存在上一个子任务数据，添加到任务路径
                self.task_path.append(self.current_subtask_data)

            self.current_subtask_data = {"page_index": self.current_page_index,
                                         "subtask_name": next_subtask['name'], "subtask": next_subtask, "actions": []}

            # 初始化推导智能体（传入当前子任务和历史，用于生成动作）
            self.derive_agent.init_subtask(next_subtask, self.subtask_history)
            self.current_subtask = next_subtask  # 更新当前子任务

            if next_subtask['name'] in ['finish', 'speak']:  # 移除 'scroll_screen'
                return self.__handle_primitive_subtask(next_subtask)

        subtask_parameters = self.current_subtask['parameters']
        # for key, value in subtask_parameters.items():
        #     if value == "unknown":
        #         raw_subtask = next(
        #             (subtask for subtask in available_subtasks if subtask['name'] == self.current_subtask['name']),
        #             None)
        #         print(raw_subtask)
        #         if raw_subtask:
        #             if isinstance(raw_subtask['parameters'], str):
        #                 raw_subtask['parameters'] = json.loads(raw_subtask['parameters'])
        #             question = raw_subtask['parameters'][key]
        #             ask_action = {"name": "ask", "parameters": {"info_name": key, "question": question}}
        #             return ask_action
        if action_failed:
            self.current_subtask_data['actions'].pop()
            log(f"删除上一个出错动作")
        # 从内存中获取历史动作（回忆模式）
        try:
            log(f"开始获取历史动作，子任务: {self.current_subtask['name'] if self.current_subtask else 'None'}", "blue")
            next_action = self.memory.get_next_action(self.current_subtask, self.encoded_xml)
            log(f"历史动作获取完成: {bool(next_action)}", "blue")
        except Exception as e:
            import traceback
            log(f"获取历史动作异常: {e}", "red")
            log(f"异常类型: {type(e).__name__}", "red")
            log(f"异常堆栈: {traceback.format_exc()}", "red")
            return None
            
        current_action_data = {"page_index": self.current_page_index, "action": next_action, "screen": self.encoded_xml,
                               "example": {}}

        log(f"📊 动作获取结果: 子任务='{self.current_subtask['name'] if self.current_subtask else 'None'}'", "blue")

        if next_action:
            self.subtask_status = Status.RECALL
            log(f"🔥 热启动: 子任务状态切换到回忆模式", "green")
            # 若内存中有动作示例，调用推导智能体泛化动作（适配当前界面）
            if "examples" in next_action:
                log(f"🔥 热启动: 使用历史示例进行动作泛化，示例数量={len(next_action['examples'])}", "green")
                try:
                    next_action, example = self.derive_agent.derive(self.encoded_xml, action_failed, suggestions, examples=next_action['examples'])
                    current_action_data['action'] = next_action
                    current_action_data['example'] = example
                except Exception as e:
                    import traceback
                    log(f"推导智能体泛化动作异常: {e}", "red")
                    log(f"异常类型: {type(e).__name__}", "red")
                    log(f"异常堆栈: {traceback.format_exc()}", "red")
                    return None
            else:
                log(f"🔥 热启动: 直接使用历史动作", "green")

        # 若内存中无动作，调用推导智能体新生成动作（学习模式）
        else:
            # 若子任务处于等待或学习状态，切换到学习模式生成新动作
            if self.subtask_status == Status.WAIT or self.subtask_status == Status.LEARN:
                self.subtask_status = Status.LEARN
                log(f"❄️ 冷启动: 子任务状态切换到学习模式，将生成新动作", "yellow")
                # Here
                try:
                    next_action, example = self.derive_agent.derive(self.encoded_xml, action_failed, suggestions)
                    current_action_data['action'] = next_action
                    current_action_data['example'] = example
                except Exception as e:
                    import traceback
                    log(f"推导智能体生成新动作异常: {e}", "red")
                    log(f"异常类型: {type(e).__name__}", "red")
                    log(f"异常堆栈: {traceback.format_exc()}", "red")
                    return None

            # 若处于回忆模式但无动作，处理任务分歧（重新选择子任务）
            elif self.subtask_status == Status.RECALL:
                log(f"⚠️ 任务分歧: 回忆模式但无历史动作，重新选择子任务", "yellow")
                self.__prepare_diverge_subtask()
                return self.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
        # 记录当前动作到子任务数据
        self.current_subtask_data['actions'].append(current_action_data)

        # 若动作是“finish”，结束当前子任务并继续获取下一步
        if next_action['name'] == 'finish':
            self.__finish_subtask(mark_finish=False, explicit_finish=True)
            next_action = self.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
        # 返回生成的下一步动作
        return next_action

    def __flush_buffer_to_page(self, page_index: int) -> None:
        """将缓冲中所有属于指定页面的(shot+xml)对写入对应页面的 screen 目录。"""
        buf = getattr(self, '_local_buffer', None)
        if not buf or (not buf.get('xmls') or not buf.get('shots')):
            return

        # 寻找所有共同 index，且若带有 page_index 标签则仅落当前页的对儿
        xml_indices = {item.get('index') for item in buf['xmls'] if 'index' in item}
        shot_indices = {item.get('index') for item in buf['shots'] if 'index' in item}
        common_all = sorted(list(xml_indices.intersection(shot_indices)))
        
        # 不再按页面过滤，所有匹配的索引都各自写入以索引为页面目录的路径
        common = common_all
        
        if not common:
            return

        # 处理所有匹配的索引对
        flushed_count = 0
        for index in common:
            # 取出对应项（选择最早加入缓冲的匹配项，避免后到的同 index XML 覆盖先到的）
            xml_item = None
            for i in range(len(buf['xmls'])):
                if buf['xmls'][i].get('index') == index:
                    xml_item = buf['xmls'].pop(i)
                    break
            shot_item = None
            for i in range(len(buf['shots'])):
                if buf['shots'][i].get('index') == index:
                    shot_item = buf['shots'].pop(i)
                    break
            if not xml_item or not shot_item:
                continue

            # 目标页面目录：直接使用 index 作为页面编号（pages/{index}/screen）
            task_name = getattr(getattr(self, 'memory', None), 'task_name', 'task') or 'task'
            dest_dir = get_screen_bundle_dir(task_name, index)

            # 写 screenshot
            try:
                import os
                os.makedirs(dest_dir, exist_ok=True)
                shot_bytes = shot_item.get('bytes', b'')
                screenshot_path = os.path.join(dest_dir, 'screenshot.jpg')
                with open(screenshot_path, 'wb') as f:
                    f.write(shot_bytes)
                log(f"[flush] wrote screenshot -> {screenshot_path} ({len(shot_bytes)} bytes)", "blue")
            except Exception as e:
                log(f"[flush] write screenshot failed for index {index}: {e}", "red")
                continue

            # 生成与写入 XML 变体
            raw_xml = xml_item.get('xml', '')
            try:
                from screenParser import parseXML
                import xml.etree.ElementTree as ET
                import xml.dom.minidom as minidom
                parsed = parseXML.parse(raw_xml)
                hierarchy = parseXML.hierarchy_parse(parsed)
                tree = ET.fromstring(parsed)
                for element in tree.iter():
                    for k in ("bounds", "important", "class"):
                        if k in element.attrib:
                            del element.attrib[k]
                encoded = ET.tostring(tree, encoding='unicode')
                pretty = minidom.parseString(encoded).toprettyxml()

                with open(os.path.join(dest_dir, 'raw.xml'), 'w', encoding='utf-8') as f:
                    f.write(raw_xml)
                with open(os.path.join(dest_dir, 'parsed.xml'), 'w', encoding='utf-8') as f:
                    f.write(parsed)
                with open(os.path.join(dest_dir, 'hierarchy.xml'), 'w', encoding='utf-8') as f:
                    f.write(hierarchy)
                with open(os.path.join(dest_dir, 'html.xml'), 'w', encoding='utf-8') as f:
                    f.write(encoded)
                with open(os.path.join(dest_dir, 'pretty.xml'), 'w', encoding='utf-8') as f:
                    f.write(pretty)
                log(f"[flush] wrote xmls -> {dest_dir}/(raw|parsed|hierarchy|html|pretty).xml", "blue")
                flushed_count += 1
            except Exception as e:
                try:
                    with open(os.path.join(dest_dir, 'raw.xml'), 'w', encoding='utf-8') as f:
                        f.write(raw_xml)
                except Exception:
                    pass
                log(f"[flush] write xml failed for index {index}: {e}", "red")
        
        if flushed_count > 0:
            log(f"[flush] flushed {flushed_count} pairs to page {page_index}", "green")

    def __flush_all_buffers(self) -> None:
        """在任务结束时flush所有缓冲中的数据到对应的页面目录。"""
        buf = getattr(self, '_local_buffer', None)
        if not buf or (not buf.get('xmls') or not buf.get('shots')):
            log("[flush_all] No data in buffer to flush", "blue")
            return

        # 获取所有共同索引
        xml_indices = {item.get('index') for item in buf['xmls'] if 'index' in item}
        shot_indices = {item.get('index') for item in buf['shots'] if 'index' in item}
        common_all = sorted(list(xml_indices.intersection(shot_indices)))
        
        if not common_all:
            log("[flush_all] No common indices found", "blue")
            return

        log(f"[flush_all] Found {len(common_all)} indices to flush: {common_all}", "blue")
        
        # 按页面分组处理
        page_groups = {}
        for idx in common_all:
            xml_item = next((it for it in buf['xmls'] if it.get('index') == idx), None)
            shot_item = next((it for it in buf['shots'] if it.get('index') == idx), None)
            if xml_item is None or shot_item is None:
                continue
                
            # 确定目标页面
            xml_page_tag = xml_item.get('page_index', None)
            shot_page_tag = shot_item.get('page_index', None)
            target_page = xml_page_tag if xml_page_tag is not None else (shot_page_tag if shot_page_tag is not None else self.current_page_index)
            
            if target_page not in page_groups:
                page_groups[target_page] = []
            page_groups[target_page].append(idx)

        # 为每个页面flush数据
        total_flushed = 0
        for page_index, indices in page_groups.items():
            log(f"[flush_all] Flushing {len(indices)} indices to page {page_index}: {indices}", "blue")
            self.__flush_buffer_to_page(page_index)
            total_flushed += len(indices)

        log(f"[flush_all] Total flushed {total_flushed} pairs across {len(page_groups)} pages", "green")

    def set_qa_answer(self, info_name: str, question: str, answer: str):
        qa = {"info": info_name, "question": question, "answer": answer}
        self.qa_history.append(qa)

        subtask_parameters = self.current_subtask['parameters']
        if info_name in subtask_parameters:
            subtask_parameters[info_name] = answer
            return self.get_next_action()
        else:
            log(f"Something wrong. Cannot find {info_name} inside subtask: {self.current_subtask}", "red")

    def __finish_subtask(self, mark_finish=True, explicit_finish=False):
        log("finish subtask!!", "green")
        log(f"subtask: {self.subtask_status}, task: {self.task_status}", "green")
        if self.subtask_status == Status.LEARN and self.task_status == Status.LEARN:
            log(f"❄️ 冷启动: 学习模式完成子任务，将保存到历史经验", "yellow")
            if mark_finish:
                finish_action = {"name": "finish", "parameters": {}}
                self.current_subtask_data['actions'].append(
                    {
                        "page_index": self.current_page_index,
                        "action": finish_action,
                        "screen": self.encoded_xml,
                        "example": {}
                    }
                )

            action_summary = self.derive_agent.summarize_actions()
            if action_summary:
                self.subtask_history.append(action_summary)
                log(f"💾 保存子任务历史: {action_summary}", "cyan")
        else:
            log(f"🔥 热启动: 回忆模式完成子任务，无需保存", "green")

        if self.subtask_status == Status.RECALL:
            if explicit_finish:
                history = f"Performed an action: {self.current_subtask}"
                self.subtask_history.append(history)

        self.current_subtask = None
        self.subtask_status = Status.WAIT

    def __prepare_diverge_subtask(self) -> None:
        """
        Prepare for diverging to a new subtask.
        Returns:
        """
        history = f"I have performed an action: {self.current_subtask}. But I am not sure if it was successful."
        self.subtask_history.append(history)

        self.current_subtask = None
        self.subtask_status = Status.WAIT

    def __send_speak_action(self, msg) -> bool:
        """
        Send a speak action to the device.
        Args:
            msg: message to be spoken by the device.
        """
        speak_action = {"name": "speak", "parameters": {"message": msg}}  # speak action
        try:
            # Send as a single frame to reduce chances of client-side half reads
            payload = json.dumps(speak_action).encode() + b"\r\n"
            self.socket.send(payload)
            return True
        except Exception as e:
            log(f"Failed to send speak action: {e}", "red")
            return False

    def __handle_primitive_subtask(self, next_subtask: dict) -> None:
        if next_subtask['name'] == 'finish':
            self.__finish_task()
            return

        elif next_subtask['name'] == 'speak':
            msg = next_subtask['parameters']['message']
            if not self.__send_speak_action(msg):
                return None

            history = f"Spoke to the user: '{msg}'"
            self.subtask_history.append(history)
            self.current_subtask = None
            self.subtask_status = Status.WAIT

            # Optional completion_rate; default to 0 when not provided
            if 'completion_rate' in next_subtask['parameters']:
                _ = parse_completion_rate(next_subtask['parameters']['completion_rate'])
            return self.get_next_action()

        # elif next_subtask['name'] == 'scroll_screen':
        #     direction = next_subtask['parameters']['direction']
        #     index = next_subtask['parameters']['scroll_ui_index']
        #
        #     scroll_action = {"name": "scroll", "parameters": {"index": index, "direction": direction}}
        #     self.socket.send(json.dumps(scroll_action).encode())
        #     self.socket.send("\r\n".encode())
        #
        #     if self.task_status == Status.LEARN:
        #         target_info = next_subtask['parameters']['target_info']
        #         history = f"Scrolled screen {direction} to find '{target_info}'"
        #         self.subtask_history.append(history)
        #     self.current_subtask = None
        #     self.subtask_status = Status.WAIT

    def __finish_task(self) -> None:
        """
        Finish the task.
        Returns:
        """
        log("------------END OF THE TASK------------", "blue")
        
        # 在任务结束前，flush所有缓冲中的数据
        self.__flush_all_buffers()
        
        self.end_time = time.time()
        elapsed_time = self.end_time - self.start_time
        minutes = int(elapsed_time / 60)
        seconds = int(elapsed_time)
        
        log(f"""Completed the execution of "{self.instruction}" you commanded, and the Task took a total of [{minutes} minutes({seconds} seconds)] to run.""", "green")
        
        self.current_subtask = None
        self.subtask_status = Status.WAIT

        self.socket.send("$$$$$".encode())
        self.socket.send("\r\n".encode())

        self.subtask_history = [f'Performed an instruction {self.instruction}']

        self.task_path.append({"page_index": self.current_page_index,  # 当前页面索引（任务结束时所在的页面）
                               "subtask_name": "finish",  # 子任务名称（固定为"finish"）
                               "subtask": {"name": "finish", # 子任务详细信息
                                           "description": "Use this to signal that the task has been completed",
                                           "parameters": {}
                                           },
                               "actions": []})
        if self.task_status == Status.LEARN: # 判断当前任务是否处于"学习模式"（Status.LEARN）
            # self.task_path = self.memory.merge_subtasks(self.task_path)

            # 使用 MongoDB 集合 'global_tasks' 保存全局任务
            global_task_database = load_dataframe('global_tasks', ['name', 'description', 'parameters', 'app'])
            global_task_database = pd.concat([global_task_database, pd.DataFrame([self.task])], ignore_index=True)
            save_dataframe('global_tasks', global_task_database)
            # 调用内存管理器的save_task方法，保存当前任务路径到应用专属库
            self.memory.save_task(self.task_path)
        # self.memory.save_task_path(self.task_path)
