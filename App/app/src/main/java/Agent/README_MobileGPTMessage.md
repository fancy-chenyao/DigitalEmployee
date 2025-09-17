# MobileGPT统一消息结构体使用指南

## 概述

`MobileGPTMessage` 是一个统一的消息结构体，用于管理所有类型的消息发送。它简化了与AI服务器的通信，提供了类型安全的消息创建和发送机制。

## 消息类型

支持以下6种消息类型：

- **I** - 指令消息 (`TYPE_INSTRUCTION`)
- **S** - 截图消息 (`TYPE_SCREENSHOT`)
- **X** - XML消息 (`TYPE_XML`)
- **A** - 问答消息 (`TYPE_QA`)
- **E** - 错误消息 (`TYPE_ERROR`)
- **G** - 获取操作列表消息 (`TYPE_GET_ACTIONS`)

## 消息字段

| 字段名 | 类型 | 描述 | 是否必需 |
|--------|------|------|----------|
| `messageType` | String | 消息类型标识 | 是 |
| `instruction` | String | 原始指令 | 指令消息必需 |
| `curXml` | String | 当前XML字符串 | XML消息必需 |
| `preXml` | String | 上一次的XML字符串 | 错误消息时包含 |
| `qaMessage` | String | 问答字符串 | 问答消息必需 |
| `errType` | String | 错误类型 | 错误消息必需 |
| `errMessage` | String | 错误信息 | 错误消息必需 |
| `advice` | String | AI服务器建议 | 预留字段 |
| `action` | String | 当前执行的动作 | 错误消息时包含 |
| `actionMessage` | String | 动作执行返回信息 | 预留字段 |
| `remark` | String | 备注 | 预留字段 |
| `screenshot` | Bitmap? | 截图数据 | 截图消息必需 |

## 错误类型常量

- `ERROR_TYPE_NETWORK` - 网络错误
- `ERROR_TYPE_ACTION` - 操作错误
- `ERROR_TYPE_SYSTEM` - 系统错误
- `ERROR_TYPE_UNKNOWN` - 未知错误

## 新增功能特性

### 1. 状态记录功能

系统现在会自动记录以下状态信息：

- **preXml记录**：每次更新屏幕XML时，会自动保存上一次的XML
- **action记录**：每次处理AI服务器消息时，会记录当前执行的动作
- **instruction记录**：每次发送指令时，会记录当前发送的指令

### 2. 增强的错误消息

错误消息现在包含完整的上下文信息：

```
ERROR_TYPE:ACTION
ERROR_MESSAGE:操作执行失败
PRE_XML:
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <!-- 上一次的XML内容 -->
</hierarchy>
ACTION:click
INSTRUCTION:打开设置
REMARK:备注信息
```

### 3. 自动状态管理

- 每次发送指令时自动记录instruction
- 每次处理AI服务器响应时自动记录action
- 每次更新屏幕时自动记录preXml
- 服务重置时自动清理所有状态

### 4. 页面变化监听功能

- **Activity生命周期监听**：通过ActivityTracker监听Activity变化
- **ViewTreeObserver监听**：监听页面布局变化
- **防抖处理**：500ms防抖延迟，避免频繁触发
- **自动屏幕更新**：页面变化时自动发送XML和截图
- **手动触发**：提供手动触发页面变化检测的功能

## 基本使用方法

### 1. 创建消息

```kotlin
// 创建指令消息
val instructionMessage = MobileGPTMessage().createInstructionMessage("打开设置")

// 创建XML消息
val xmlMessage = MobileGPTMessage().createXmlMessage("<hierarchy>...</hierarchy>")

// 创建问答消息
val qaMessage = MobileGPTMessage().createQAMessage("用户信息\\用户名\\张三")

// 创建错误消息
val errorMessage = MobileGPTMessage().createErrorMessage(
    MobileGPTMessage.ERROR_TYPE_ACTION, 
    "操作执行失败"
)

// 创建获取操作列表消息
val getActionsMessage = MobileGPTMessage().createGetActionsMessage()

// 创建截图消息
val screenshotMessage = MobileGPTMessage().createScreenshotMessage(bitmap)
```

### 2. 发送消息

```kotlin
// 使用新的统一发送方法
client.sendMessage(message)

// 或者使用向后兼容的方法（推荐使用统一方法）
client.sendInstruction("打开设置")
client.sendXML("<xml>...</xml>")
client.sendQA("问答字符串")
client.sendError("错误信息")
client.getActions()
client.sendScreenshot(bitmap)
```

### 3. 消息验证

```kotlin
if (message.isValid()) {
    client.sendMessage(message)
} else {
    Log.e(TAG, "消息验证失败")
}
```

### 4. 获取消息描述

```kotlin
val description = message.getDescription()
Log.d(TAG, "消息描述: $description")
```

## 高级功能

### 状态记录示例

```kotlin
// 系统会自动记录状态，无需手动操作
// 发送指令时自动记录
val instructionMessage = MobileGPTMessage().createInstructionMessage("打开设置")
client.sendMessage(instructionMessage) // 自动记录instruction

// 处理AI服务器响应时自动记录
// 在handleResponse中自动记录action

// 更新屏幕时自动记录
// 在saveCurrScreenXML中自动记录preXml
```

### 错误消息包含完整上下文

```kotlin
// 错误消息会自动包含所有相关状态
val errorMessage = MobileGPTMessage().apply {
    messageType = MobileGPTMessage.TYPE_ERROR
    errType = MobileGPTMessage.ERROR_TYPE_ACTION
    errMessage = "操作执行失败"
    preXml = previousScreenXML  // 自动包含上一次XML
    action = currentAction      // 自动包含当前动作
    instruction = currentInstruction // 自动包含当前指令
}
client.sendMessage(errorMessage)
```

### 页面变化监听示例

```kotlin
// 页面变化监听是自动的，无需手动配置
// 系统会自动监听Activity变化和页面布局变化

// 手动触发页面变化检测
mobileService.triggerPageChangeDetection()

// 检查页面变化监听是否活跃
val isActive = mobileService.isPageChangeListenerActive()
Log.d(TAG, "页面变化监听状态: $isActive")
```

### JSON序列化

```kotlin
// 序列化消息为JSON
val jsonString = message.toJsonString()

// 从JSON反序列化消息
val deserializedMessage = MobileGPTMessage.fromJsonString(jsonString)
```

### 复杂消息创建

```kotlin
val complexMessage = MobileGPTMessage().apply {
    messageType = MobileGPTMessage.TYPE_XML
    curXml = "<hierarchy>...</hierarchy>"
    action = "click"
    remark = "这是一个测试消息"
}
```

### 批量消息发送

```kotlin
val messages = listOf(
    MobileGPTMessage().createInstructionMessage("指令1"),
    MobileGPTMessage().createXMLMessage("<xml>...</xml>"),
    MobileGPTMessage().createQAMessage("问答")
)

messages.forEach { message ->
    if (message.isValid()) {
        client.sendMessage(message)
    }
}
```

## 错误处理

```kotlin
try {
    val message = MobileGPTMessage().createInstructionMessage("测试指令")
    client.sendMessage(message)
} catch (e: Exception) {
    val errorMessage = MobileGPTMessage().createErrorMessage(
        MobileGPTMessage.ERROR_TYPE_SYSTEM,
        "发送失败: ${e.message}"
    )
    client.sendMessage(errorMessage)
}
```

## 向后兼容性

为了保持向后兼容性，原有的发送方法仍然可用：

- `sendInstruction(instruction: String)`
- `sendScreenshot(bitmap: Bitmap)`
- `sendXML(xml: String)`
- `sendQA(qaString: String)`
- `sendError(msg: String)`
- `getActions()`

这些方法内部会创建相应的 `MobileGPTMessage` 对象并调用 `sendMessage()` 方法。

## 最佳实践

1. **优先使用统一方法**：推荐使用 `sendMessage(message)` 而不是单独的方法
2. **消息验证**：发送前始终验证消息的有效性
3. **错误处理**：使用适当的错误类型和描述
4. **资源管理**：及时回收Bitmap资源，避免内存泄漏
5. **日志记录**：使用 `getDescription()` 方法记录消息信息
6. **状态管理**：系统会自动管理状态记录，无需手动干预
7. **错误上下文**：错误消息会自动包含完整的上下文信息，便于调试

## 测试

项目包含了完整的测试类：

- `MobileGPTMessageTest` - 单元测试
- `MobileGPTMessageUsageExample` - 使用示例

运行测试以验证功能：

```kotlin
MobileGPTMessageTest.testAllMessageTypes()
MobileGPTMessageTest.testJsonSerialization()
MobileGPTMessageTest.testMessageValidation()
```

## 注意事项

1. 截图消息中的Bitmap会在发送后自动处理，无需手动回收
2. 预留字段（advice、actionMessage、remark）目前未使用，但已为未来扩展做好准备
3. preXml、action、instruction字段现在会在错误消息中自动包含
4. 消息验证基于消息类型和必需字段，确保发送的消息格式正确
5. JSON序列化不包含Bitmap数据，截图需要单独处理
6. 状态记录是自动的，无需手动管理，但会在服务重置时自动清理
7. 错误消息现在包含完整的上下文信息，便于服务器端调试和分析
8. 页面变化监听使用防抖机制，避免频繁触发，提高性能
9. ViewTreeObserver监听会在Activity变化时自动切换，无需手动管理
10. 页面变化监听在服务销毁时会自动清理，防止内存泄漏
