"""
环境变量配置管理
"""
import os
from typing import Optional


class Config:
    """配置管理类"""
    
    # MongoDB配置
    MONGODB_URI: str = os.getenv("MONGODB_URI", "mongodb://192.168.100.56:27017")
    MONGODB_DB: str = os.getenv("MONGODB_DB", "mobilegpt")
    MONGODB_MAX_POOL_SIZE: int = int(os.getenv("MONGODB_MAX_POOL_SIZE", "50"))
    MONGODB_MIN_POOL_SIZE: int = int(os.getenv("MONGODB_MIN_POOL_SIZE", "5"))
    
    # 服务器配置
    SERVER_HOST: str = os.getenv("SERVER_HOST", "0.0.0.0")
    SERVER_PORT: int = int(os.getenv("SERVER_PORT", "12345"))
    SERVER_BUFFER_SIZE: int = int(os.getenv("SERVER_BUFFER_SIZE", "4096"))
    
    # 日志配置
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    LOG_DIRECTORY: str = os.getenv("LOG_DIRECTORY", "./memory/log")
    
    # 内存配置
    MEMORY_DIRECTORY: str = os.getenv("MEMORY_DIRECTORY", "./memory")
    ENABLE_DB: bool = os.getenv("ENABLE_DB", "false").lower() == "true"
    
    # 其他配置
    OPENAI_API_KEY: Optional[str] = os.getenv("OPENAI_API_KEY")
    
    @classmethod
    def get_mongodb_config(cls) -> dict:
        """获取MongoDB配置"""
        return {
            'uri': cls.MONGODB_URI,
            'db_name': cls.MONGODB_DB,
            'max_pool_size': cls.MONGODB_MAX_POOL_SIZE,
            'min_pool_size': cls.MONGODB_MIN_POOL_SIZE
        }
    
    @classmethod
    def get_server_config(cls) -> dict:
        """获取服务器配置"""
        return {
            'host': cls.SERVER_HOST,
            'port': cls.SERVER_PORT,
            'buffer_size': cls.SERVER_BUFFER_SIZE
        }
    
    @classmethod
    def print_config(cls):
        """打印当前配置"""
        print("=== 当前配置 ===")
        print(f"MongoDB URI: {cls.MONGODB_URI}")
        print(f"MongoDB DB: {cls.MONGODB_DB}")
        print(f"MongoDB Max Pool Size: {cls.MONGODB_MAX_POOL_SIZE}")
        print(f"MongoDB Min Pool Size: {cls.MONGODB_MIN_POOL_SIZE}")
        print(f"Server Host: {cls.SERVER_HOST}")
        print(f"Server Port: {cls.SERVER_PORT}")
        print(f"Enable DB: {cls.ENABLE_DB}")
        print("===============")
