#!/usr/bin/env python3
"""
日志配置工具
提供详细的日志输出配置和监控功能
"""

import logging
import sys
from datetime import datetime
from typing import Optional

class ColoredFormatter(logging.Formatter):
    """彩色日志格式化器"""
    
    # 颜色代码
    COLORS = {
        'DEBUG': '\033[36m',    # 青色
        'INFO': '\033[32m',     # 绿色
        'WARNING': '\033[33m',  # 黄色
        'ERROR': '\033[31m',    # 红色
        'CRITICAL': '\033[35m', # 紫色
        'RESET': '\033[0m'      # 重置
    }
    
    def format(self, record):
        # 添加颜色
        if record.levelname in self.COLORS:
            record.levelname = f"{self.COLORS[record.levelname]}{record.levelname}{self.COLORS['RESET']}"
        
        # 添加时间戳
        record.timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        
        # 自定义格式
        if hasattr(record, 'color'):
            color = self.COLORS.get(record.color, '')
            reset = self.COLORS['RESET']
            record.msg = f"{color}{record.msg}{reset}"
        
        return super().format(record)

def setup_logging(level: str = "INFO", enable_file_logging: bool = True) -> logging.Logger:
    """设置日志配置"""
    
    # 创建根日志器
    logger = logging.getLogger()
    logger.setLevel(getattr(logging, level.upper()))
    
    # 清除现有处理器
    for handler in logger.handlers[:]:
        logger.removeHandler(handler)
    
    # 创建控制台处理器
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(logging.DEBUG)
    
    # 创建格式化器
    formatter = ColoredFormatter(
        fmt='%(timestamp)s [%(levelname)s] %(message)s',
        datefmt='%H:%M:%S'
    )
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    
    # 创建文件处理器（可选）
    if enable_file_logging:
        try:
            file_handler = logging.FileHandler('server.log', encoding='utf-8')
            file_handler.setLevel(logging.DEBUG)
            file_formatter = logging.Formatter(
                fmt='%(asctime)s [%(levelname)s] %(message)s',
                datefmt='%Y-%m-%d %H:%M:%S'
            )
            file_handler.setFormatter(file_formatter)
            logger.addHandler(file_handler)
        except Exception as e:
            print(f"无法创建文件日志: {e}")
    
    return logger

def log_with_color(message: str, color: str = "white", level: str = "INFO"):
    """带颜色的日志输出"""
    logger = logging.getLogger()
    
    # 创建日志记录
    record = logging.LogRecord(
        name=logger.name,
        level=getattr(logging, level.upper()),
        pathname="",
        lineno=0,
        msg=message,
        args=(),
        exc_info=None
    )
    
    # 添加颜色信息
    record.color = color
    
    # 输出日志
    logger.handle(record)

def log(message: str, color: str = "white"):
    """统一日志入口，兼容旧接口: log(msg, color)
    颜色到级别的映射：
      red -> ERROR, yellow -> WARNING, green/blue/white -> INFO, cyan -> DEBUG
    """
    level_map = {
        'red': 'ERROR',
        'yellow': 'WARNING',
        'green': 'INFO',
        'blue': 'INFO',
        'white': 'INFO',
        'cyan': 'DEBUG',
    }
    level = level_map.get(color, 'INFO')
    log_with_color(message, color=color, level=level)

def log_system_status():
    """输出系统状态日志"""
    print("\n" + "="*60)
    print("📊 系统状态监控")
    print("="*60)
    print(f"⏰ 当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("🟢 服务器运行中...")
    print("📝 日志级别: INFO")
    print("🎨 彩色输出: 启用")
    print("📁 文件日志: 启用")
    print("="*60)




