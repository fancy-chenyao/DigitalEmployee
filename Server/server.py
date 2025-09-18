import json
import os
import socket
import threading
import queue
import time
from typing import Optional

from utils.utils import log
from log_config import setup_logging, log_with_color, log_system_status
from screenParser.Encoder import xmlEncoder
from mobilegpt import MobileGPT
from agents.task_agent import TaskAgent
from datetime import datetime
from utils.mongo_utils import check_connection, get_connection_info, close_connection
from env_config import Config
from session_manager import SessionManager, ClientSession, resource_lock
from async_processor import async_processor, message_queue


class Server:
    def __init__(self, host=None, port=None, buffer_size=None):
        # 设置增强日志
        setup_logging("INFO", True)
        log_system_status()
        
        # 使用配置类获取参数
        config = Config.get_server_config()
        self.host = host or config['host']
        self.port = port or config['port']
        self.buffer_size = buffer_size or config['buffer_size']
        
        self.memory_directory = Config.MEMORY_DIRECTORY
        self.enable_db = Config.ENABLE_DB
        self.db_queue: "queue.Queue[dict]" = queue.Queue(maxsize=1000)
        self._db_worker_thread = threading.Thread(target=self._db_worker, name="db-writer", daemon=True)
        
        # 打印配置信息
        Config.print_config()
        
        # 检查MongoDB连接
        if self.enable_db:
            if not check_connection():
                log("MongoDB连接检查失败，尝试重新连接...", "yellow")
                from utils.mongo_utils import reconnect
                if not reconnect():
                    log("MongoDB连接失败，将使用文件系统存储", "red")
                    self.enable_db = False
            else:
                log("MongoDB连接正常", "green")
                # MongoDB连接池信息日志已删除，减少日志噪音

        # Create the directory for saving received files if it doesn't exist
        if not os.path.exists(self.memory_directory):
            os.makedirs(self.memory_directory)
        # 启动DB后台写入线程
        self._db_worker_thread.start()
        
        # 启动连接监控线程
        self._monitor_thread = threading.Thread(target=self._connection_monitor, name="connection-monitor", daemon=True)
        self._monitor_thread.start()
        
        # 初始化会话管理器
        self.session_manager = SessionManager()
        
        # 异步处理器已在初始化时自动启动
        log("异步处理器已就绪", "green")
        
        # 启动消息队列
        message_queue.start(self._process_message)

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
            
            # 创建客户端会话
            session = self.session_manager.create_session(client_socket, client_address)
            
            # 启动客户端处理线程
            client_thread = threading.Thread(
                target=self.handle_client_with_session, 
                args=(session,), 
                name=f"Client-{session.session_id}"
            )
            client_thread.start()

    def handle_client_with_session(self, session: ClientSession):
        """使用会话管理处理客户端连接"""
        log(f"处理客户端会话: {session.session_id} from {session.client_address}", "green")
        
        try:
            # 为会话创建MobileGPT实例
            mobileGPT = MobileGPT(session.client_socket)
            session.mobilegpt = mobileGPT
            
            # 处理客户端消息
            self._process_client_messages(session)
            
        except Exception as e:
            log(f"处理客户端会话时出错: {e}", "red")
        finally:
            # 清理会话
            self.session_manager.remove_session(session.session_id)
            log(f"客户端会话已清理: {session.session_id}", "yellow")

    def _process_client_messages(self, session: ClientSession):
        """处理客户端消息"""
        client_socket = session.client_socket
        
        # 创建一次性的文件对象，避免重复创建
        try:
            client_file = client_socket.makefile('rb')
            
            while True:
                try:
                    # 接收消息
                    message = self._receive_message_with_file(client_file)
                    if not message:
                        break
                    
                    # 更新会话活动时间
                    session.update_activity()
                    
                    # 异步处理消息
                    self._handle_message_async(session, message)
                    
                except Exception as e:
                    log(f"处理消息时出错: {e}", "red")
                    break
                    
        finally:
            try:
                client_file.close()
            except:
                pass

    def _receive_message_with_file(self, client_file) -> Optional[dict]:
        """使用已创建的文件对象接收消息"""
        try:
            # 先读取第一个字节判断消息类型
            message_type_byte = client_file.read(1)
            if not message_type_byte:
                log("客户端断开连接", "yellow")
                return None
            
            message_type = message_type_byte.decode()
            log(f"检测到消息类型: {message_type}", "blue")
            
            # 根据消息类型处理不同的格式
            if message_type in ['I', 'X', 'S', 'A', 'E', 'G']:
                # 旧格式：直接读取内容
                log(f"使用旧格式解析消息: {message_type}", "blue")
                return self._receive_legacy_message(client_file, message_type)
            else:
                # 新格式：JSON格式
                log(f"使用新格式解析消息: {message_type}", "blue")
                return self._receive_json_message(client_file, message_type)
            
        except Exception as e:
            log(f"接收消息失败: {e}", "red")
            return None

    def _receive_message(self, client_socket) -> Optional[dict]:
        """接收客户端消息，兼容新旧格式（向后兼容方法）"""
        try:
            # 使用缓冲读取
            client_file = client_socket.makefile('rb')
            
            # 先读取第一个字节判断消息类型
            message_type_byte = client_file.read(1)
            if not message_type_byte:
                return None
            
            message_type = message_type_byte.decode()
            
            # 根据消息类型处理不同的格式
            if message_type in ['I', 'X', 'S', 'A', 'E', 'G']:
                # 旧格式：直接读取内容
                return self._receive_legacy_message(client_file, message_type)
            else:
                # 新格式：JSON格式
                return self._receive_json_message(client_file, message_type)
            
        except Exception as e:
            log(f"接收消息失败: {e}", "red")
            return None
    
    def _receive_legacy_message(self, client_file, message_type: str) -> Optional[dict]:
        """接收旧格式消息"""
        try:
            if message_type == 'I':
                # 指令消息
                log("解析指令消息", "blue")
                instruction_line = client_file.readline()
                if not instruction_line:
                    log("指令消息为空", "yellow")
                    return None
                instruction = instruction_line.decode().strip()
                log(f"解析到指令: {instruction}", "green")
                return {
                    'messageType': 'instruction',
                    'instruction': instruction
                }
            elif message_type == 'X':
                # XML消息
                log("解析XML消息", "blue")
                length_line = client_file.readline()
                if not length_line:
                    log("XML消息长度为空", "yellow")
                    return None
                message_length = int(length_line.decode().strip())
                log(f"XML消息长度: {message_length}字节", "blue")
                xml_data = client_file.read(message_length)
                if len(xml_data) != message_length:
                    log(f"XML数据长度不匹配: 期望{message_length}, 实际{len(xml_data)}", "red")
                    return None
                xml_content = xml_data.decode('utf-8')
                log(f"XML解析完成: {len(xml_content)}字符", "green")
                return {
                    'messageType': 'xml',
                    'xml': xml_content
                }
            elif message_type == 'S':
                # 截图消息
                log("解析截图消息", "blue")
                length_line = client_file.readline()
                if not length_line:
                    log("截图消息长度为空", "yellow")
                    return None
                message_length = int(length_line.decode().strip())
                log(f"截图消息长度: {message_length}字节", "blue")
                screenshot_data = client_file.read(message_length)
                if len(screenshot_data) != message_length:
                    log(f"截图数据长度不匹配: 期望{message_length}, 实际{len(screenshot_data)}", "red")
                    return None
                log(f"截图解析完成: {len(screenshot_data)}字节", "green")
                return {
                    'messageType': 'screenshot',
                    'screenshot': screenshot_data
                }
            elif message_type == 'A':
                # 问答消息
                qa_line = client_file.readline()
                if not qa_line:
                    return None
                qa_content = qa_line.decode().strip()
                return {
                    'messageType': 'qa',
                    'qa': qa_content
                }
            elif message_type == 'E':
                # 错误消息
                length_line = client_file.readline()
                if not length_line:
                    return None
                message_length = int(length_line.decode().strip())
                error_data = client_file.read(message_length)
                if len(error_data) != message_length:
                    return None
                error_content = error_data.decode('utf-8')
                return {
                    'messageType': 'error',
                    'error': error_content
                }
            elif message_type == 'G':
                # 获取操作消息
                return {
                    'messageType': 'get_actions'
                }
            else:
                log(f"未知的旧格式消息类型: {message_type}", "yellow")
                return None
                
        except Exception as e:
            log(f"解析旧格式消息失败: {e}", "red")
            return None
    
    def _receive_json_message(self, client_file, message_type: str) -> Optional[dict]:
        """接收新格式JSON消息"""
        try:
            # 读取消息长度
            length_line = client_file.readline()
            if not length_line:
                return None
            
            # 安全地解析消息长度
            try:
                message_length = int(length_line.decode().strip())
            except ValueError:
                log(f"无效的消息长度格式: {length_line.decode().strip()}", "red")
                return None
            
            # 读取消息内容
            message_data = client_file.read(message_length)
            if len(message_data) != message_length:
                log(f"消息长度不匹配: 期望{message_length}, 实际{len(message_data)}", "red")
                return None
            
            # 解析JSON消息
            message = json.loads(message_data.decode('utf-8'))
            return message
            
        except Exception as e:
            log(f"解析JSON消息失败: {e}", "red")
            return None

    def _handle_message_async(self, session: ClientSession, message: dict):
        """异步处理消息"""
        message_type = message.get('messageType', '')
        log(f"收到消息: 类型={message_type}, 会话={session.session_id}", "blue")
        
        if message_type == 'instruction' or message_type == 'I':  # 指令消息
            log("处理指令消息", "green")
            self._handle_instruction_message(session, message)
        elif message_type == 'xml' or message_type == 'X':  # XML消息
            log("处理XML消息", "green")
            self._handle_xml_message(session, message)
        elif message_type == 'screenshot' or message_type == 'S':  # 截图消息
            log("处理截图消息", "green")
            self._handle_screenshot_message(session, message)
        elif message_type == 'qa' or message_type == 'A':  # 问答消息
            log("处理问答消息", "green")
            self._handle_qa_message(session, message)
        elif message_type == 'error' or message_type == 'E':  # 错误消息
            log("处理错误消息", "yellow")
            self._handle_error_message(session, message)
        elif message_type == 'get_actions' or message_type == 'G':  # 获取操作消息
            log("处理获取操作消息", "green")
            self._handle_get_actions_message(session, message)
        else:
            log(f"未知消息类型: {message_type}", "red")

    def _handle_instruction_message(self, session: ClientSession, message: dict):
        """处理指令消息 - 异步版本"""
        instruction = message.get('instruction', '')
        log(f"收到指令: {instruction}", "cyan")
        session.instruction = instruction
        
        # 异步处理指令
        self._process_instruction_async(session, instruction)

    def _process_instruction_async(self, session: ClientSession, instruction: str):
        """异步处理指令，保持功能稳定性"""
        try:
            log("开始异步处理指令业务逻辑", "green")
            
            # 准备异步任务数据
            task_data = {
                'instruction': instruction,
                'session_id': session.session_id,
                'client_socket': session.client_socket
            }
            
            # 定义回调函数
            def instruction_callback(result):
                try:
                    if result.get('status') == 'instruction_processed':
                        # 保存MobileGPT实例到会话
                        session.mobilegpt = result.get('mobilegpt')
                        log("指令异步处理完成，MobileGPT实例已保存", "green")
                    else:
                        log(f"指令异步处理失败: {result.get('error', 'unknown error')}", "red")
                except Exception as e:
                    log(f"指令回调处理失败: {e}", "red")
            
            # 提交异步任务
            task_id = async_processor.submit_task_with_callback(
                session_id=session.session_id,
                task_type="instruction_processing",
                data=task_data,
                callback=instruction_callback,
                priority=10  # 高优先级
            )
            
            if task_id:
                log(f"指令异步任务已提交: {task_id}", "blue")
            else:
                log("指令异步任务提交失败，回退到同步处理", "yellow")
                # 回退到同步处理，确保功能稳定性
                self._process_instruction_directly(session, instruction)
                
        except Exception as e:
            log(f"异步指令处理失败: {e}，回退到同步处理", "red")
            # 回退到同步处理，确保功能稳定性
            self._process_instruction_directly(session, instruction)

    def _process_instruction_directly(self, session: ClientSession, instruction: str):
        """直接处理指令，执行完整的业务逻辑"""
        try:
            log("开始处理指令业务逻辑", "green")
            
            # 创建TaskAgent解析指令
            task_agent = TaskAgent()
            task, is_new_task = task_agent.get_task(instruction)
            
            log(f"TaskAgent解析结果: 任务={task.get('name', 'unknown')}, 新任务={is_new_task}", "green")
            
            # 创建MobileGPT实例处理业务逻辑
            mobileGPT = MobileGPT(session.client_socket)
            session.mobilegpt = mobileGPT
            
            # 初始化MobileGPT
            mobileGPT.init(instruction, task, is_new_task)
            
            log("MobileGPT初始化完成", "green")
            
            # 这里应该继续处理后续逻辑，比如等待XML和截图数据
            # 然后调用mobileGPT的相应方法
            
        except Exception as e:
            log(f"指令处理失败: {e}", "red")
            import traceback
            traceback.print_exc()

    def _handle_xml_message(self, session: ClientSession, message: dict):
        """处理XML消息 - 异步版本"""
        xml_content = message.get('xml', '')
        xml_length = len(xml_content) if xml_content else 0
        log(f"收到XML数据: 长度={xml_length}字符", "cyan")
        
        # 异步处理XML
        self._process_xml_async(session, xml_content)

    def _process_xml_async(self, session: ClientSession, xml_content: str):
        """异步处理XML，保持功能稳定性"""
        try:
            # 如果MobileGPT实例不存在或memory属性未初始化，等待指令处理完成
            if (not hasattr(session, 'mobilegpt') or 
                not session.mobilegpt or 
                not hasattr(session.mobilegpt, 'memory') or 
                session.mobilegpt.memory is None):
                log("MobileGPT实例或memory属性未准备就绪，等待指令处理完成", "yellow")
                # 等待指令处理完成，最多等待10秒
                self._wait_for_mobilegpt(session, xml_content, max_wait=10)
                return
            
            log("开始异步处理XML", "green")
            
            # 准备异步任务数据
            task_data = {
                'xml': xml_content,
                'session_id': session.session_id,
                'mobilegpt': session.mobilegpt
            }
            
            # 定义回调函数
            def xml_callback(result):
                try:
                    if result.get('status') == 'xml_processed' and result.get('action'):
                        # 发送动作给客户端
                        self._send_action_to_client(session, result['action'])
                        log("XML异步处理完成，动作已发送", "green")
                    elif result.get('status') == 'xml_processed_no_action':
                        log("XML异步处理完成，无动作返回", "yellow")
                    else:
                        log(f"XML异步处理失败: {result.get('error', 'unknown error')}", "red")
                except Exception as e:
                    log(f"XML回调处理失败: {e}", "red")
            
            # 提交异步任务
            task_id = async_processor.submit_task_with_callback(
                session_id=session.session_id,
                task_type="xml_processing",
                data=task_data,
                callback=xml_callback,
                priority=5  # 中等优先级
            )
            
            if task_id:
                log(f"XML异步任务已提交: {task_id}", "blue")
            else:
                log("XML异步任务提交失败，回退到同步处理", "yellow")
                # 回退到同步处理，确保功能稳定性
                self._process_xml_directly(session, xml_content)
                
        except Exception as e:
            log(f"异步XML处理失败: {e}，回退到同步处理", "red")
            # 回退到同步处理，确保功能稳定性
            self._process_xml_directly(session, xml_content)

    def _wait_for_mobilegpt(self, session: ClientSession, xml_content: str, max_wait: int = 10):
        """等待MobileGPT实例准备就绪"""
        import time
        
        start_time = time.time()
        log(f"开始等待MobileGPT实例准备就绪，最多等待{max_wait}秒", "blue")
        
        while time.time() - start_time < max_wait:
            # 检查MobileGPT实例是否存在
            if not hasattr(session, 'mobilegpt'):
                log("等待中: MobileGPT实例不存在", "blue")
            elif session.mobilegpt is None:
                log("等待中: MobileGPT实例为None", "blue")
            elif not hasattr(session.mobilegpt, 'memory'):
                log("等待中: MobileGPT实例没有memory属性", "blue")
            elif session.mobilegpt.memory is None:
                pass
            else:
                log("MobileGPT实例已准备就绪，开始处理XML", "green")
                self._process_xml_async(session, xml_content)
                return
            
            time.sleep(0.5)  # 等待500ms
        
        log(f"等待MobileGPT实例超时({max_wait}秒)，回退到同步处理", "yellow")
        self._process_xml_directly(session, xml_content)

    def _process_xml_directly(self, session: ClientSession, xml_content: str):
        """直接处理XML，确保功能稳定性"""
        try:
            if not hasattr(session, 'mobilegpt') or not session.mobilegpt:
                log("MobileGPT实例不存在，跳过XML处理", "yellow")
                return
                
            log("使用MobileGPT直接处理XML", "green")
            
            # 解析XML数据
            from screenParser.Encoder import xmlEncoder
            screen_parser = xmlEncoder()
            
            # 解析XML
            parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(xml_content, 0)
            
            log(f"XML解析完成: parsed={len(parsed_xml)}字符, hierarchy={len(hierarchy_xml)}字符, encoded={len(encoded_xml)}字符", "green")
            
            # 调用MobileGPT的get_next_action方法
            action = session.mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
            
            if action:
                log(f"MobileGPT返回动作: {action}", "green")
                # 发送动作给客户端
                self._send_action_to_client(session, action)
            else:
                log("MobileGPT未返回动作", "yellow")
                
        except Exception as e:
            log(f"MobileGPT处理XML失败: {e}", "red")
            import traceback
            traceback.print_exc()

    def _send_action_to_client(self, session: ClientSession, action: dict):
        """发送动作给客户端"""
        try:
            log(f"发送动作给客户端: {action}", "green")
            
            # 将动作转换为JSON字符串
            action_json = json.dumps(action, ensure_ascii=False)
            
            # 发送给客户端
            client_socket = session.client_socket
            client_socket.send(action_json.encode('utf-8'))
            client_socket.send(b'\r\n')  # 添加结束符
            
            log("动作发送成功", "green")
            
        except Exception as e:
            log(f"发送动作失败: {e}", "red")

    def _handle_screenshot_message(self, session: ClientSession, message: dict):
        """处理截图消息 - 异步版本"""
        screenshot_data = message.get('screenshot', b'')
        screenshot_size = len(screenshot_data) if screenshot_data else 0
        log(f"收到截图数据: 大小={screenshot_size}字节", "cyan")
        
        # 异步处理截图
        self._process_screenshot_async(session, screenshot_data)

    def _process_screenshot_async(self, session: ClientSession, screenshot_data: bytes):
        """异步处理截图，保持功能稳定性"""
        try:
            log("开始异步处理截图", "green")
            
            # 准备异步任务数据
            task_data = {
                'screenshot': screenshot_data,
                'session_id': session.session_id,
                'mobilegpt': getattr(session, 'mobilegpt', None)
            }
            
            # 定义回调函数
            def screenshot_callback(result):
                try:
                    if result.get('status') == 'screenshot_processed':
                        log("截图异步处理完成", "green")
                    else:
                        log(f"截图异步处理失败: {result.get('error', 'unknown error')}", "red")
                except Exception as e:
                    log(f"截图回调处理失败: {e}", "red")
            
            # 提交异步任务
            task_id = async_processor.submit_task_with_callback(
                session_id=session.session_id,
                task_type="screenshot_processing",
                data=task_data,
                callback=screenshot_callback,
                priority=3  # 低优先级
            )
            
            if task_id:
                log(f"截图异步任务已提交: {task_id}", "blue")
            else:
                log("截图异步任务提交失败，回退到同步处理", "yellow")
                # 回退到同步处理，确保功能稳定性
                self._process_screenshot_directly(session, screenshot_data)
                
        except Exception as e:
            log(f"异步截图处理失败: {e}，回退到同步处理", "red")
            # 回退到同步处理，确保功能稳定性
            self._process_screenshot_directly(session, screenshot_data)

    def _process_screenshot_directly(self, session: ClientSession, screenshot_data: bytes):
        """直接处理截图，确保功能稳定性"""
        try:
            if hasattr(session, 'mobilegpt') and session.mobilegpt:
                log("使用MobileGPT直接处理截图", "green")
                # 这里可以添加截图处理逻辑
                log("截图数据已传递给MobileGPT", "green")
            else:
                log("MobileGPT实例不存在，跳过截图处理", "yellow")
        except Exception as e:
            log(f"MobileGPT处理截图失败: {e}", "red")

    def _handle_qa_message(self, session: ClientSession, message: dict):
        """处理问答消息"""
        qa_content = message.get('qa', '')
        log(f"收到问答消息: {qa_content}", "blue")
        
        # 可以在这里添加问答处理逻辑
        # 目前只是记录日志

    def _handle_error_message(self, session: ClientSession, message: dict):
        """处理错误消息"""
        error_content = message.get('error', '')
        log(f"收到错误消息: {error_content}", "red")
        
        # 可以在这里添加错误处理逻辑
        # 目前只是记录日志

    def _handle_get_actions_message(self, session: ClientSession, message: dict):
        """处理获取操作消息"""
        log("收到获取操作请求", "blue")
        
        # 可以在这里添加获取操作列表的逻辑
        # 目前只是记录日志

    def _process_message(self, message: dict):
        """处理消息队列中的消息"""
        # 这里可以添加消息处理逻辑
        pass

    def handle_client(self, client_socket, client_address):
        """原始客户端处理方法（保留兼容性）"""
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
                    if reflection.problem_type == 'area':
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

    def _connection_monitor(self):
        """
        MongoDB连接监控线程
        定期检查连接健康状态，必要时进行重连
        """
        while True:
            try:
                if self.enable_db:
                    if not check_connection():
                        log("MongoDB连接异常，尝试重连...", "yellow")
                        from utils.mongo_utils import reconnect
                        if reconnect():
                            log("MongoDB重连成功", "green")
                        else:
                            log("MongoDB重连失败，切换到文件系统存储", "red")
                            self.enable_db = False
                    else:
                        # 每5分钟打印一次连接状态
                        conn_info = get_connection_info()
                        if conn_info:
                            current_conn = conn_info['connections']['current']
                            max_conn = conn_info['max_pool_size']
                            # MongoDB连接状态日志已删除，减少日志噪音
                
                # 每30秒检查一次
                time.sleep(30)
                
            except Exception as e:
                log(f"连接监控异常: {e}", "red")
                time.sleep(60)  # 出错时等待更长时间

    def get_server_status(self):
        """
        获取服务器状态信息
        """
        status = {
            'server': {
                'host': self.host,
                'port': self.port,
                'buffer_size': self.buffer_size,
                'memory_directory': self.memory_directory,
                'enable_db': self.enable_db
            },
            'database': None,
            'queue': {
                'db_queue_size': self.db_queue.qsize(),
                'db_queue_maxsize': self.db_queue.maxsize
            },
            'sessions': self.session_manager.get_session_stats(),
            'async_processor': async_processor.get_stats(),
            'message_queue': message_queue.get_status()
        }
        
        if self.enable_db:
            status['database'] = get_connection_info()
        
        return status

    def shutdown(self):
        """
        优雅关闭服务器
        """
        log("正在关闭服务器...", "yellow")
        
        # 停止异步处理器
        async_processor.stop()
        log("异步处理器已停止", "green")
        
        # 停止消息队列
        message_queue.stop()
        log("消息队列已停止", "green")
        
        # 关闭会话管理器
        self.session_manager.shutdown()
        log("会话管理器已关闭", "green")
        
        # 关闭MongoDB连接
        if self.enable_db:
            close_connection()
            log("MongoDB连接已关闭", "green")
        
        # 等待队列处理完成
        while not self.db_queue.empty():
            time.sleep(0.1)
        
        log("服务器已关闭", "green")
