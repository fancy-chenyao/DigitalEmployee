import json
import os
import socket
import threading
import sys

# 添加项目根目录到系统路径
project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if project_root not in sys.path:
    sys.path.append(project_root)

from Server import mobilegpt
from utils.utils import log
from screenParser.Encoder import xmlEncoder
from mobilegpt import MobileGPT
from agents.task_agent import TaskAgent
from datetime import datetime

from Reflector_Agent.base import AgentMemory
from Reflector_Agent.reflector import Reflector


class Server:
    def __init__(self, host='000.000.000.000', port=12345, buffer_size=4096):
        self.host = host
        self.port = port
        self.buffer_size = buffer_size
        self.memory_directory = './memory'# 核心数据存储目录（日志、截图、XML等）

        # Create the directory for saving received files if it doesn't exist
        if not os.path.exists(self.memory_directory):
            os.makedirs(self.memory_directory)

    def open(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            # Connecting to an external IP address (Google DNS in this example)
            s.connect(("8.8.8.8", 80))
            real_ip = s.getsockname()[0]
        finally:
            s.close()
    
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.host, self.port))
        server.listen()

        log("--------------------------------------------------------")
        log(f"Server is listening on {real_ip}:{self.port}\nInput this IP address into the app. : [{real_ip}]", "red")

        while True:
            client_socket, client_address = server.accept()
            client_thread = threading.Thread(target=self.handle_client, args=(
                client_socket, client_address))
            client_thread.start()

    def handle_client(self, client_socket, client_address):
        print(f"Connected to client: {client_address}")

        mobileGPT = MobileGPT(client_socket)  #MobileGPT主逻辑（决策下一步动作）
        task_agent = TaskAgent()  #任务智能体（解析用户指令尾结构化任务）
        screen_parser = xmlEncoder()# XML解析器（解析手机界面XML，提取控件信息）
        screen_count = 0  # 屏幕计数（用于截图/XML文件命名，按顺序递增）
        log_directory = self.memory_directory  # 日志根目录

        while True:
            raw_message_type = client_socket.recv(1)

            if not raw_message_type:
                log(f"Connection closed by {client_address}", 'red')
                client_socket.close()
                return
            # 将消息类型字节转为字符串（如'L'、'I'、'S'等）
            message_type = raw_message_type.decode()

            # 无应用依赖模式：不再接收或处理应用列表
            if message_type == 'L':
                log("App-agnostic mode: ignore app list", "blue")

            # 接收用户的自然语言指令，先让 TaskAgent 解析任务 → 再让 AppAgent 预测目标 App → 把包名发回手机
            elif message_type == 'I':  # Instruction
                log("Instruction is received", "blue")
                # Receive the string
                instruction = b''
                while not instruction.endswith(b'\n'):
                    instruction += client_socket.recv(1)
                instruction = instruction.decode().strip()

                # 1. TaskAgent解析指令为结构化任务（API格式）
                task, is_new_task = task_agent.get_task(instruction)
                # 2. 无应用依赖：清空任何潜在的app字段，仅基于指令与页面工作
                task['app'] = ''

                # 4. 创建任务专属日志目录（按会话→任务→时间戳分类，便于追溯）
                now = datetime.now()
                # dd/mm/YY H:M:S
                dt_string = now.strftime("%Y_%m_%d_%H-%M-%S")  # 合法格式：2025_08_06_16-41-57
                log_directory += f'/log/session/{task["name"]}/{dt_string}/'
                screen_parser.init(log_directory)
                
                # 存储当前任务信息用于后续MongoDB存储（无应用字段）
                self.current_task = task["name"]
                self.current_log_directory = log_directory

                # 与移动端协议保持兼容：仍发送包名字段，但为空
                response = "##$$##"
                client_socket.send(response.encode())
                client_socket.send("\r\n".encode())

                mobileGPT.init(instruction, task, is_new_task)

# 接收屏幕截图（jpg）按字节流完整读取后保存到本地临时目录和MongoDB
            elif message_type == 'S':
                file_info = b''
                while not file_info.endswith(b'\n'):
                    file_info += client_socket.recv(1)
                file_size_str = file_info.decode().strip()
                file_size = int(file_size_str)

                # save screenshot image to local temp directory
                screenshots_dir = os.path.join(log_directory, "screenshots")
                if not os.path.exists(screenshots_dir):
                    os.makedirs(screenshots_dir)
                scr_shot_path = os.path.join(screenshots_dir, f"{screen_count}.jpg")
                with open(scr_shot_path, 'wb') as f:
                    bytes_remaining = file_size
                    image_data = b""
                    while bytes_remaining > 0:
                        data = client_socket.recv(min(bytes_remaining, self.buffer_size))
                        image_data += data
                        bytes_remaining -= len(data)
                    f.write(image_data)
                
                # 同时保存到MongoDB（用于后续处理）
                self._save_screenshot_to_mongo(image_data, screen_count)

# 接收当前界面的 XML 布局，保存为 .xml → 用 xmlEncoder 解析出可点击控件 → 交给 MobileGPT 决策下一步动作（如点击、滑动、输入）→ 把动作 JSON 发回手机
            elif message_type == 'X':
                # 若在收到XML前尚未通过指令初始化，则进行会话级初始化
                if getattr(mobileGPT, 'memory', None) is None:
                    now = datetime.now()
                    dt_string = now.strftime("%Y_%m_%d_%H-%M-%S")
                    # 准备日志目录并初始化解析器
                    fallback_log_dir = os.path.join(self.memory_directory, f'log/session/{dt_string}/')
                    log_directory = fallback_log_dir
                    screen_parser.init(log_directory)

                    # 初始化一个默认任务与内存，避免 NoneType 错误
                    default_task = {"name": "session", "app": ""}
                    mobileGPT.init("", default_task, True)
                    self.current_task = default_task["name"]
                    self.current_log_directory = log_directory

                # 1. 调用工具函数__recv_xml接收并保存XML文件
                raw_xml = self.__recv_xml(client_socket, screen_count, log_directory)
                
                # 同时保存原始XML到MongoDB
                self._save_xml_to_mongo(raw_xml, screen_count, 'raw')

                # 2. 解析XML：得到结构化控件信息（parsed_xml）、层级结构（hierarchy_xml）、编码XML（encoded_xml）
                parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(raw_xml, screen_count)
                
                # 保存解析后的XML到MongoDB
                self._save_xml_to_mongo(parsed_xml, screen_count, 'parsed')
                self._save_xml_to_mongo(hierarchy_xml, screen_count, 'hierarchy')
                self._save_xml_to_mongo(encoded_xml, screen_count, 'encoded')
                
                screen_count += 1  # 屏幕计数递增（下一张截图/XML用新编号）

                # 3. 调用MobileGPT决策下一步动作（如点击按钮、输入文本、滑动）
                action = mobileGPT.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)

                # 4. 若有决策结果，将动作转为JSON发给手机端（加换行符标识结束）
                if action is not None:
                    message = json.dumps(action)
                    try:
                        client_socket.send(message.encode())
                        client_socket.send("\r\n".encode())
                    except ConnectionAbortedError:
                        log("Client disconnected during action sending", "yellow")
                        break

# 接收"问答"结果，如果 GPT 需要补充信息（登录验证码、二次确认），手机端弹窗提问 → 用户回答后回传 → 继续任务
            elif message_type == 'A':
                qa_string = b''
                while not qa_string.endswith(b'\n'):
                    qa_string += client_socket.recv(1)
                qa_string = qa_string.decode().strip()
                info_name, question, answer = qa_string.split("\\", 2)
                log(f"QA is received ({question}: {answer})", "blue")
                action = mobileGPT.set_qa_answer(info_name, question, answer)

                if action is not None:
                    message = json.dumps(action)
                    try:
                        client_socket.send(message.encode())
                        client_socket.send("\r\n".encode())
                    except ConnectionAbortedError:
                        log("Client disconnected during QA response sending", "yellow")
                        break

# 接收错误消息，包含完整的上下文信息（preXml、action、instruction等）
            elif message_type == 'E':
                file_info = b''
                while not file_info.endswith(b'\n'):
                    file_info += client_socket.recv(1)
                file_size_str = file_info.decode().strip()
                file_size = int(file_size_str)
                
                # 读取错误数据
                error_data = b''
                bytes_remaining = file_size
                while bytes_remaining > 0:
                    data = client_socket.recv(min(bytes_remaining, self.buffer_size))
                    error_data += data
                    bytes_remaining -= len(data)
                
                error_string = error_data.decode().strip()
                log(f"Error message received: {error_string}", "red")
                
                # 解析错误信息
                error_info = self._parse_error_message(error_string)
                log(f"Parsed error - Type: {error_info.get('error_type', 'UNKNOWN')}, "
                    f"Message: {error_info.get('error_message', 'No message')}, "
                    f"Action: {error_info.get('action', 'None')}, "
                    f"Instruction: {error_info.get('instruction', 'None')}", "red")
                
                # 如果有preXml，保存到MongoDB用于调试
                if error_info.get('pre_xml'):
                    self._save_xml_to_mongo(error_info['pre_xml'], screen_count, 'error_pre_xml')

                # 初始化AgentMemory
                self.agent_memory = AgentMemory(
                    instruction=error_info.get('instruction', 'None'),
                    errTYPE=error_info.get('error_type', 'UNKNOWN'),
                    errMessage=error_info.get('error_message', 'No message'),
                    curXML=error_info.get('cur_xml', 'None'),
                    preXML=error_info.get('pre_xml', 'None'),
                    action=error_info.get('action', 'None')
                )

                # 调用Reflector进行反思分析
                reflector = Reflector(self.agent_memory)
                reflection = reflector.reflect_on_episodic_memory(self.agent_memory)
                
                # 根据反思结果决定下一步操作
                if reflection.need_back:
                    # 需要回退，直接发送回退指令
                    back_action = {"name": "back", "parameters": {}}
                    message = json.dumps(back_action)
                    try:
                        client_socket.send(message.encode())
                        client_socket.send("\r\n".encode())
                        log("Back action sent to client", "blue")
                    except ConnectionAbortedError:
                        log("Client disconnected during back action sending", "yellow")
                        break
                else:
                    # 不需要回退
                    advice = reflection.advice
                    if reflection.problem_type == 0:
                        # 选择了错误的区域
                        log(f"Advice for '选择了错误的区域': {advice}", "blue")
                        
                        # 获取当前XML数据（从错误信息中提取）
                        current_xml = error_info.get('cur_xml', '')
                        if current_xml:
                            # 解析当前XML以获得所需的格式
                            try:
                                parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(current_xml, screen_count)
                                
                                # 搜索当前页面节点并获取可用子任务
                                page_index, new_subtasks = mobileGPT.memory.search_node(parsed_xml, hierarchy_xml, encoded_xml)
                                available_subtasks = mobileGPT.memory.get_available_subtasks(page_index)
                                if len(new_subtasks) > 0:
                                    available_subtasks += new_subtasks
                                
                                # 调用SelectAgent.select：结合历史和当前界面选择子任务，传入反思建议
                                response, new_action = mobileGPT.select_agent.select(
                                    available_subtasks, 
                                    mobileGPT.subtask_history,
                                    mobileGPT.qa_history,
                                    encoded_xml, 
                                    [advice] if advice else []  # 将反思建议作为suggestions传入，确保格式正确
                                )
                                
                                # 若生成了新动作，添加到内存（供后续复用）
                                if new_action:
                                    mobileGPT.memory.add_new_action(new_action, page_index)
                                
                                # 提取选择的子任务
                                next_subtask = response['action']
                                
                                # 处理speak动作（与mobilegpt.py保持一致）
                                if next_subtask['name'] != 'read_screen':
                                    msg = response['speak']
                                    speak_action = {"name": "speak", "parameters": {"message": msg}}
                                    try:
                                        client_socket.send(json.dumps(speak_action).encode())
                                        client_socket.send("\r\n".encode())
                                        log(f"Speak action sent: {msg}", "blue")
                                    except ConnectionAbortedError:
                                        log("Client disconnected during speak action sending", "yellow")
                                        break
                                
                                # 更新MobileGPT的子任务状态和历史
                                if mobileGPT.current_subtask_data:
                                    mobileGPT.task_path.append(mobileGPT.current_subtask_data)
                                
                                mobileGPT.current_subtask_data = {
                                    "page_index": page_index,
                                    "subtask_name": next_subtask['name'], 
                                    "subtask": next_subtask, 
                                    "actions": []
                                }
                                
                                # 初始化推导智能体
                                mobileGPT.derive_agent.init_subtask(next_subtask, mobileGPT.subtask_history)
                                mobileGPT.current_subtask = next_subtask
                                
                                # 处理基础子任务（finish, speak, scroll_screen）
                                if next_subtask['name'] in ['finish', 'speak', 'scroll_screen']:
                                    primitive_action = mobileGPT._MobileGPT__handle_primitive_subtask(next_subtask)
                                    if primitive_action:
                                        try:
                                            client_socket.send(json.dumps(primitive_action).encode())
                                            client_socket.send("\r\n".encode())
                                            log(f"Primitive action sent: {primitive_action['name']}", "blue")
                                        except ConnectionAbortedError:
                                            log("Client disconnected during primitive action sending", "yellow")
                                            break
                                else:
                                    # 对于复杂子任务，调用derive_agent生成具体动作
                                    try:
                                        next_action, example = mobileGPT.derive_agent.derive(encoded_xml, suggestions=[advice] if advice else [])
                                        
                                        # 记录动作数据
                                        current_action_data = {
                                            "page_index": page_index, 
                                            "action": next_action, 
                                            "screen": encoded_xml,
                                            "example": example
                                        }
                                        mobileGPT.current_subtask_data['actions'].append(current_action_data)
                                        
                                        # 发送动作到客户端
                                        if next_action:
                                            message = json.dumps(next_action)
                                            try:
                                                client_socket.send(message.encode())
                                                client_socket.send("\r\n".encode())
                                                log(f"Corrective action sent to client: {next_action['name']}", "blue")
                                            except ConnectionAbortedError:
                                                log("Client disconnected during corrective action sending", "yellow")
                                                break
                                    except Exception as derive_error:
                                        log(f"Error in derive_agent: {derive_error}", "red")
                                        # 发送finish动作作为兜底
                                        finish_action = {"name": "finish", "parameters": {}}
                                        try:
                                            client_socket.send(json.dumps(finish_action).encode())
                                            client_socket.send("\r\n".encode())
                                        except ConnectionAbortedError:
                                            log("Client disconnected during error recovery", "yellow")
                                            break
                                        
                            except Exception as e:
                                log(f"Error processing corrective action: {e}", "red")
                                # 发送默认的finish动作
                                finish_action = {"name": "finish", "parameters": {}}
                                try:
                                    client_socket.send(json.dumps(finish_action).encode())
                                    client_socket.send("\r\n".encode())
                                except ConnectionAbortedError:
                                    log("Client disconnected during error recovery", "yellow")
                                    break
                        else:
                            log("No current XML available for corrective action", "red")

                    else:
                        # 其他的错误默认为指令错误
                        # 重新使用deriveAgent生成动作，发送到客户端
                        log("Handling instruction error - regenerating action with derive_agent", "yellow")
                        
                        if encoded_xml and mobileGPT.current_subtask:
                            try:
                                # 获取当前页面索引
                                current_xml = error_info.get('cur_xml', '')
                                if current_xml:
                                    parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(current_xml, screen_count)
                                    page_index, _ = mobileGPT.memory.search_node(parsed_xml, hierarchy_xml, encoded_xml)
                                else:
                                    # 如果没有当前XML，使用当前页面索引
                                    page_index = mobileGPT.current_page_index
                                
                                # 使用derive_agent重新生成动作，传入反思建议
                                suggestions = [advice] if advice else []
                                next_action, example = mobileGPT.derive_agent.derive(encoded_xml, suggestions=suggestions)
                                
                                # 记录重新生成的动作数据
                                current_action_data = {
                                    "page_index": page_index,
                                    "action": next_action,
                                    "screen": encoded_xml,
                                    "example": example,
                                    "regenerated": True  # 标记为重新生成的动作
                                }
                                
                                if mobileGPT.current_subtask_data:
                                    mobileGPT.current_subtask_data['actions'].append(current_action_data)
                                
                                # 发送重新生成的动作到客户端
                                if next_action:
                                    message = json.dumps(next_action)
                                    try:
                                        client_socket.send(message.encode())
                                        client_socket.send("\r\n".encode())
                                        log(f"Regenerated action sent to client: {next_action['name']}", "green")
                                    except ConnectionAbortedError:
                                        log("Client disconnected during regenerated action sending", "yellow")
                                        break
                                    except Exception as send_error:
                                        log(f"Failed to send regenerated action: {send_error}", "red")
                                        # 尝试发送finish动作作为兜底
                                        try:
                                            finish_action = {"name": "finish", "parameters": {}}
                                            client_socket.send(json.dumps(finish_action).encode())
                                            client_socket.send("\r\n".encode())
                                            log("Sent finish action after send error", "yellow")
                                        except:
                                            log("Failed to send fallback finish action", "red")
                                            break
                                else:
                                    # 如果derive_agent返回None，发送finish动作
                                    log("Derive agent returned None, sending finish action", "yellow")
                                    finish_action = {"name": "finish", "parameters": {}}
                                    try:
                                        client_socket.send(json.dumps(finish_action).encode())
                                        client_socket.send("\r\n".encode())
                                    except ConnectionAbortedError:
                                        log("Client disconnected during finish action sending", "yellow")
                                        break
                                    except Exception as send_error:
                                        log(f"Failed to send finish action: {send_error}", "red")
                                        break
                                        
                            except Exception as derive_error:
                                log(f"Error in derive_agent during instruction error recovery: {derive_error}", "red")
                                # 发送finish动作作为最终兜底
                                finish_action = {"name": "finish", "parameters": {}}
                                try:
                                    client_socket.send(json.dumps(finish_action).encode())
                                    client_socket.send("\r\n".encode())
                                    log("Sent finish action as final fallback", "yellow")
                                except ConnectionAbortedError:
                                    log("Client disconnected during final fallback", "yellow")
                                    break
                                except Exception as send_error:
                                    log(f"Failed to send final fallback finish action: {send_error}", "red")
                                    break
                        else:
                            # 缺少必要的上下文信息，无法重新生成动作
                            log("Missing context (XML or current_subtask) for instruction error recovery", "red")
                            # 尝试重置当前子任务状态，为后续操作做准备
                            if hasattr(mobileGPT, 'current_subtask'):
                                mobileGPT.current_subtask = None
                            if hasattr(mobileGPT, 'current_subtask_data'):
                                mobileGPT.current_subtask_data = None
                            
                            finish_action = {"name": "finish", "parameters": {}}
                            try:
                                client_socket.send(json.dumps(finish_action).encode())
                                client_socket.send("\r\n".encode())
                                log("Sent finish action due to missing context", "yellow")
                            except ConnectionAbortedError:
                                log("Client disconnected during context error recovery", "yellow")
                                break
                            except Exception as send_error:
                                log(f"Failed to send finish action: {send_error}", "red")
                                break


# 接收获取操作列表请求
            elif message_type == 'G':
                log("Get actions request received", "blue")
                # 这里可以返回可用的操作列表，目前暂时忽略
                pass

    def __recv_xml(
        self, client_socket, screen_count, log_directory):
        # Receive the file name and size
        file_info = b''
        while not file_info.endswith(b'\n'):
            file_info += client_socket.recv(1)
        file_size_str = file_info.decode().strip()
        file_size = int(file_size_str)

        # 2. 拼接XML保存路径（日志目录/xmls/屏幕计数.xml）
        xmls_dir = os.path.join(log_directory, "xmls")
        if not os.path.exists(xmls_dir):
            os.makedirs(xmls_dir)
        raw_xml_path = os.path.join(xmls_dir, f"{screen_count}.xml")

        # 3. 完整读取XML字节流，修复空class属性（避免后续解析出错）
        with open(raw_xml_path, 'w', encoding='utf-8') as f:
            bytes_remaining = file_size
            string_data = b''
            while bytes_remaining > 0:
                data = client_socket.recv(min(bytes_remaining, self.buffer_size))
                string_data += data
                bytes_remaining -= len(data)
            raw_xml = string_data.decode().strip().replace("class=\"\"", "class=\"unknown\"")
            f.write(raw_xml)
        return raw_xml
    
    def _save_screenshot_to_mongo(self, image_data, screen_count):
        """将屏幕截图保存到MongoDB（无 app 维度）"""
        import base64
        from utils.mongo_utils import get_db
        
        try:
            db = get_db()
            collection = db['temp_screenshots']
            
            screenshot_data = {
                'task_name': getattr(self, 'current_task', 'unknown'),
                'screen_count': screen_count,
                'screenshot': base64.b64encode(image_data).decode('utf-8'),
                'created_at': datetime.now()
            }
            
            collection.replace_one(
                {
                    'task_name': screenshot_data['task_name'],
                    'screen_count': screen_count
                },
                screenshot_data,
                upsert=True
            )
        except Exception as e:
            log(f"Failed to save screenshot to MongoDB: {e}", "red")
    
    def _save_xml_to_mongo(self, xml_data, screen_count, xml_type):
        """将XML数据保存到MongoDB（无 app 维度）"""
        from utils.mongo_utils import get_db
        
        try:
            db = get_db()
            collection = db['temp_xmls']
            
            xml_doc = {
                'task_name': getattr(self, 'current_task', 'unknown'),
                'screen_count': screen_count,
                'xml_type': xml_type,
                'xml_content': xml_data,
                'created_at': datetime.now()
            }
            
            collection.replace_one(
                {
                    'task_name': xml_doc['task_name'],
                    'screen_count': screen_count,
                    'xml_type': xml_type
                },
                xml_doc,
                upsert=True
            )
        except Exception as e:
            log(f"Failed to save XML to MongoDB: {e}", "red")
    
    def _parse_error_message(self, error_string):
        """解析错误消息，提取各种上下文信息"""
        error_info = {}
        lines = error_string.split('\n')
        
        for line in lines:
            line = line.strip()
            if line.startswith('ERROR_TYPE:'):
                error_info['error_type'] = line[11:]
            elif line.startswith('ERROR_MESSAGE:'):
                error_info['error_message'] = line[14:]
            elif line.startswith('ACTION:'):
                error_info['action'] = line[7:]
            elif line.startswith('INSTRUCTION:'):
                error_info['instruction'] = line[12:]
            elif line.startswith('REMARK:'):
                error_info['remark'] = line[7:]
            elif line == 'PRE_XML:':
                # 找到PRE_XML标记，收集后续所有行作为XML内容
                xml_lines = []
                for xml_line in lines[lines.index(line) + 1:]:
                    if xml_line.startswith(('ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:')):
                        break
                    xml_lines.append(xml_line)
                if xml_lines:
                    error_info['pre_xml'] = '\n'.join(xml_lines)
        
        return error_info
