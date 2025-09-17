#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试错误消息解析功能
验证新的MobileGPTMessage错误格式是否能被正确解析
"""

import sys
import os
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from server import Server

def test_error_parsing():
    """测试错误消息解析功能"""
    print("=== 测试错误消息解析功能 ===")
    
    # 创建Server实例
    server = Server()
    
    # 测试用例1：基本错误消息
    error_string1 = """ERROR_TYPE:ACTION
ERROR_MESSAGE:操作执行失败
ACTION:click
INSTRUCTION:打开设置
REMARK:测试备注"""
    
    result1 = server._parse_error_message(error_string1)
    print("测试用例1 - 基本错误消息:")
    print(f"  错误类型: {result1.get('error_type', 'None')}")
    print(f"  错误消息: {result1.get('error_message', 'None')}")
    print(f"  动作: {result1.get('action', 'None')}")
    print(f"  指令: {result1.get('instruction', 'None')}")
    print(f"  备注: {result1.get('remark', 'None')}")
    print(f"  preXml: {result1.get('pre_xml', 'None')}")
    print()
    
    # 测试用例2：包含preXml的错误消息
    error_string2 = """ERROR_TYPE:NETWORK
ERROR_MESSAGE:网络连接失败
ACTION:sendXML
INSTRUCTION:发送屏幕信息
PRE_XML:
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <node index="0" text="测试按钮" clickable="true"/>
  <node index="1" text="输入框" editable="true"/>
</hierarchy>
REMARK:网络超时"""
    
    result2 = server._parse_error_message(error_string2)
    print("测试用例2 - 包含preXml的错误消息:")
    print(f"  错误类型: {result2.get('error_type', 'None')}")
    print(f"  错误消息: {result2.get('error_message', 'None')}")
    print(f"  动作: {result2.get('action', 'None')}")
    print(f"  指令: {result2.get('instruction', 'None')}")
    print(f"  备注: {result2.get('remark', 'None')}")
    print(f"  preXml长度: {len(result2.get('pre_xml', ''))}")
    print(f"  preXml内容: {result2.get('pre_xml', 'None')[:100]}...")
    print()
    
    # 测试用例3：只有错误类型和消息
    error_string3 = """ERROR_TYPE:SYSTEM
ERROR_MESSAGE:系统内部错误"""
    
    result3 = server._parse_error_message(error_string3)
    print("测试用例3 - 最小错误消息:")
    print(f"  错误类型: {result3.get('error_type', 'None')}")
    print(f"  错误消息: {result3.get('error_message', 'None')}")
    print(f"  动作: {result3.get('action', 'None')}")
    print(f"  指令: {result3.get('instruction', 'None')}")
    print(f"  备注: {result3.get('remark', 'None')}")
    print(f"  preXml: {result3.get('pre_xml', 'None')}")
    print()
    
    # 测试用例4：复杂XML内容
    complex_xml = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <node index="0" text="主界面" class="android.widget.LinearLayout">
    <node index="1" text="标题栏" class="android.widget.TextView"/>
    <node index="2" text="内容区域" class="android.widget.ScrollView">
      <node index="3" text="按钮1" class="android.widget.Button" clickable="true"/>
      <node index="4" text="按钮2" class="android.widget.Button" clickable="true"/>
    </node>
  </node>
</hierarchy>"""
    
    error_string4 = f"""ERROR_TYPE:ACTION
ERROR_MESSAGE:点击操作失败
ACTION:click
INSTRUCTION:点击按钮1
PRE_XML:
{complex_xml}
REMARK:按钮不可点击"""
    
    result4 = server._parse_error_message(error_string4)
    print("测试用例4 - 复杂XML内容:")
    print(f"  错误类型: {result4.get('error_type', 'None')}")
    print(f"  错误消息: {result4.get('error_message', 'None')}")
    print(f"  动作: {result4.get('action', 'None')}")
    print(f"  指令: {result4.get('instruction', 'None')}")
    print(f"  备注: {result4.get('remark', 'None')}")
    print(f"  preXml长度: {len(result4.get('pre_xml', ''))}")
    print(f"  preXml是否包含按钮1: {'按钮1' in result4.get('pre_xml', '')}")
    print()
    
    print("=== 所有测试完成 ===")

def test_message_format_compatibility():
    """测试消息格式兼容性"""
    print("\n=== 测试消息格式兼容性 ===")
    
    # 模拟App端发送的各种消息类型
    message_types = ['I', 'S', 'X', 'A', 'E', 'G']
    
    print("支持的消息类型:")
    for msg_type in message_types:
        print(f"  {msg_type} - ", end="")
        if msg_type == 'I':
            print("指令消息 (Instruction)")
        elif msg_type == 'S':
            print("截图消息 (Screenshot)")
        elif msg_type == 'X':
            print("XML消息 (XML)")
        elif msg_type == 'A':
            print("问答消息 (QA)")
        elif msg_type == 'E':
            print("错误消息 (Error) - 新增支持")
        elif msg_type == 'G':
            print("获取操作列表消息 (Get Actions) - 新增支持")
    
    print("\n新的错误消息格式支持:")
    print("  - ERROR_TYPE: 错误类型 (NETWORK/ACTION/SYSTEM/UNKNOWN)")
    print("  - ERROR_MESSAGE: 错误描述")
    print("  - ACTION: 当前执行的动作")
    print("  - INSTRUCTION: 当前指令")
    print("  - PRE_XML: 上一次的XML内容")
    print("  - REMARK: 备注信息")
    
    print("\n=== 兼容性测试完成 ===")

if __name__ == "__main__":
    test_error_parsing()
    test_message_format_compatibility()
