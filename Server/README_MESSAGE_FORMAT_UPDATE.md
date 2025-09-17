# Server端消息格式更新说明

## 概述

本次更新适配了App端新的`MobileGPTMessage`统一消息结构体，主要增强了错误消息的处理能力，并添加了对新消息类型的支持。

## 主要变更

### 1. 新增错误消息处理 (Message Type 'E')

**之前**: 错误消息处理不完整，缺少上下文信息

**现在**: 完整支持新的错误消息格式，包含丰富的上下文信息

```python
# 新增的错误消息处理逻辑
elif message_type == 'E':
    # 读取错误数据（包含文件大小信息）
    file_info = b''
    while not file_info.endswith(b'\n'):
        file_info += client_socket.recv(1)
    file_size_str = file_info.decode().strip()
    file_size = int(file_size_str)
    
    # 读取完整错误数据
    error_data = b''
    bytes_remaining = file_size
    while bytes_remaining > 0:
        data = client_socket.recv(min(bytes_remaining, self.buffer_size))
        error_data += data
        bytes_remaining -= len(data)
    
    # 解析错误信息
    error_string = error_data.decode().strip()
    error_info = self._parse_error_message(error_string)
    
    # 保存preXml到MongoDB用于调试
    if error_info.get('pre_xml'):
        self._save_xml_to_mongo(error_info['pre_xml'], screen_count, 'error_pre_xml')
```

### 2. 新增获取操作列表请求处理 (Message Type 'G')

```python
elif message_type == 'G':
    log("Get actions request received", "blue")
    # 这里可以返回可用的操作列表，目前暂时忽略
    pass
```

### 3. 增强的错误消息解析功能

新增`_parse_error_message`方法，支持解析以下格式的错误消息：

```
ERROR_TYPE:ACTION
ERROR_MESSAGE:操作执行失败
ACTION:click
INSTRUCTION:打开设置
PRE_XML:
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <!-- XML内容 -->
</hierarchy>
REMARK:测试备注
```

**解析结果**:
- `error_type`: 错误类型 (NETWORK/ACTION/SYSTEM/UNKNOWN)
- `error_message`: 错误描述
- `action`: 当前执行的动作
- `instruction`: 当前指令
- `pre_xml`: 上一次的XML内容
- `remark`: 备注信息

### 4. 增强的连接错误处理

为所有消息发送操作添加了`ConnectionAbortedError`异常处理：

```python
try:
    client_socket.send(message.encode())
    client_socket.send("\r\n".encode())
except ConnectionAbortedError:
    log("Client disconnected during action sending", "yellow")
    break
```

## 支持的消息类型

| 消息类型 | 描述 | 状态 | 说明 |
|---------|------|------|------|
| I | 指令消息 | ✅ 支持 | 用户自然语言指令 |
| S | 截图消息 | ✅ 支持 | 屏幕截图数据 |
| X | XML消息 | ✅ 支持 | 界面XML结构 |
| A | 问答消息 | ✅ 支持 | 用户问答交互 |
| E | 错误消息 | ✅ 新增 | 包含完整上下文信息 |
| G | 获取操作列表 | ✅ 新增 | 请求可用操作列表 |
| L | 应用列表 | ⚠️ 忽略 | 单应用模式下忽略 |

## 错误消息格式详解

### App端发送的错误消息格式

```kotlin
// App端构建错误数据
private fun buildErrorData(message: MobileGPTMessage): String {
    val errorData = StringBuilder()
    errorData.append("ERROR_TYPE:${message.errType}\n")
    errorData.append("ERROR_MESSAGE:${message.errMessage}\n")
    
    if (message.preXml.isNotEmpty()) {
        errorData.append("PRE_XML:\n")
        errorData.append(message.preXml)
        errorData.append("\n")
    }
    
    if (message.action.isNotEmpty()) {
        errorData.append("ACTION:${message.action}\n")
    }
    
    if (message.instruction.isNotEmpty()) {
        errorData.append("INSTRUCTION:${message.instruction}\n")
    }
    
    if (message.remark.isNotEmpty()) {
        errorData.append("REMARK:${message.remark}\n")
    }
    
    return errorData.toString()
}
```

### Server端解析逻辑

```python
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
            # 收集后续所有行作为XML内容
            xml_lines = []
            for xml_line in lines[lines.index(line) + 1:]:
                if xml_line.startswith(('ERROR_TYPE:', 'ERROR_MESSAGE:', 'ACTION:', 'INSTRUCTION:', 'REMARK:')):
                    break
                xml_lines.append(xml_line)
            if xml_lines:
                error_info['pre_xml'] = '\n'.join(xml_lines)
    
    return error_info
```

## 数据存储增强

### MongoDB存储

错误消息中的`pre_xml`会自动保存到MongoDB：

```python
# 保存preXml到MongoDB用于调试
if error_info.get('pre_xml'):
    self._save_xml_to_mongo(error_info['pre_xml'], screen_count, 'error_pre_xml')
```

存储结构：
```json
{
    "task_name": "当前任务名称",
    "screen_count": 屏幕计数,
    "xml_type": "error_pre_xml",
    "xml_content": "XML内容",
    "created_at": "时间戳"
}
```

## 向后兼容性

- ✅ 保持所有现有消息类型的处理逻辑不变
- ✅ 新增的错误消息处理不影响现有功能
- ✅ 连接错误处理增强了系统稳定性
- ✅ 所有修改都是增量式的，不会破坏现有功能

## 测试验证

提供了完整的测试脚本 `test_error_parsing.py`：

```bash
cd Server
python test_error_parsing.py
```

测试覆盖：
- 基本错误消息解析
- 包含preXml的复杂错误消息
- 最小错误消息
- 复杂XML内容解析
- 消息格式兼容性验证

## 日志输出示例

```
Error message received: ERROR_TYPE:ACTION
ERROR_MESSAGE:操作执行失败
ACTION:click
INSTRUCTION:打开设置
PRE_XML:
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <node index="0" text="测试按钮" clickable="true"/>
</hierarchy>
REMARK:测试备注

Parsed error - Type: ACTION, Message: 操作执行失败, Action: click, Instruction: 打开设置
```

## 注意事项

1. **文件大小处理**: 错误消息现在包含文件大小信息，需要先读取大小再读取内容
2. **XML内容解析**: preXml内容可能包含多行，需要正确识别结束位置
3. **MongoDB连接**: 错误消息解析依赖MongoDB连接，需要确保数据库可用
4. **内存管理**: 大型XML内容可能占用较多内存，建议监控内存使用情况
5. **错误处理**: 所有网络操作都添加了异常处理，提高了系统稳定性

## 未来扩展

1. **操作列表响应**: 可以扩展G消息类型，返回可用的操作列表
2. **错误统计**: 可以添加错误统计和分析功能
3. **XML验证**: 可以添加XML格式验证功能
4. **性能优化**: 可以优化大型XML的解析性能
