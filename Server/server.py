import json
import os
import sys
import socket
import threading
import queue
import time
from typing import Optional

# 添加项目根目录到系统路径（必须在其他import之前）
project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if project_root not in sys.path:
    sys.path.append(project_root)

from log_config import log, setup_logging, log_system_status
from screenParser.Encoder import xmlEncoder
from mobilegpt import MobileGPT
from agents.task_agent import TaskAgent
from datetime import datetime
from utils.mongo_utils import check_connection, get_connection_info, close_connection, get_db
from env_config import Config
from session_manager import SessionManager, ClientSession
from async_processor import async_processor, message_queue
from Reflector_Agent.base import AgentMemory,AgentMemoryVL
from Reflector_Agent.reflector import Reflector
from Reflector_Agent.reflector_vl import ReflectorVL
from utils.mongo_utils import reconnect
import traceback
from screenParser import parseXML
import xml.etree.ElementTree as ET


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
        def dummy_message_processor(message: dict):
            """空的消息处理器，用于消息队列"""
            pass

        message_queue.start(dummy_message_processor)

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
        log(f"Server is listening on {real_ip}:{self.port}\nInput this IP address into the app. : [{real_ip}]", "green")

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
            try:
                # 将session_id传入MobileGPT，便于初始化后迁移会话预缓冲
                setattr(mobileGPT, 'session_id', session.session_id)
            except Exception:
                pass
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
                instruction_line = client_file.readline()
                if not instruction_line:
                    return None
                instruction = instruction_line.decode().strip()
                return {
                    'messageType': 'instruction',
                    'instruction': instruction
                }
            elif message_type == 'X':
                # XML消息
                length_line = client_file.readline()
                if not length_line:
                    return None
                message_length = int(length_line.decode().strip())
                xml_data = client_file.read(message_length)
                if len(xml_data) != message_length:
                    return None
                xml_content = xml_data.decode('utf-8')
                return {
                    'messageType': 'xml',
                    'xml': xml_content
                }
            elif message_type == 'S':
                # 截图消息
                length_line = client_file.readline()
                if not length_line:
                    return None
                message_length = int(length_line.decode().strip())
                screenshot_data = client_file.read(message_length)
                if len(screenshot_data) != message_length:
                    return None
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

                # 解析错误消息以提取截图数据
                error_info = self._parse_error_message(error_content)
                screenshot_data = error_info.get('screenshot', None)

                return {
                    'messageType': 'error',
                    'error': error_content,
                    'screenshot': screenshot_data
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
            
            # 使用已有的MobileGPT实例处理业务逻辑
            mobileGPT = session.mobilegpt
            if mobileGPT is None:
                mobileGPT = MobileGPT(session.client_socket)
                session.mobilegpt = mobileGPT
            
            # 初始化MobileGPT
            mobileGPT.init(instruction, task, is_new_task)
            
            log("MobileGPT初始化完成", "green")
            
            # 这里应该继续处理后续逻辑，比如等待XML和截图数据
            # 然后调用mobileGPT的相应方法
            
        except Exception as e:
            log(f"指令处理失败: {e}", "red")
            
            traceback.print_exc()

    def _handle_xml_message(self, session: ClientSession, message: dict):
        """处理XML消息 - 异步版本"""
        xml_content = message.get('xml', '')
        xml_length = len(xml_content) if xml_content else 0
        log(f"收到XML数据: 长度={xml_length}字符", "cyan")
        
        # 使用优化版本处理XML
        self._process_xml_optimized(session, xml_content)

    def _process_xml_async(self, session: ClientSession, xml_content: str):
        """异步处理XML，保持功能稳定性"""
        try:
            # 快速检查MobileGPT是否准备就绪
            if not self._is_mobilegpt_ready(session):
                log("MobileGPT实例未准备就绪，等待指令处理完成", "yellow")
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
    
    def _process_xml_optimized(self, session: ClientSession, xml_content: str):
        """使用优化版本处理XML"""
        try:
            if not self._is_mobilegpt_ready(session):
                log("MobileGPT实例未准备就绪，等待指令处理完成", "yellow")
                self._wait_for_mobilegpt(session, xml_content, max_wait=10)
                return
            
            log("开始优化版本XML处理", "green")
            
            # 解析XML数据
            screen_parser = xmlEncoder()
            parsed_xml = parseXML.parse(xml_content)
            hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
            tree = ET.fromstring(parsed_xml)
            for element in tree.iter():
                for k in ("bounds", "important", "class"):
                    if k in element.attrib:
                        del element.attrib[k]
            encoded_xml = ET.tostring(tree, encoding='unicode')
            
            # 确保XML也进入缓冲，与截图配对
            try:
                mobilegpt = session.mobilegpt
                if mobilegpt is not None:
                    # 不再依赖(_screen_count - 1)对齐方式；直接使用当前计数作为参考索引
                    assigned_index = getattr(mobilegpt, '_screen_count', 0)
                    buf = getattr(mobilegpt, '_local_buffer', None)
                    if buf is None:
                        buf = {'xmls': [], 'shots': []}
                        setattr(mobilegpt, '_local_buffer', buf)
                    # 不再按 index 去重：始终入队，并立刻标注 page_index
                    buf['xmls'].append({'index': assigned_index, 'xml': xml_content, 'page_index': getattr(mobilegpt, 'current_page_index', -1)})
                    log(f"[buffer] xml queued (optimized) idx={assigned_index}, raw_len={len(xml_content)}, xmls={len(buf['xmls'])}", "blue")
                else:
                    # MobileGPT 未就绪：入会话预缓冲
                    assigned_index = session.screen_count
                    if session.prebuffer is None:
                        session.prebuffer = {'xmls': [], 'shots': []}
                    session.prebuffer['xmls'].append({'index': assigned_index, 'xml': xml_content, 'page_index': -1})
                    log(f"[buffer] xml queued (session) idx={assigned_index}, raw_len={len(xml_content)}, xmls={len(session.prebuffer['xmls'])}", "blue")
            except Exception as e:
                log(f"XML缓冲失败: {e}", "red")
            
            # 使用优化版本的MobileGPT
            mobilegpt = session.mobilegpt
            use_optimization = os.getenv("MOBILEGPT_OPTIMIZATION", "true").lower() == "true"
            
            if use_optimization and hasattr(mobilegpt, 'get_next_action_optimized'):
                # 使用异步优化版本
                import asyncio
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                try:
                    action = loop.run_until_complete(
                        mobilegpt.get_next_action_optimized(parsed_xml, hierarchy_xml, encoded_xml)
                    )
                finally:
                    loop.close()
            else:
                # 回退到同步版本
                action = mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
            
            if action:
                log(f"MobileGPT返回动作: {action}", "green")
                self._send_action_to_client(session, action)
            else:
                log("MobileGPT未返回动作", "yellow")

            # 动作决策完成后，若此前XML写入了MobileGPT缓冲，则根据最新页面回写该条XML的page_index
            try:
                mobilegpt = session.mobilegpt
                page_idx_after = getattr(mobilegpt, 'current_page_index', -1)
                assigned_index = getattr(mobilegpt, '_screen_count', 0)
                buf = getattr(mobilegpt, '_local_buffer', None)
                if buf is not None:
                    for it in reversed(buf.get('xmls', [])):
                        if it.get('index') == assigned_index:
                            it['page_index'] = page_idx_after
                            break
            except Exception:
                pass
                
        except Exception as e:
            log(f"优化版本XML处理失败: {e}", "red")
            traceback.print_exc()
            # 回退到原始处理方式
            self._process_xml_directly(session, xml_content)

    def _wait_for_mobilegpt(self, session: ClientSession, xml_content: str, max_wait: int = 15):
        """等待MobileGPT实例准备就绪"""
        
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
                log("等待中: MobileGPT实例memory属性为None", "blue")
            else:
                log("MobileGPT实例已准备就绪，开始处理XML", "green")
                # 直接处理XML，避免递归调用
                self._process_xml_content_directly(session, xml_content)
                return
            
            time.sleep(0.5)  # 等待500ms
        
        log(f"等待MobileGPT实例超时({max_wait}秒)，回退到同步处理", "yellow")
        self._process_xml_directly(session, xml_content)

    def _is_mobilegpt_ready(self, session: ClientSession) -> bool:
        """快速检查MobileGPT是否准备就绪"""
        return (hasattr(session, 'mobilegpt') and 
                session.mobilegpt is not None and
                hasattr(session.mobilegpt, 'memory') and 
                session.mobilegpt.memory is not None)

    def _process_xml_content_directly(self, session: ClientSession, xml_content: str):
        """直接处理XML内容，避免递归调用"""
        try:
            log("开始处理XML内容", "green")
            
            # 解析XML数据
            screen_parser = xmlEncoder()
            parsed_xml = parseXML.parse(xml_content)
            hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
            tree = ET.fromstring(parsed_xml)
            for element in tree.iter():
                for k in ("bounds", "important", "class"):
                    if k in element.attrib:
                        del element.attrib[k]
            encoded_xml = ET.tostring(tree, encoding='unicode')
            # 将原始XML缓存在 mobilegpt 上，与最近一次截图对齐（_screen_count - 1）
            try:
                mobilegpt = session.mobilegpt
                if mobilegpt is not None:
                    # 不再依赖(_screen_count - 1)对齐方式；直接使用当前计数作为参考索引
                    assigned_index = getattr(mobilegpt, '_screen_count', 0)
                    buf = getattr(mobilegpt, '_local_buffer', None)
                    if buf is None:
                        buf = {'xmls': [], 'shots': []}
                        setattr(mobilegpt, '_local_buffer', buf)
                    if not any(it.get('index') == assigned_index for it in buf['xmls']):
                        buf['xmls'].append({'index': assigned_index, 'xml': xml_content})
                        # 入队即标记 page_index
                        try:
                            buf['xmls'][-1]['page_index'] = getattr(mobilegpt, 'current_page_index', -1)
                        except Exception:
                            pass
                        log(f"[buffer] xml queued (direct) idx={assigned_index}, raw_len={len(xml_content)}, xmls={len(buf['xmls'])}", "blue")
                        if any(it.get('index') == assigned_index for it in buf['shots']):
                            page_idx = getattr(mobilegpt, 'current_page_index', -1)
                            for it in buf['xmls']:
                                if it.get('index') == assigned_index:
                                    it['page_index'] = page_idx
                                    break
                            for it in buf['shots']:
                                if it.get('index') == assigned_index:
                                    it['page_index'] = page_idx
                                    break
                    else:
                        log(f"[buffer] xml skipped (duplicate index) idx={assigned_index}", "yellow")
            except Exception:
                pass
            

            # 调用MobileGPT的get_next_action方法
            action = session.mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
            
            if action:
                log(f"MobileGPT返回动作: {action}", "green")
                # 发送动作给客户端
                self._send_action_to_client(session, action)
            else:
                log("MobileGPT未返回动作", "yellow")
                
        except Exception as e:
            log(f"处理XML内容失败: {e}", "red")
            traceback.print_exc()

    def _process_xml_directly(self, session: ClientSession, xml_content: str):
        """直接处理XML，确保功能稳定性"""
        try:
            if not self._is_mobilegpt_ready(session):
                log("MobileGPT未准备就绪，跳过XML处理", "yellow")
                return
                
            log("使用MobileGPT直接处理XML", "green")
            
            # 解析XML数据
            screen_parser = xmlEncoder()
            parsed_xml = parseXML.parse(xml_content)
            hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
            tree = ET.fromstring(parsed_xml)
            for element in tree.iter():
                for k in ("bounds", "important", "class"):
                    if k in element.attrib:
                        del element.attrib[k]
            encoded_xml = ET.tostring(tree, encoding='unicode')
            
            

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
            else:
                log("MobileGPT实例不存在，跳过截图处理", "yellow")
        except Exception as e:
            log(f"MobileGPT处理截图失败: {e}", "red")

    def _handle_qa_message(self, session: ClientSession, message: dict):
        """处理问答消息"""
        qa_content = message.get('qa', '')
        log(f"收到问答消息: {qa_content}", "blue")

        # 解析格式：info_name\question\answer
        try:
            info_name, question, answer = qa_content.split("\\", 2)
        except Exception:
            log("问答消息格式无效，期望格式为 info_name\\question\\answer", "red")
            return

        # 检查 MobileGPT 实例
        mobilegpt = getattr(session, 'mobilegpt', None)
        if not mobilegpt:
            log("MobileGPT实例不存在，无法处理问答", "red")
            return

        # 写入答案并尝试继续生成动作
        try:
            action = mobilegpt.set_qa_answer(info_name, question, answer)
            if action:
                log(f"问答生效，发送后续动作: {action}", "green")
                self._send_action_to_client(session, action)
            else:
                log("问答已记录，但未返回动作", "yellow")
        except Exception as e:
            log(f"处理问答时发生异常: {e}", "red")

    def _handle_error_message(self, session: ClientSession, message: dict):
        """处理错误消息"""
        error_content = message.get('error', '')
        # log(f"收到错误消息: {message}", "red")
        screenshot_data = message.get('screenshot', None)

        # 获取必要的变量
        client_socket = session.client_socket
        mobileGPT = getattr(session, 'mobilegpt', None)
        if not client_socket:
            log("客户端socket不存在，无法处理错误消息", "red")
            return
            
        if not mobileGPT:
            log("MobileGPT实例不存在，无法处理错误消息", "red")
            return

        try:
            # 解析错误信息
            error_info = self._parse_error_message(error_content)
            screen_parser = xmlEncoder()
            parsed_xml, hierarchy_xml, encoded_xml = screen_parser.encode(error_info['cur_xml'], 0)
            parsed_xml_pre, hierarchy_xml_pre, encoded_xml_pre = screen_parser.encode(error_info['pre_xml'], 0)

            log(f"错误信息: {error_info.get('error_message', 'No message')}", "red")

            # 获取前一个界面的子任务列表和执行的子任务
            try:
                log("尝试搜索匹配的历史页面", "blue")
                page_index, new_subtasks = mobileGPT.memory.search_node(parsed_xml_pre, hierarchy_xml_pre,
                                                                        encoded_xml_pre)
                log(f"search_node返回结果: page_index={page_index}", "blue")

                # if page_index == -1:
                #     log("未找到匹配页面，尝试探索新界面", "blue")
                #     page_index = mobileGPT.explore_agent.explore(parsed_xml_pre, hierarchy_xml_pre, encoded_xml_pre)
                #     log(f"explore返回结果: page_index={page_index}", "blue")

                log(f"获取可用子任务，page_index={page_index}", "blue")
                available_subtasks = mobileGPT.memory.get_available_subtasks(page_index)
                log(f"获取到的可用子任务: {available_subtasks}", "blue")

                current_subtask = mobileGPT.current_subtask
                log(f"当前子任务: {current_subtask}", "blue")
            except Exception as e:
                log(f"获取子任务列表时发生异常: {str(e)}", "red")
                # 设置默认值，避免后续代码出错
                page_index = -1
                available_subtasks = []
                current_subtask = None

            """
            下面代码为使用出错前后的界面元素进行反思的代码
            """
            # # 初始化AgentMemory
            # self.agent_memory = AgentMemory(
            #     instruction=error_info.get('instruction', 'None'),
            #     errTYPE=error_info.get('error_type', 'UNKNOWN'),
            #     errMessage=error_info.get('error_message', 'No message'),
            #     curXML=encoded_xml,
            #     preXML=encoded_xml_pre,
            #     action=error_info.get('action', 'None'),
            #     current_subtask=current_subtask,
            #     available_subtasks=available_subtasks
            # )
            #
            # log(self.agent_memory.instruction, "blue")
            # log(self.agent_memory.action, "blue")
            # log(f"当前子任务: {self.agent_memory.current_subtask}", "blue")
            # log(f"可用子任务: {self.agent_memory.available_subtasks}", "blue")
            #
            # # 调用Reflector进行反思分析
            # reflector = Reflector(self.agent_memory)
            # reflection = reflector.reflect_on_episodic_memory(self.agent_memory)

            """
            下面代码为使用出错时的界面截图进行反思的代码
            """
            # 初始化AgentMemoryVL
            self.agent_memory_vl = AgentMemoryVL(
                instruction=error_info.get('instruction', 'None'),
                errTYPE=error_info.get('error_type', 'UNKNOWN'),
                errMessage=error_info.get('error_message', 'No message'),
                curScreenshot=screenshot_data,
                action=error_info.get('action', 'None'),
                current_subtask=current_subtask,
                available_subtasks=available_subtasks
            )
            # 调用ReflectorVL进行反思分析
            reflector_vl = ReflectorVL(self.agent_memory_vl)
            reflection = reflector_vl.reflect_on_episodic_memory(self.agent_memory_vl)

            # 根据反思结果决定下一步操作
            if reflection.need_back:
                # 需要回退，直接发送回退指令
                self._send_back_action(client_socket)
            else:
                # 不需要回退，根据问题类型处理
                advice = reflection.advice
                # 构建建议的结构
                suggestion = {
                    "出错的动作": error_info.get('action', 'None'),
                    "建议": advice
                }
                log(f"建议: {suggestion}", "blue")
                if reflection.problem_type == 'task':
                    # 获取MobileGPT实例并调用方法
                    mobilegpt = getattr(session, 'mobilegpt', None)
                    if mobilegpt is None:
                        log("MobileGPT实例不存在，无法处理错误", "red")
                        self._send_finish_action(client_socket, "MobileGPT实例不存在")
                        return
                    action = mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml, subtask_failed=True,
                                                       action_failed=False, suggestions=suggestion)

                else:
                    # 获取MobileGPT实例并调用方法
                    mobilegpt = getattr(session, 'mobilegpt', None)
                    if mobilegpt is None:
                        log("MobileGPT实例不存在，无法处理错误", "red")
                        self._send_finish_action(client_socket, "MobileGPT实例不存在")
                        return
                    action = mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml, subtask_failed=False,
                                                       action_failed=True, suggestions=suggestion)

                if action:
                    log(f"MobileGPT返回动作: {action}", "green")
                    # 发送动作给客户端
                    self._send_action_to_client(session, action)
                else:
                    log("MobileGPT未返回动作", "yellow")

        except Exception as e:
            log(f"处理错误消息时发生异常: {e}", "red")
            # 发送默认的finish动作作为兜底
            self._send_finish_action(client_socket, "处理错误消息时发生异常")

    def _send_back_action(self, client_socket):
        """发送回退动作"""
        back_action = {"name": "back", "parameters": {}}
        message = json.dumps(back_action)
        try:
            client_socket.send(message.encode())
            client_socket.send("\r\n".encode())
            log("Back action sent to client", "blue")
        except Exception as e:
            log(f"发送回退动作失败: {e}", "red")

    def _send_finish_action(self, client_socket, reason=""):
        """发送完成动作"""
        finish_action = {"name": "finish", "parameters": {}}
        try:
            client_socket.send(json.dumps(finish_action).encode())
            client_socket.send("\r\n".encode())
            log(f"Finish action sent: {reason}", "yellow")
        except Exception as e:
            log(f"发送完成动作失败: {e}", "red")

    def _handle_area_error(self, session: ClientSession, error_info: dict, advice: str, screen_count: int):
        """处理区域选择错误"""
        client_socket = session.client_socket
        mobileGPT = session.mobilegpt

        log(f"处理区域选择错误，建议: {advice}", "blue")

        # 获取当前XML数据（从错误信息中提取）
        current_xml = error_info.get('cur_xml', '')
        if not current_xml:
            log("没有当前XML数据，无法处理区域错误", "red")
            self._send_finish_action(client_socket, "缺少XML数据")
            return

        try:
            parsed_xml = parseXML.parse(current_xml)
            hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
            tree = ET.fromstring(parsed_xml)
            for element in tree.iter():
                for k in ("bounds", "important", "class"):
                    if k in element.attrib:
                        del element.attrib[k]
            encoded_xml = ET.tostring(tree, encoding='unicode')
            # 将原始XML缓存在 mobilegpt 上，与最近一次截图对齐（_screen_count - 1）
            try:
                mobilegpt = session.mobilegpt
                if mobilegpt is not None:
                    # 不再依赖(_screen_count - 1)对齐方式；直接使用当前计数作为参考索引
                    assigned_index = getattr(mobilegpt, '_screen_count', 0)
                    buf = getattr(mobilegpt, '_local_buffer', None)
                    if buf is None:
                        buf = {'xmls': [], 'shots': []}
                        setattr(mobilegpt, '_local_buffer', buf)
                    if not any(it.get('index') == assigned_index for it in buf['xmls']):
                        buf['xmls'].append({'index': assigned_index, 'xml': current_xml})
                        # 入队即标记 page_index
                        try:
                            buf['xmls'][-1]['page_index'] = getattr(mobilegpt, 'current_page_index', -1)
                        except Exception:
                            pass
                        log(f"[buffer] xml queued (direct2) idx={assigned_index}, raw_len={len(current_xml)}, xmls={len(buf['xmls'])}",
                            "blue")
                        if any(it.get('index') == assigned_index for it in buf['shots']):
                            page_idx = getattr(mobilegpt, 'current_page_index', -1)
                            for it in buf['xmls']:
                                if it.get('index') == assigned_index:
                                    it['page_index'] = page_idx
                                    break
                            for it in buf['shots']:
                                if it.get('index') == assigned_index:
                                    it['page_index'] = page_idx
                                    break
                    else:
                        log(f"[buffer] xml skipped (duplicate index) idx={assigned_index}", "yellow")
            except Exception:
                pass

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
                [advice] if advice else []
            )

            # 若生成了新动作，添加到内存（供后续复用）
            if new_action:
                mobileGPT.memory.add_new_action(new_action, page_index)

            # 提取选择的子任务
            next_subtask = response['action']

            # 处理speak动作
            if next_subtask['name'] != 'read_screen':
                msg = response['speak']
                speak_action = {"name": "speak", "parameters": {"message": msg}}
                try:
                    client_socket.send(json.dumps(speak_action).encode())
                    client_socket.send("\r\n".encode())
                    log(f"Speak action sent: {msg}", "blue")
                except Exception as e:
                    log(f"发送speak动作失败: {e}", "red")
                    return

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

            # 处理基础子任务（finish, speak）
            # scroll_screen 已注释掉
            if next_subtask['name'] in ['finish', 'speak']:  # 移除 'scroll_screen'
                primitive_action = mobileGPT._MobileGPT__handle_primitive_subtask(next_subtask)
                if primitive_action:
                    try:
                        client_socket.send(json.dumps(primitive_action).encode())
                        client_socket.send("\r\n".encode())
                        log(f"Primitive action sent: {primitive_action['name']}", "blue")
                    except Exception as e:
                        log(f"发送基础动作失败: {e}", "red")
            else:
                # 对于复杂子任务，调用derive_agent生成具体动作
                try:
                    next_action, example = mobileGPT.derive_agent.derive(encoded_xml,
                                                                         suggestions=[advice] if advice else [])

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
                        except Exception as e:
                            log(f"发送纠正动作失败: {e}", "red")
                    else:
                        self._send_finish_action(client_socket, "derive_agent返回空动作")

                except Exception as derive_error:
                    log(f"derive_agent处理失败: {derive_error}", "red")
                    self._send_finish_action(client_socket, "derive_agent处理失败")

        except Exception as e:
            log(f"处理区域错误时发生异常: {e}", "red")
            self._send_finish_action(client_socket, "处理区域错误时发生异常")

    def _handle_instruction_error(self, session: ClientSession, error_info: dict, advice: str, screen_count: int):
        """处理指令错误"""
        client_socket = session.client_socket
        mobileGPT = session.mobilegpt

        log("处理指令错误 - 使用derive_agent重新生成动作", "yellow")

        # 获取当前XML数据
        current_xml = error_info.get('cur_xml', '')
        if not current_xml:
            log("缺少XML数据，无法重新生成动作", "red")
            self._send_finish_action(client_socket, "缺少XML数据")
            return

        if not mobileGPT.current_subtask:
            log("缺少当前子任务，无法重新生成动作", "red")
            self._send_finish_action(client_socket, "缺少当前子任务")
            return

        try:
            parsed_xml = parseXML.parse(current_xml)
            hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
            tree = ET.fromstring(parsed_xml)
            for element in tree.iter():
                for k in ("bounds", "important", "class"):
                    if k in element.attrib:
                        del element.attrib[k]
            encoded_xml = ET.tostring(tree, encoding='unicode')
            page_index, _ = mobileGPT.memory.search_node(parsed_xml, hierarchy_xml, encoded_xml)

            # 使用derive_agent重新生成动作，传入反思建议
            suggestions = [advice] if advice else []
            next_action, example = mobileGPT.derive_agent.derive(encoded_xml, suggestions=suggestions)

            # 记录重新生成的动作数据
            current_action_data = {
                "page_index": page_index,
                "action": next_action,
                "screen": encoded_xml,
                "example": example,
                "regenerated": True
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
                except Exception as send_error:
                    log(f"发送重新生成的动作失败: {send_error}", "red")
                    self._send_finish_action(client_socket, "发送动作失败")
            else:
                log("derive_agent返回空动作，发送finish动作", "yellow")
                self._send_finish_action(client_socket, "derive_agent返回空动作")

        except Exception as derive_error:
            log(f"指令错误恢复过程中发生异常: {derive_error}", "red")
            self._send_finish_action(client_socket, "指令错误恢复失败")

        # 可以在这里添加错误处理逻辑
        # 目前只是记录日志

    def _handle_get_actions_message(self, session: ClientSession, message: dict):
        """处理获取操作消息"""
        log("收到获取操作请求", "blue")

        # 可以在这里添加获取操作列表的逻辑
        # 目前只是记录日志

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

    def _save_xml_to_mongo(self, xml_data, screen_count, xml_type):
        """将XML数据保存到MongoDB（无 app 维度）"""
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

        i = 0
        screenshot_found = False
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith('ERROR_TYPE:'):
                error_info['error_type'] = line[11:].strip()
            elif line.startswith('ERROR_MESSAGE:'):
                error_info['error_message'] = line[14:].strip()
            elif line.startswith('ACTION:'):
                error_info['action'] = line[7:].strip()
            elif line.startswith('INSTRUCTION:'):
                error_info['instruction'] = line[12:].strip()
            elif line.startswith('REMARK:'):
                error_info['remark'] = line[7:].strip()
            elif line.startswith('SCREENSHOT_DATA:'):
                # 处理截图信息（Base64编码）
                screenshot_found = True
                screenshot_lines = []
                # 收集第一行的数据
                first_line_data = line[15:].strip()
                if first_line_data:
                    screenshot_lines.append(first_line_data)

                # 继续收集后续行的截图数据，直到遇到下一个标记
                i += 1
                while i < len(lines):
                    screenshot_line = lines[i]
                    # 检查是否遇到下一个标记
                    if screenshot_line.strip() in ['PRE_XML:', 'CUR_XML:', 'ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:'] or \
                       screenshot_line.strip().startswith(('ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:')):
                        i -= 1  # 回退一行，让外层循环处理
                        break
                    screenshot_lines.append(screenshot_line.strip())
                    i += 1

                # 合并所有截图数据
                screenshot_data = ''.join(screenshot_lines)
                if screenshot_data:
                    try:
                        import base64
                        # 解码Base64数据为字节
                        error_info['screenshot'] = base64.b64decode(screenshot_data)
                    except Exception as e:
                        log(f"截图数据解码失败: {e}", "red")
                        error_info['screenshot'] = None
                else:
                    error_info['screenshot'] = None
            elif line == 'PRE_XML:':
                # 找到PRE_XML标记，收集后续XML内容直到下一个标记
                xml_lines = []
                i += 1
                while i < len(lines):
                    xml_line = lines[i]
                    if xml_line.strip() in ['CUR_XML:', 'ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:', 'SCREENSHOT_DATA:'] or \
                       xml_line.strip().startswith(('ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:', 'SCREENSHOT_DATA:')):
                        i -= 1  # 回退一行，让外层循环处理
                        break
                    xml_lines.append(xml_line)
                    i += 1
                if xml_lines:
                    error_info['pre_xml'] = '\n'.join(xml_lines).strip()
            elif line == 'CUR_XML:':
                # 找到CUR_XML标记，收集后续XML内容直到下一个标记
                xml_lines = []
                i += 1
                while i < len(lines):
                    xml_line = lines[i]
                    if xml_line.strip() in ['PRE_XML:', 'ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:', 'SCREENSHOT_DATA:'] or \
                       xml_line.strip().startswith(('ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:', 'SCREENSHOT_DATA:')):
                        i -= 1  # 回退一行，让外层循环处理
                        break
                    xml_lines.append(xml_line)
                    i += 1
                if xml_lines:
                    error_info['cur_xml'] = '\n'.join(xml_lines).strip()
            i += 1

        return error_info

    def _enqueue_db_doc(self, doc: dict):
        try:
            self.db_queue.put(doc, timeout=0.5)
        except queue.Full:
            log("DB queue full, dropping doc", "yellow")

    def _db_worker(self):
        """后台DB写入线程：批量、合并策略"""
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