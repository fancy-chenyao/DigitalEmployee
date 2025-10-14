"""
客户端会话管理器
解决多客户端状态冲突和资源竞争问题
"""

import threading
import time
import uuid
from typing import Dict, Optional, Any
from dataclasses import dataclass
from datetime import datetime, timedelta
import json

from mobilegpt import MobileGPT
from memory.memory_manager import Memory
from log_config import log


@dataclass
class ClientSession:
    """客户端会话信息"""
    session_id: str
    client_socket: Any
    client_address: tuple
    created_at: datetime
    last_activity: datetime
    mobilegpt: Optional[MobileGPT] = None
    memory: Optional[Memory] = None
    instruction: str = ""
    task_name: str = ""
    is_active: bool = True
    # 新增：会话级截图计数与预缓冲，避免MobileGPT未初始化期间的数据错位
    screen_count: int = 0
    prebuffer: dict = None
    
    def update_activity(self):
        """更新最后活动时间"""
        self.last_activity = datetime.now()
        if self.prebuffer is None:
            self.prebuffer = {'xmls': [], 'shots': []}
    
    def is_expired(self, timeout_minutes: int = 30) -> bool:
        """检查会话是否过期"""
        if not self.is_active:
            return True
        return datetime.now() - self.last_activity > timedelta(minutes=timeout_minutes)
    
    def to_dict(self) -> dict:
        """转换为字典格式"""
        return {
            'session_id': self.session_id,
            'client_address': self.client_address,
            'created_at': self.created_at.isoformat(),
            'last_activity': self.last_activity.isoformat(),
            'instruction': self.instruction,
            'task_name': self.task_name,
            'is_active': self.is_active
        }


class SessionManager:
    """会话管理器 - 单例模式"""
    
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
        return cls._instance
    
    def __init__(self):
        if hasattr(self, '_initialized'):
            return
        
        self._initialized = True
        self.sessions: Dict[str, ClientSession] = {}
        self.session_locks: Dict[str, threading.RLock] = {}
        self.cleanup_thread = None
        self.running = True
        
        # 启动清理线程
        self._start_cleanup_thread()
    
    def create_session(self, client_socket, client_address) -> ClientSession:
        """创建新的客户端会话"""
        session_id = str(uuid.uuid4())
        
        # 创建会话锁
        session_lock = threading.RLock()
        self.session_locks[session_id] = session_lock
        
        # 创建会话对象
        session = ClientSession(
            session_id=session_id,
            client_socket=client_socket,
            client_address=client_address,
            created_at=datetime.now(),
            last_activity=datetime.now()
        )
        
        with session_lock:
            self.sessions[session_id] = session
            log(f"创建新会话: {session_id} from {client_address}", "green")
        
        return session
    
    def get_session(self, session_id: str) -> Optional[ClientSession]:
        """获取指定会话"""
        if session_id not in self.sessions:
            return None
        
        session = self.sessions[session_id]
        if session.is_expired():
            self.remove_session(session_id)
            return None
        
        session.update_activity()
        return session
    
    def get_session_by_socket(self, client_socket) -> Optional[ClientSession]:
        """通过socket获取会话"""
        for session in self.sessions.values():
            if session.client_socket == client_socket:
                if session.is_expired():
                    self.remove_session(session.session_id)
                    return None
                session.update_activity()
                return session
        return None
    
    def update_session(self, session_id: str, **kwargs) -> bool:
        """更新会话信息"""
        if session_id not in self.sessions:
            return False
        
        session = self.sessions[session_id]
        if session.is_expired():
            self.remove_session(session_id)
            return False
        
        with self.session_locks[session_id]:
            for key, value in kwargs.items():
                if hasattr(session, key):
                    setattr(session, key, value)
            session.update_activity()
        
        return True
    
    def remove_session(self, session_id: str) -> bool:
        """移除会话"""
        if session_id not in self.sessions:
            return False
        
        session = self.sessions[session_id]
        
        # 清理资源
        try:
            if session.mobilegpt:
                # 清理MobileGPT资源
                pass
            if session.memory:
                # 清理Memory资源
                pass
            if session.client_socket:
                session.client_socket.close()
        except Exception as e:
            log(f"清理会话资源时出错: {e}", "red")
        
        # 移除会话和锁
        with self.session_locks.get(session_id, threading.RLock()):
            if session_id in self.sessions:
                del self.sessions[session_id]
            if session_id in self.session_locks:
                del self.session_locks[session_id]
        
        log(f"移除会话: {session_id}", "yellow")
        return True
    
    def get_active_sessions(self) -> Dict[str, ClientSession]:
        """获取所有活跃会话"""
        active_sessions = {}
        expired_sessions = []
        
        for session_id, session in self.sessions.items():
            if session.is_expired():
                expired_sessions.append(session_id)
            else:
                active_sessions[session_id] = session
        
        # 清理过期会话
        for session_id in expired_sessions:
            self.remove_session(session_id)
        
        return active_sessions
    
    def get_session_stats(self) -> dict:
        """获取会话统计信息"""
        active_sessions = self.get_active_sessions()
        
        return {
            'total_sessions': len(active_sessions),
            'active_sessions': len([s for s in active_sessions.values() if s.is_active]),
            'sessions': [session.to_dict() for session in active_sessions.values()]
        }
    
    def _start_cleanup_thread(self):
        """启动清理线程"""
        def cleanup_worker():
            while self.running:
                try:
                    # 每5分钟清理一次过期会话
                    time.sleep(300)
                    self._cleanup_expired_sessions()
                except Exception as e:
                    log(f"清理线程出错: {e}", "red")
        
        self.cleanup_thread = threading.Thread(target=cleanup_worker, daemon=True)
        self.cleanup_thread.start()
        log("会话清理线程已启动", "blue")
    
    def _cleanup_expired_sessions(self):
        """清理过期会话"""
        expired_sessions = []
        
        for session_id, session in self.sessions.items():
            if session.is_expired():
                expired_sessions.append(session_id)
        
        for session_id in expired_sessions:
            self.remove_session(session_id)
        
        if expired_sessions:
            log(f"清理了 {len(expired_sessions)} 个过期会话", "yellow")
    
    def shutdown(self):
        """关闭会话管理器"""
        self.running = False
        
        # 关闭所有会话
        for session_id in list(self.sessions.keys()):
            self.remove_session(session_id)
        
        log("会话管理器已关闭", "green")


class ResourceLock:
    """资源锁管理器"""
    
    def __init__(self):
        self.locks: Dict[str, threading.RLock] = {}
        self.lock_creation_lock = threading.Lock()
    
    def get_lock(self, resource_id: str) -> threading.RLock:
        """获取资源锁"""
        if resource_id not in self.locks:
            with self.lock_creation_lock:
                if resource_id not in self.locks:
                    self.locks[resource_id] = threading.RLock()
        return self.locks[resource_id]
    
    def acquire_lock(self, resource_id: str, timeout: float = 10.0) -> bool:
        """获取资源锁（带超时）"""
        lock = self.get_lock(resource_id)
        return lock.acquire(timeout=timeout)
    
    def release_lock(self, resource_id: str):
        """释放资源锁"""
        if resource_id in self.locks:
            self.locks[resource_id].release()
    
    def __enter__(self, resource_id: str):
        """上下文管理器入口"""
        self.acquire_lock(resource_id)
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """上下文管理器出口"""
        pass


# 全局资源锁管理器
resource_lock = ResourceLock()



