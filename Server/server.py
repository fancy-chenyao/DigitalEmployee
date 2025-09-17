import json
import os
import socket
import threading
import queue
import time

from utils.utils import log
from screenParser.Encoder import xmlEncoder
from mobilegpt import MobileGPT
from agents.task_agent import TaskAgent
from datetime import datetime


class Server:
    def __init__(self, host='000.000.000.000', port=12345, buffer_size=4096):
        self.host = host
        self.port = port
        self.buffer_size = buffer_size
        self.memory_directory = './memory'# 核心数据存储目录（日志、截图、XML等）
        self.enable_db = True  # 可配置：是否启用Mongo写入
        self.db_queue: "queue.Queue[dict]" = queue.Queue(maxsize=1000)
        self._db_worker_thread = threading.Thread(target=self._db_worker, name="db-writer", daemon=True)

        # Create the directory for saving received files if it doesn't exist
        if not os.path.exists(self.memory_directory):
            os.makedirs(self.memory_directory)
        # 启动DB后台写入线程
        self._db_worker_thread.start()

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
        screenshots_dir = None
        xmls_dir = None

        # 使用缓冲文件对象提高读取效率
        file_obj = client_socket.makefile('rb')

        while True:
            raw_message_type = file_obj.read(1)

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
                # Receive the string (使用缓冲按行读取)
                instruction = file_obj.readline().decode().strip()

                # 1. TaskAgent解析指令为结构化任务（API格式）
                task, is_new_task = task_agent.get_task(instruction)
                # 2. 无应用依赖：清空任何潜在的app字段，仅基于指令与页面工作
                task['app'] = ''

                # 4. 创建任务专属日志目录（按会话→任务→时间戳分类，便于追溯）
                now = datetime.now()
                # dd/mm/YY H:M:S
                dt_string = now.strftime("%Y_%m_%d_%H-%M-%S")  # 合法格式：2025_08_06_16-41-57
                log_directory = os.path.join(self.memory_directory, f'log/session/{task["name"]}/{dt_string}/')
                # 一次性创建会话期目录
                screenshots_dir = os.path.join(log_directory, "screenshots")
                xmls_dir = os.path.join(log_directory, "xmls")
                os.makedirs(screenshots_dir, exist_ok=True)
                os.makedirs(xmls_dir, exist_ok=True)
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
                # 读取文件大小
                size_line = file_obj.readline().decode().strip()
                file_size = int(size_line)

                # save screenshot image to local temp directory（流式写入，避免内存峰值）
                if screenshots_dir is None:
                    screenshots_dir = os.path.join(log_directory, "screenshots")
                    os.makedirs(screenshots_dir, exist_ok=True)
                scr_shot_path = os.path.join(screenshots_dir, f"{screen_count}.jpg")
                bytes_remaining = file_size
                with open(scr_shot_path, 'wb') as f:
                    while bytes_remaining > 0:
                        chunk = file_obj.read(min(bytes_remaining, self.buffer_size))
                        if not chunk:
                            break
                        f.write(chunk)
                        bytes_remaining -= len(chunk)

                # 将截图路径入队（不再base64存图）
                if self.enable_db:
                    self._enqueue_db_doc({
                        'kind': 'screenshot',
                        'task_name': getattr(self, 'current_task', 'unknown'),
                        'screen_count': screen_count,
                        'screenshot_path': scr_shot_path,
                        'created_at': datetime.now()
                    })

# 接收当前界面的 XML 布局，保存为 .xml → 用 xmlEncoder 解析出可点击控件 → 交给 MobileGPT 决策下一步动作（如点击、滑动、输入）→ 把动作 JSON 发回手机
            elif message_type == 'X':
                # 若在收到XML前尚未通过指令初始化，则进行会话级初始化
                if getattr(mobileGPT, 'memory', None) is None:
                    now = datetime.now()
                    dt_string = now.strftime("%Y_%m_%d_%H-%M-%S")
                    # 准备日志目录并初始化解析器
                    log_directory = os.path.join(self.memory_directory, f'log/session/{dt_string}/')
                    screenshots_dir = os.path.join(log_directory, "screenshots")
                    xmls_dir = os.path.join(log_directory, "xmls")
                    os.makedirs(screenshots_dir, exist_ok=True)
                    os.makedirs(xmls_dir, exist_ok=True)
                    screen_parser.init(log_directory)

                    # 初始化一个默认任务与内存，避免 NoneType 错误
                    default_task = {"name": "session", "app": ""}
                    mobileGPT.init("", default_task, True)
                    self.current_task = default_task["name"]
                    self.current_log_directory = log_directory

                # 1. 调用工具函数__recv_xml接收并保存XML文件（流式落盘）
                raw_xml = self.__recv_xml(file_obj, screen_count, log_directory, xmls_dir)
                
                # 原始XML可选入队
                # 统一合并到单条文档中，见后续入队

                # 2. 解析XML：得到结构化控件信息（parsed_xml）、层级结构（hierarchy_xml）、编码XML（encoded_xml）
                parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(raw_xml, screen_count)
                
                # 合并单屏文档入队，后台批量写库
                if self.enable_db:
                    self._enqueue_db_doc({
                        'kind': 'screen_bundle',
                        'task_name': getattr(self, 'current_task', 'unknown'),
                        'screen_count': screen_count,
                        'screenshot_path': os.path.join(screenshots_dir or '', f"{screen_count}.jpg"),
                        'raw_xml': raw_xml,
                        'parsed_xml': parsed_xml,
                        'hierarchy_xml': hierarchy_xml,
                        'encoded_xml': encoded_xml,
                        'created_at': datetime.now()
                    })
                
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
                qa_string = file_obj.readline().decode().strip()
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
                size_line = file_obj.readline().decode().strip()
                file_size = int(size_line)
                
                # 读取错误数据
                bytes_remaining = file_size
                chunks = []
                while bytes_remaining > 0:
                    data = file_obj.read(min(bytes_remaining, self.buffer_size))
                    if not data:
                        break
                    chunks.append(data)
                    bytes_remaining -= len(data)
                error_string = b''.join(chunks).decode().strip()
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

# 接收获取操作列表请求
            elif message_type == 'G':
                log("Get actions request received", "blue")
                # 这里可以返回可用的操作列表，目前暂时忽略
                pass

    def __recv_xml(self, file_obj, screen_count, log_directory, xmls_dir):
        # Receive the file size (length-prefixed line)
        size_line = file_obj.readline().decode().strip()
        file_size = int(size_line)

        # 拼接XML保存路径（日志目录/xmls/屏幕计数.xml），目录由会话初始化时创建
        if xmls_dir is None:
            xmls_dir = os.path.join(log_directory, "xmls")
            os.makedirs(xmls_dir, exist_ok=True)
        raw_xml_path = os.path.join(xmls_dir, f"{screen_count}.xml")

        # 流式读取并直接写入文件，避免在内存里拼接超大字符串
        bytes_remaining = file_size
        with open(raw_xml_path, 'wb') as f:
            while bytes_remaining > 0:
                chunk = file_obj.read(min(bytes_remaining, self.buffer_size))
                if not chunk:
                    break
                f.write(chunk)
                bytes_remaining -= len(chunk)

        # 读取回字符串供解析器使用（一次I/O，避免双倍内存占用）
        with open(raw_xml_path, 'r', encoding='utf-8') as rf:
            raw_xml = rf.read().strip().replace("class=\"\"", "class=\"unknown\"")
        # 将修复后的字符串覆盖回文件，保证磁盘上也是修复版
        with open(raw_xml_path, 'w', encoding='utf-8') as wf:
            wf.write(raw_xml)
        return raw_xml
    
    def _save_screenshot_to_mongo(self, image_data, screen_count):
        """已弃用：改为异步队列写入，仅保存路径不做base64。保留以兼容旧调用。"""
        if self.enable_db:
            log("_save_screenshot_to_mongo is deprecated; using queued path-based doc", "yellow")
    
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

    def _enqueue_db_doc(self, doc: dict):
        try:
            self.db_queue.put(doc, timeout=0.5)
        except queue.Full:
            log("DB queue full, dropping doc", "yellow")

    def _db_worker(self):
        """后台DB写入线程：批量、合并策略"""
        from utils.mongo_utils import get_db
        db = None
        collection_xml = None
        collection_shot = None
        while True:
            try:
                # 批量拉取
                batch = []
                item = self.db_queue.get()
                if item is not None:
                    batch.append(item)
                t0 = time.time()
                while len(batch) < 50 and (time.time() - t0) < 0.5:
                    try:
                        batch.append(self.db_queue.get(timeout=0.05))
                    except queue.Empty:
                        break

                if not batch:
                    continue

                # 延迟初始化连接
                if db is None:
                    try:
                        db = get_db()
                        collection_xml = db['temp_xmls_bundle']
                        collection_shot = db['temp_screenshots_meta']
                    except Exception as e:
                        log(f"DB init failed in worker: {e}", "red")
                        db = None
                        continue

                # 写入
                for doc in batch:
                    kind = doc.get('kind')
                    if kind == 'screen_bundle' and collection_xml is not None:
                        # 合并为一个文档（同一screen_count幂等）
                        key = {
                            'task_name': doc.get('task_name', 'unknown'),
                            'screen_count': doc.get('screen_count', -1)
                        }
                        to_save = {
                            **key,
                            'screenshot_path': doc.get('screenshot_path'),
                            'raw_xml': doc.get('raw_xml'),
                            'parsed_xml': doc.get('parsed_xml'),
                            'hierarchy_xml': doc.get('hierarchy_xml'),
                            'encoded_xml': doc.get('encoded_xml'),
                            'created_at': doc.get('created_at', datetime.now())
                        }
                        try:
                            collection_xml.replace_one(key, to_save, upsert=True)
                        except Exception as e:
                            log(f"DB write (bundle) failed: {e}", "red")
                    elif kind == 'screenshot' and collection_shot is not None:
                        key = {
                            'task_name': doc.get('task_name', 'unknown'),
                            'screen_count': doc.get('screen_count', -1)
                        }
                        to_save = {
                            **key,
                            'screenshot_path': doc.get('screenshot_path'),
                            'created_at': doc.get('created_at', datetime.now())
                        }
                        try:
                            collection_shot.replace_one(key, to_save, upsert=True)
                        except Exception as e:
                            log(f"DB write (shot) failed: {e}", "red")
            except Exception as e:
                log(f"DB worker loop error: {e}", "red")
