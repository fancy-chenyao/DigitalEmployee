#!/usr/bin/env python3
"""
服务器启动脚本 - 完整日志版本
将所有控制台输出重定向到文件，同时保持控制台显示
"""

import os
import sys
import time
from datetime import datetime
from io import StringIO
import threading

class TeeOutput:
    """同时输出到控制台和文件的类"""
    def __init__(self, file_path, mode='a'):
        self.terminal = sys.stdout
        self.log_file = open(file_path, mode, encoding='utf-8')
        
    def write(self, message):
        # 写入控制台
        self.terminal.write(message)
        self.terminal.flush()
        
        # 写入文件
        self.log_file.write(message)
        self.log_file.flush()
        
    def flush(self):
        self.terminal.flush()
        self.log_file.flush()
        
    def close(self):
        self.log_file.close()

class TeeError:
    """同时输出错误到控制台和文件的类"""
    def __init__(self, file_path, mode='a'):
        self.terminal = sys.stderr
        self.log_file = open(file_path, mode, encoding='utf-8')
        
    def write(self, message):
        # 写入控制台
        self.terminal.write(message)
        self.terminal.flush()
        
        # 写入文件
        self.log_file.write(message)
        self.log_file.flush()
        
    def flush(self):
        self.terminal.flush()
        self.log_file.flush()
        
    def close(self):
        self.log_file.close()

def setup_full_logging():
    """设置完整的日志重定向"""
    
    # 创建日志目录
    log_dir = "logs"
    if not os.path.exists(log_dir):
        os.makedirs(log_dir)
    
    # 生成带时间戳的日志文件名
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    stdout_log = os.path.join(log_dir, f"server_output_{timestamp}.log")
    stderr_log = os.path.join(log_dir, f"server_error_{timestamp}.log")
    combined_log = os.path.join(log_dir, f"server_combined_{timestamp}.log")
    
    print(f"📝 日志文件:")
    print(f"   - 标准输出: {stdout_log}")
    print(f"   - 错误输出: {stderr_log}")
    print(f"   - 合并日志: {combined_log}")
    print(f"   - 系统日志: server.log")
    print()
    
    # 重定向标准输出和错误输出
    sys.stdout = TeeOutput(stdout_log)
    sys.stderr = TeeError(stderr_log)
    
    # 创建合并日志文件
    combined_file = open(combined_log, 'w', encoding='utf-8')
    
    # 写入日志头部信息
    header = f"""
{'='*80}
🚀 MobileGPT 服务器启动日志
{'='*80}
启动时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
Python版本: {sys.version}
工作目录: {os.getcwd()}
{'='*80}

"""
    
    combined_file.write(header)
    combined_file.flush()
    
    return combined_file

def main():
    """主函数"""
    print("🔧 正在设置完整日志系统...")
    
    # 设置完整日志
    combined_file = setup_full_logging()
    
    try:
        print("🚀 启动 MobileGPT 服务器...")
        print(f"⏰ 启动时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print()
        
        # 导入并启动服务器
        from main import main as server_main
        server_main()
        
    except KeyboardInterrupt:
        print("\n⚠️  收到中断信号，正在关闭服务器...")
        
    except Exception as e:
        print(f"\n❌ 服务器启动失败: {e}")
        import traceback
        traceback.print_exc()
        
    finally:
        print(f"\n🔚 服务器关闭时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        
        # 关闭日志文件
        if combined_file:
            combined_file.close()
            
        # 恢复标准输出
        if hasattr(sys.stdout, 'close'):
            sys.stdout.close()
        if hasattr(sys.stderr, 'close'):
            sys.stderr.close()

if __name__ == "__main__":
    main()