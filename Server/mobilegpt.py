import json
import os
from enum import Enum
import time

import pandas as pd

from agents.derive_agent import DeriveAgent
from agents.explore_agent import ExploreAgent
from agents.select_agent import SelectAgent
from memory.memory_manager import Memory
from utils.utils import log, parse_completion_rate
from utils.mongo_utils import load_dataframe, save_dataframe


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
        self.memory = Memory(task['app'], instruction, task['name'])
        self.explore_agent = ExploreAgent(self.memory)
        self.select_agent = SelectAgent(self.memory, self.instruction)
        self.derive_agent = DeriveAgent(self.memory, self.instruction)
        
        self.start_time = time.time()
        self.end_time = 0
        # 若为新任务，设为学习模式（需记录流程到内存）
        if is_new_task:
            self.task_status = Status.LEARN

        log('Mobile Agent Initialized for app: ' + task['app'] + ' / Task: ' + task['name'])

    def get_next_action(self, parsed_xml=None, hierarchy_xml=None, encoded_xml=None):
        log(":::::::::MobileGPT received new screen:::::::::", 'red')
        parsed_xml = parsed_xml or self.parsed_xml
        hierarchy_xml = hierarchy_xml or self.hierarchy_xml
        encoded_xml = encoded_xml or self.encoded_xml

        self.parsed_xml = parsed_xml
        self.hierarchy_xml = hierarchy_xml
        self.encoded_xml = encoded_xml

        self.current_screen_xml = encoded_xml
        # 检查当前界面是否匹配历史页面（调用内存的search_node方法）
        page_index, new_subtasks = self.memory.search_node(parsed_xml, hierarchy_xml, encoded_xml)

        # 若未匹配到历史页面（page_index == -1），调用ExploreAgent探索新界面
        if page_index == -1:
            page_index = self.explore_agent.explore(parsed_xml, hierarchy_xml, encoded_xml)

        # 若页面索引变化（进入新页面），初始化页面管理器并结束当前子任务
        if page_index != self.current_page_index:
            self.memory.init_page_manager(page_index)
            self.current_page_index = page_index

            if self.subtask_status == Status.LEARN:
                self.__finish_subtask()

        # 获取当前页面的可用子任务（含新生成的子任务）
        available_subtasks = self.memory.get_available_subtasks(page_index)
        if len(new_subtasks) > 0:
            available_subtasks += new_subtasks

        # 若当前无子任务，选择下一个子任务
        if self.current_subtask is None:
            # 从内存中获取下一步子任务（优先复用历史）
            next_subtask = self.memory.get_next_subtask(page_index, self.qa_history, self.current_screen_xml)

            # 若内存中无可用子任务，调用SelectAgent从可用子任务中选择
            if not next_subtask:
                # 调用SelectAgent.select：结合历史和当前界面选择子任务
                response, new_action = self.select_agent.select(available_subtasks, self.subtask_history,
                                                                self.qa_history,
                                                                encoded_xml)
                # 若生成了新动作，添加到内存（供后续复用）
                if new_action:
                    self.memory.add_new_action(new_action, page_index)
                    available_subtasks = self.memory.get_available_subtasks(page_index)

                next_subtask = response['action']# 提取选择的子任务
                if next_subtask['name'] != 'read_screen':
                    msg = response['speak']
                    self.__send_speak_action(msg)
            # 记录当前子任务数据（页面索引、名称、动作等）
            if self.current_subtask_data:# 若存在上一个子任务数据，添加到任务路径
                self.task_path.append(self.current_subtask_data)

            self.current_subtask_data = {"page_index": self.current_page_index,
                                         "subtask_name": next_subtask['name'], "subtask": next_subtask, "actions": []}

            # 初始化推导智能体（传入当前子任务和历史，用于生成动作）
            self.derive_agent.init_subtask(next_subtask, self.subtask_history)
            self.current_subtask = next_subtask  # 更新当前子任务

            if next_subtask['name'] in ['finish', 'speak', 'scroll_screen']:
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

        # 从内存中获取历史动作（回忆模式）
        next_action = self.memory.get_next_action(self.current_subtask, self.encoded_xml)
        current_action_data = {"page_index": self.current_page_index, "action": next_action, "screen": self.encoded_xml,
                               "example": {}}


        if next_action:
            self.subtask_status = Status.RECALL
            # 若内存中有动作示例，调用推导智能体泛化动作（适配当前界面）
            if "examples" in next_action:
                next_action, example = self.derive_agent.derive(self.encoded_xml, examples=next_action['examples'])
                current_action_data['action'] = next_action
                current_action_data['example'] = example

        # 若内存中无动作，调用推导智能体新生成动作（学习模式）
        else:
            # 若子任务处于等待或学习状态，切换到学习模式生成新动作
            if self.subtask_status == Status.WAIT or self.subtask_status == Status.LEARN:
                self.subtask_status = Status.LEARN
                # Here
                next_action, example = self.derive_agent.derive(self.encoded_xml)
                current_action_data['action'] = next_action
                current_action_data['example'] = example

            # 若处于回忆模式但无动作，处理任务分歧（重新选择子任务）
            elif self.subtask_status == Status.RECALL:
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
        log("finish subtask!!", "red")
        log(f"subtask: {self.subtask_status}, task: {self.task_status}", "red")
        if self.subtask_status == Status.LEARN and self.task_status == Status.LEARN:
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

    def __send_speak_action(self, msg) -> None:
        """
        Send a speak action to the device.
        Args:
            msg: message to be spoken by the device.
        """
        speak_action = {"name": "speak", "parameters": {"message": msg}}  # speak action
        self.socket.send(json.dumps(speak_action).encode())
        self.socket.send("\r\n".encode())

    def __handle_primitive_subtask(self, next_subtask: dict) -> None:
        if next_subtask['name'] == 'finish':
            self.__finish_task()
            return

        elif next_subtask['name'] == 'speak':
            msg = next_subtask['parameters']['message']
            speak_action = {"name": "speak", "parameters": {"message": msg}}  # speak action
            self.socket.send(json.dumps(speak_action).encode())
            self.socket.send("\r\n".encode())

            history = f"Spoke to the user: '{msg}'"
            self.subtask_history.append(history)
            self.current_subtask = None
            self.subtask_status = Status.WAIT

            completion_rate = parse_completion_rate(next_subtask['parameters']['completion_rate'])
            return self.get_next_action()

        elif next_subtask['name'] == 'scroll_screen':
            direction = next_subtask['parameters']['direction']
            index = next_subtask['parameters']['scroll_ui_index']

            scroll_action = {"name": "scroll", "parameters": {"index": index, "direction": direction}}
            self.socket.send(json.dumps(scroll_action).encode())
            self.socket.send("\r\n".encode())

            if self.task_status == Status.LEARN:
                target_info = next_subtask['parameters']['target_info']
                history = f"Scrolled screen {direction} to find '{target_info}'"
                self.subtask_history.append(history)
            self.current_subtask = None
            self.subtask_status = Status.WAIT

    def __finish_task(self) -> None:
        """
        Finish the task.
        Returns:
        """
        log("------------END OF THE TASK------------", "blue")
        
        self.end_time = time.time()
        elapsed_time = self.end_time - self.start_time
        minutes = int(elapsed_time / 60)
        seconds = int(elapsed_time)
        
        log(f"""Completed the execution of “{self.instruction}” you commanded, and the Task took a total of [{minutes} minutes({seconds} seconds)] to run.""", "green")
        
        self.current_subtask = None
        self.subtask_status = Status.WAIT

        self.socket.send("$$$$$".encode())
        self.socket.send("\r\n".encode())

        self.subtask_history = [f'Performed an instruction {self.instruction}']

        self.task_path.append({"page_index": self.current_page_index,  # 当前页面索引（任务结束时所在的页面）
                               "subtask_name": "finish",  # 子任务名称（固定为“finish”）
                               "subtask": {"name": "finish", # 子任务详细信息
                                           "description": "Use this to signal that the task has been completed",
                                           "parameters": {}
                                           },
                               "actions": []})
        if self.task_status == Status.LEARN: # 判断当前任务是否处于“学习模式”（Status.LEARN）
            # self.task_path = self.memory.merge_subtasks(self.task_path)

            # 使用 MongoDB 集合 'global_tasks' 保存全局任务
            global_task_database = load_dataframe('global_tasks', ['name', 'description', 'parameters', 'app'])
            global_task_database = pd.concat([global_task_database, pd.DataFrame([self.task])], ignore_index=True)
            save_dataframe('global_tasks', global_task_database)
            # 调用内存管理器的save_task方法，保存当前任务路径到应用专属库
            self.memory.save_task(self.task_path)
        # self.memory.save_task_path(self.task_path)
