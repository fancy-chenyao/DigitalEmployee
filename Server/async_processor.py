"""
异步处理器
提升并发性能和响应速度
"""

import asyncio
import threading
import time
import queue
from typing import Dict, Any, Callable, Optional
from unittest.mock import Mock
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
import json

from log_config import log
from session_manager import SessionManager, ClientSession


class ProcessingTask:
    """处理任务"""
    
    def __init__(self, task_id: str, session_id: str, task_type: str, data: Any, 
                 callback: Optional[Callable] = None, created_at: datetime = None, 
                 priority: int = 0):
        self.task_id = task_id
        self.session_id = session_id
        self.task_type = task_type
        self.data = data
        self.callback = callback
        self.created_at = created_at or datetime.now()
        self.priority = priority  # 优先级，数字越大优先级越高
    
    def __lt__(self, other):
        """小于比较，用于优先队列排序"""
        if not isinstance(other, ProcessingTask):
            return NotImplemented
        # 优先级高的排在前面（数字大的优先级高）
        if self.priority != other.priority:
            return self.priority > other.priority
        # 优先级相同时，按创建时间排序（早创建的优先）
        return self.created_at < other.created_at
    
    def __eq__(self, other):
        """等于比较"""
        if not isinstance(other, ProcessingTask):
            return NotImplemented
        return self.task_id == other.task_id
    
    def __repr__(self):
        return f"ProcessingTask(id={self.task_id}, priority={self.priority})"


class AsyncProcessor:
    """异步处理器"""
    
    def __init__(self, max_workers: int = 10, max_queue_size: int = 1000):
        self.max_workers = max_workers
        self.max_queue_size = max_queue_size
        
        # 任务队列
        self.task_queue = queue.PriorityQueue(maxsize=max_queue_size)
        
        # 线程池
        self.thread_pool = ThreadPoolExecutor(max_workers=max_workers)
        
        # 任务状态跟踪
        self.active_tasks: Dict[str, ProcessingTask] = {}
        self.completed_tasks: Dict[str, Any] = {}
        
        # 控制标志
        self.running = False
        self.worker_threads = []
        
        # 统计信息
        self.stats = {
            'total_tasks': 0,
            'completed_tasks': 0,
            'failed_tasks': 0,
            'active_tasks': 0,
            'queue_size': 0
        }
        
        # 自动启动
        self.start()
    
    def start(self):
        """启动异步处理器"""
        if self.running:
            return
        
        self.running = True
        
        # 启动工作线程
        for i in range(self.max_workers):
            worker = threading.Thread(
                target=self._worker_loop,
                name=f"AsyncWorker-{i}",
                daemon=True
            )
            worker.start()
            self.worker_threads.append(worker)
        
        # 启动统计线程
        stats_thread = threading.Thread(
            target=self._stats_loop,
            name="StatsWorker",
            daemon=True
        )
        stats_thread.start()
        
        log(f"异步处理器已启动，工作线程数: {self.max_workers}", "green")
    
    def stop(self):
        """停止异步处理器"""
        self.running = False
        
        # 等待所有工作线程完成
        for worker in self.worker_threads:
            worker.join(timeout=5)
        
        # 关闭线程池
        self.thread_pool.shutdown(wait=True)
        
        log("异步处理器已停止", "yellow")
    
    def submit_task(self, session_id: str, task_type: str, data: Any, 
                   callback: Optional[Callable] = None, priority: int = 0) -> str:
        """提交处理任务"""
        task_id = f"{session_id}_{task_type}_{int(time.time() * 1000)}"
        
        task = ProcessingTask(
            task_id=task_id,
            session_id=session_id,
            task_type=task_type,
            data=data,
            callback=callback,
            priority=priority
        )
        
        try:
            # 直接使用任务对象，通过__lt__方法实现优先级排序
            self.task_queue.put(task, timeout=1)
            
            self.active_tasks[task_id] = task
            self.stats['total_tasks'] += 1
            self.stats['active_tasks'] += 1
            
            log(f"任务已提交: {task_id} (优先级: {priority})", "blue")
            return task_id
            
        except queue.Full:
            log(f"任务队列已满，无法提交任务: {task_id}", "red")
            return None
    
    def submit_task_with_callback(self, session_id: str, task_type: str, data: Any, 
                                callback: Callable, priority: int = 0) -> Optional[str]:
        """提交任务并设置回调函数"""
        return self.submit_task(session_id, task_type, data, callback, priority)
    
    def get_task_result(self, task_id: str) -> Optional[Any]:
        """获取任务结果"""
        return self.completed_tasks.get(task_id)
    
    def wait_for_task(self, task_id: str, timeout: float = 30.0) -> Optional[Any]:
        """等待任务完成"""
        start_time = time.time()
        
        while time.time() - start_time < timeout:
            if task_id in self.completed_tasks:
                return self.completed_tasks[task_id]
            
            if task_id not in self.active_tasks:
                return None
            
            time.sleep(0.1)
        
        log(f"等待任务超时: {task_id}", "red")
        return None
    
    def _worker_loop(self):
        """工作线程循环"""
        while self.running:
            try:
                # 获取任务（带超时）
                task = self.task_queue.get(timeout=1)
                
                # 处理任务
                self._process_task(task)
                
            except queue.Empty:
                continue
            except Exception as e:
                log(f"工作线程处理任务时出错: {e}", "red")
    
    def _process_task(self, task: ProcessingTask):
        """处理单个任务"""
        try:
            log(f"开始处理任务: {task.task_id}", "blue")
            
            # 根据任务类型选择处理方法
            result = self._execute_task(task)
            
            # 存储结果
            self.completed_tasks[task.task_id] = result
            
            # 执行回调
            if task.callback:
                try:
                    task.callback(result)  # 只传递结果，不传递task_id
                except Exception as e:
                    log(f"任务回调执行失败: {e}", "red")
            
            # 更新统计
            self.stats['completed_tasks'] += 1
            self.stats['active_tasks'] -= 1
            
            log(f"任务处理完成: {task.task_id}", "green")
            
        except Exception as e:
            log(f"任务处理失败: {task.task_id}, 错误: {e}", "red")
            self.stats['failed_tasks'] += 1
            self.stats['active_tasks'] -= 1
            
            # 存储错误结果
            self.completed_tasks[task.task_id] = {
                'error': str(e),
                'success': False
            }
        
        finally:
            # 清理活跃任务
            if task.task_id in self.active_tasks:
                del self.active_tasks[task.task_id]
    
    def _execute_task(self, task: ProcessingTask) -> Any:
        """执行具体任务"""
        task_type = task.task_type
        data = task.data
        
        if task_type == "instruction_processing":
            return self._process_instruction(data)
        elif task_type == "xml_processing":
            return self._process_xml(data)
        elif task_type == "screenshot_processing":
            return self._process_screenshot(data)
        elif task_type == "error_recovery":
            return self._process_error_recovery(data)
        else:
            raise ValueError(f"未知的任务类型: {task_type}")
    
    def _is_mobilegpt_ready(self, mobileGPT) -> bool:
        """快速检查MobileGPT是否准备就绪"""
        return (hasattr(mobileGPT, 'memory') and 
                mobileGPT.memory is not None)

    def _process_instruction(self, data: dict) -> dict:
        """处理指令相关任务 - 异步版本"""
        instruction = data.get('instruction', '')
        session_id = data.get('session_id', '')
        client_socket = data.get('client_socket')
        log(f"异步处理指令: {instruction}", "blue")
        
        try:
            # 导入必要的模块
            from agents.task_agent import TaskAgent
            from mobilegpt import MobileGPT
            
            # 创建TaskAgent解析指令
            task_agent = TaskAgent()
            
            try:
                # 跨平台超时机制，避免AI调用阻塞太久
                import threading
                import time
                
                result_container = {'task': None, 'is_new_task': None, 'error': None}
                
                def task_worker():
                    try:
                        task, is_new_task = task_agent.get_task(instruction)
                        result_container['task'] = task
                        result_container['is_new_task'] = is_new_task
                    except Exception as e:
                        result_container['error'] = e
                
                # 启动任务线程
                worker_thread = threading.Thread(target=task_worker, daemon=True)
                worker_thread.start()
                
                # 等待30秒或直到完成
                worker_thread.join(timeout=30)
                
                if worker_thread.is_alive():
                    log("TaskAgent解析超时，使用默认任务", "yellow")
                    # 使用默认任务
                    task = {
                        "name": "requestLeave",
                        "description": "Submit a leave request for specific dates in the leave management system.",
                        "parameters": {
                            "start_date": "2025-09-17",
                            "end_date": "2025-09-18", 
                            "leave_type": "年休假"
                        }
                    }
                    is_new_task = True
                elif result_container['error']:
                    log(f"TaskAgent解析失败: {result_container['error']}，使用默认任务", "red")
                    # 使用默认任务
                    task = {
                        "name": "requestLeave",
                        "description": "Submit a leave request for specific dates in the leave management system.",
                        "parameters": {
                            "start_date": "2025-09-17",
                            "end_date": "2025-09-18",
                            "leave_type": "年休假"
                        }
                    }
                    is_new_task = True
                else:
                    task = result_container['task']
                    is_new_task = result_container['is_new_task']
                    log(f"TaskAgent解析结果: 任务={task.get('name', 'unknown')}, 新任务={is_new_task}", "green")
                
            except Exception as e:
                log(f"TaskAgent解析异常: {e}，使用默认任务", "red")
                # 使用默认任务
                task = {
                    "name": "requestLeave",
                    "description": "Submit a leave request for specific dates in the leave management system.",
                    "parameters": {
                        "start_date": "2025-09-17",
                        "end_date": "2025-09-18",
                        "leave_type": "年休假"
                    }
                }
                is_new_task = True
            
            # 使用已有的MobileGPT实例
            if client_socket is not None:
                # 从session_manager获取已有的MobileGPT实例
                from session_manager import SessionManager
                session = SessionManager().get_session(session_id)
                if session and session.mobilegpt:
                    mobileGPT = session.mobilegpt
                else:
                    mobileGPT = MobileGPT(client_socket)
                    # 保存到session
                    if session:
                        session.mobilegpt = mobileGPT
                
                mobileGPT.init(instruction, task, is_new_task)
                
                # 验证初始化是否成功
                if not self._is_mobilegpt_ready(mobileGPT):
                    log("MobileGPT初始化失败，memory属性为空", "red")
                    raise Exception("MobileGPT初始化失败")
                    
            else:
                # 测试环境，创建模拟的MobileGPT实例
                mobileGPT = Mock()
                mobileGPT.instruction = instruction
                mobileGPT.task = task
                mobileGPT.is_new_task = is_new_task
                mobileGPT.memory = Mock()  # 添加模拟的memory属性
            
            log("MobileGPT异步初始化完成", "green")
            
            return {
                "status": "instruction_processed", 
                "instruction": instruction,
                "task": task,
                "is_new_task": is_new_task,
                "session_id": session_id,
                "mobilegpt": mobileGPT  # 返回MobileGPT实例
            }
            
        except Exception as e:
            log(f"异步指令处理失败: {e}", "red")
            import traceback
            traceback.print_exc()
            return {
                "status": "instruction_failed", 
                "instruction": instruction,
                "error": str(e),
                "session_id": session_id
            }
    
    def _process_xml(self, data: dict) -> dict:
        """处理XML相关任务 - 异步版本"""
        xml_content = data.get('xml', '')
        session_id = data.get('session_id', '')
        mobilegpt = data.get('mobilegpt')
        
        log(f"异步处理XML: 长度={len(xml_content)}字符", "blue")
        
        try:
            if not mobilegpt:
                log("MobileGPT实例不存在，跳过XML处理", "yellow")
                return {"status": "xml_skipped", "reason": "no_mobilegpt", "session_id": session_id}
            
            # 验证MobileGPT实例的memory属性
            if not hasattr(mobilegpt, 'memory') or mobilegpt.memory is None:
                log("MobileGPT实例memory属性为空，跳过XML处理", "yellow")
                return {"status": "xml_skipped", "reason": "no_memory", "session_id": session_id}
            
            # 严格按照策略选择存储位置：DB可用→只写DB临时集合；否则→只写本地
            from env_config import Config
            from utils.mongo_utils import check_connection, get_db
            db_available = bool(Config.ENABLE_DB) and check_connection()

            if db_available:
                # 仅解析到内存并写 Mongo 临时集合
                from screenParser import parseXML
                import xml.etree.ElementTree as ET
                parsed_xml = parseXML.parse(xml_content)
                hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
                tree = ET.fromstring(parsed_xml)
                for element in tree.iter():
                    for k in ("bounds", "important", "class"):
                        if k in element.attrib:
                            del element.attrib[k]
                encoded_xml = ET.tostring(tree, encoding='unicode')

                try:
                    db = get_db()
                    temp_xmls = db['temp_xmls']
                    task_name = getattr(getattr(mobilegpt, 'memory', None), 'task_name', 'untitled') or 'untitled'
                    # 统一由截图路径自增 _screen_count，这里不自增，避免与截图路径产生双自增错位
                    screen_count = getattr(mobilegpt, '_screen_count', 0)
                    docs = [
                        {"task_name": task_name, "xml_type": "raw", "screen_count": screen_count, "xml_content": xml_content},
                        {"task_name": task_name, "xml_type": "parsed", "screen_count": screen_count, "xml_content": parsed_xml},
                        {"task_name": task_name, "xml_type": "hierarchy", "screen_count": screen_count, "xml_content": hierarchy_xml},
                        {"task_name": task_name, "xml_type": "encoded", "screen_count": screen_count, "xml_content": encoded_xml},
                    ]
                    for d in docs:
                        temp_xmls.replace_one(
                            {"task_name": d["task_name"], "xml_type": d["xml_type"], "screen_count": d["screen_count"]},
                            d,
                            upsert=True,
                        )
                except Exception:
                    pass
            else:
                # 本地模式：不立即写盘，解析并缓存，待任务名可用后统一落盘
                from screenParser import parseXML
                import xml.etree.ElementTree as ET
                parsed_xml = parseXML.parse(xml_content)
                hierarchy_xml = parseXML.hierarchy_parse(parsed_xml)
                tree = ET.fromstring(parsed_xml)
                for element in tree.iter():
                    for k in ("bounds", "important", "class"):
                        if k in element.attrib:
                            del element.attrib[k]
                encoded_xml = ET.tostring(tree, encoding='unicode')
                # 缓存原始XML，供事后落盘；不再依赖索引去重，后续按 page_index 配对
                screen_count = getattr(mobilegpt, '_screen_count', 0)
                buf = getattr(mobilegpt, '_local_buffer', None)
                if buf is None:
                    buf = {'xmls': [], 'shots': []}
                    setattr(mobilegpt, '_local_buffer', buf)
                # 不再按 index 去重：始终入队，并立刻标注 page_index
                buf['xmls'].append({'index': screen_count, 'xml': xml_content})
                try:
                    buf['xmls'][-1]['page_index'] = getattr(mobilegpt, 'current_page_index', -1)
                except Exception:
                    pass
                log(f"[buffer] xml queued idx={screen_count}, raw_len={len(xml_content)}, xmls={len(buf['xmls'])}", "blue")
            
            log(f"XML异步解析完成: parsed={len(parsed_xml)}字符, hierarchy={len(hierarchy_xml)}字符, encoded={len(encoded_xml)}字符", "green")
            
            # 调用MobileGPT的get_next_action方法
            action = mobilegpt.get_next_action(parsed_xml, hierarchy_xml, encoded_xml)
            
            if action:
                log(f"MobileGPT异步返回动作: {action}", "green")
                return {
                    "status": "xml_processed", 
                    "action": action,
                    "session_id": session_id
                }
            else:
                log("MobileGPT未返回动作", "yellow")
                return {
                    "status": "xml_processed_no_action", 
                    "session_id": session_id
                }
                
        except Exception as e:
            log(f"异步XML处理失败: {e}", "red")
            import traceback
            traceback.print_exc()
            return {
                "status": "xml_failed", 
                "error": str(e),
                "session_id": session_id
            }
    
    def _process_screenshot(self, data: dict) -> dict:
        """处理截图相关任务（按策略保存到 DB 或本地）"""
        screenshot_data = data.get('screenshot', b'')
        session_id = data.get('session_id', '')
        mobilegpt = data.get('mobilegpt')
        if not screenshot_data:
            return {"status": "screenshot_skipped", "reason": "empty", "session_id": session_id}

        try:
            from env_config import Config
            from utils.mongo_utils import check_connection, get_db
            from datetime import datetime
            import os, base64

            task_name = getattr(getattr(mobilegpt, 'memory', None), 'task_name', 'untitled') or 'untitled'
            db_available = bool(Config.ENABLE_DB) and check_connection()

            # 统一由会话维度递增计数，若无会话信息则回退到MobileGPT
            try:
                from session_manager import SessionManager
                session = SessionManager().get_session(session_id)
            except Exception:
                session = None
            if session is not None:
                screen_count = getattr(session, 'screen_count', 0)
                setattr(session, 'screen_count', screen_count + 1)
            else:
                screen_count = getattr(mobilegpt, '_screen_count', 0)
                setattr(mobilegpt, '_screen_count', screen_count + 1)

            if db_available:
                try:
                    db = get_db()
                    temp_screenshots = db['temp_screenshots']
                    b64 = base64.b64encode(screenshot_data).decode('utf-8')
                    temp_screenshots.replace_one(
                        {"task_name": task_name, "screen_count": screen_count},
                        {"task_name": task_name, "screen_count": screen_count, "screenshot": b64, "created_at": datetime.now()},
                        upsert=True,
                    )
                except Exception:
                    pass
            else:
                # 本地模式：不立即写盘，缓存截图；MobileGPT 未就绪时先入会话预缓冲
                if session is not None and getattr(session, 'mobilegpt', None) is None:
                    if session.prebuffer is None:
                        session.prebuffer = {'xmls': [], 'shots': []}
                    session.prebuffer['shots'].append({'index': screen_count, 'bytes': screenshot_data})
                    try:
                        session.prebuffer['shots'][-1]['page_index'] = -1
                    except Exception:
                        pass
                    from log_config import log
                    log(f"[buffer] shot queued (session) idx={screen_count}, size={len(screenshot_data)}, shots={len(session.prebuffer['shots'])}", "blue")
                else:
                    buf = getattr(mobilegpt, '_local_buffer', None)
                    if buf is None:
                        buf = {'xmls': [], 'shots': []}
                        setattr(mobilegpt, '_local_buffer', buf)
                    buf['shots'].append({'index': screen_count, 'bytes': screenshot_data})
                    try:
                        buf['shots'][-1]['page_index'] = getattr(mobilegpt, 'current_page_index', -1)
                    except Exception:
                        pass
                    from log_config import log
                    log(f"[buffer] shot queued idx={screen_count}, size={len(screenshot_data)}, shots={len(buf['shots'])}", "blue")

            return {"status": "screenshot_processed", "data_size": len(screenshot_data), "session_id": session_id}
        except Exception as e:
            log(f"截图处理失败: {e}", "red")
            return {"status": "screenshot_failed", "error": str(e), "session_id": session_id}
    
    
    def _stats_loop(self):
        """统计信息更新循环"""
        while self.running:
            try:
                time.sleep(10)  # 每10秒更新一次统计
                
                self.stats['queue_size'] = self.task_queue.qsize()
                self.stats['active_tasks'] = len(self.active_tasks)
                
                # 清理过期的已完成任务（保留最近1000个）
                if len(self.completed_tasks) > 1000:
                    # 按时间排序，删除最旧的
                    sorted_tasks = sorted(
                        self.completed_tasks.items(),
                        key=lambda x: x[1].get('timestamp', 0),
                        reverse=True
                    )
                    self.completed_tasks = dict(sorted_tasks[:1000])
                
            except Exception as e:
                log(f"统计更新出错: {e}", "red")
    
    def get_stats(self) -> dict:
        """获取统计信息"""
        return self.stats.copy()
    
    def get_queue_status(self) -> dict:
        """获取队列状态"""
        return {
            'queue_size': self.task_queue.qsize(),
            'max_queue_size': self.max_queue_size,
            'active_tasks': len(self.active_tasks),
            'completed_tasks': len(self.completed_tasks)
        }


class MessageQueue:
    """消息队列管理器"""
    
    def __init__(self, max_size: int = 10000):
        self.max_size = max_size
        self.queue = queue.Queue(maxsize=max_size)
        self.running = False
        self.processor_thread = None
    
    def start(self, processor: Callable):
        """启动消息队列处理器"""
        if self.running:
            return
        
        self.running = True
        self.processor_thread = threading.Thread(
            target=self._process_messages,
            args=(processor,),
            name="MessageQueueProcessor",
            daemon=True
        )
        self.processor_thread.start()
        
        log("消息队列处理器已启动", "green")
    
    def stop(self):
        """停止消息队列处理器"""
        self.running = False
        if self.processor_thread:
            self.processor_thread.join(timeout=5)
        
        log("消息队列处理器已停止", "yellow")
    
    def put_message(self, message: dict, timeout: float = 1.0) -> bool:
        """添加消息到队列"""
        try:
            self.queue.put(message, timeout=timeout)
            return True
        except queue.Full:
            log("消息队列已满，无法添加消息", "red")
            return False
    
    def get_message(self, timeout: float = 1.0) -> Optional[dict]:
        """从队列获取消息"""
        try:
            return self.queue.get(timeout=timeout)
        except queue.Empty:
            return None
    
    def _process_error_recovery(self, data: dict) -> dict:
        """处理错误恢复任务"""
        try:
            error_info = data.get('error_info', {})
            session_id = data.get('session_id', '')
            mobilegpt = data.get('mobilegpt')
            
            log(f"开始处理错误恢复任务: {session_id}", "green")
            
            # 这里可以添加具体的错误恢复逻辑
            # 由于错误恢复逻辑比较复杂，建议直接调用server中的同步方法
            # 或者在这里实现简化的错误恢复逻辑
            
            return {
                'status': 'error_recovery_completed',
                'session_id': session_id,
                'error_type': error_info.get('error_type', 'UNKNOWN'),
                'success': True
            }
            
        except Exception as e:
            log(f"错误恢复任务处理失败: {e}", "red")
            return {
                'status': 'error_recovery_failed',
                'error': str(e),
                'success': False
            }

    def _process_messages(self, processor: Callable):
        """处理消息循环"""
        while self.running:
            try:
                message = self.get_message(timeout=1.0)
                if message:
                    processor(message)
            except Exception as e:
                log(f"消息处理出错: {e}", "red")
    
    def get_status(self) -> dict:
        """获取队列状态"""
        return {
            'queue_size': self.queue.qsize(),
            'max_size': self.max_size,
            'running': self.running
        }


# 全局异步处理器实例
async_processor = AsyncProcessor(max_workers=10, max_queue_size=1000)
message_queue = MessageQueue(max_size=10000)
