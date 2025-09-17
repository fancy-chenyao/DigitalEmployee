# MobileService 动作执行功能说明

## 概述

MobileService现在支持处理AI服务器发送的动作指令，包括点击、输入、滚动、长按、后退等操作。通过统一的Controller动作空间执行UI操作。

## 支持的动作

### 1. 点击动作 (click)
```json
{
  "name": "click",
  "parameters": {
    "index": 23
  }
}
```

### 2. 输入动作 (input)
```json
{
  "name": "input",
  "parameters": {
    "index": 15,
    "input_text": "要输入的文本"
  }
}
```

### 3. 滚动动作 (scroll)
```json
{
  "name": "scroll",
  "parameters": {
    "index": 10,
    "direction": "down"
  }
}
```
支持的方向：up, down, left, right

### 4. 长按动作 (long-click)
```json
{
  "name": "long-click",
  "parameters": {
    "index": 8
  }
}
```

### 5. 后退动作 (go-back)
```json
{
  "name": "go-back",
  "parameters": {}
}
```

### 6. 回到主页动作 (go-home)
```json
{
  "name": "go-home",
  "parameters": {}
}
```

## 实现原理

### 1. 消息解析
- 使用`GPTMessage`类解析AI服务器发送的JSON消息
- 提取动作名称和参数

### 2. 元素定位
- 通过`buildNodeMap`方法将GenericElement树转换为`HashMap<Int, GenericElement>`
- 使用index作为key快速定位目标元素

### 3. 动作执行
- 根据动作类型调用对应的Controller方法
- 支持原生页面、WebView页面和无障碍服务三种页面类型

### 4. 错误处理
- 完整的错误处理机制
- 详细的日志记录
- 错误信息回传给AI服务器

## 使用示例

### 示例1：点击"请休假"按钮
```json
{
  "reasoning": "The subtask requires clicking the '请休假' (leave application) button, which has the id 'iv_leave'. From the HTML code, I can see that there is a button with the id 'iv_leave' at index '23'. Therefore, I will click this button to proceed with the leave application process.",
  "action": {
    "name": "click",
    "parameters": {
      "index": 23
    }
  },
  "completion_rate": 0.25,
  "plan": "Click on the '请休假' (leave application) button to start the leave application process."
}
```

### 示例2：输入文本
```json
{
  "name": "input",
  "parameters": {
    "index": 15,
    "input_text": "张三"
  }
}
```

## 技术细节

### 1. nodeMap构建
```kotlin
private fun buildNodeMap(element: GenericElement) {
    fun traverseElement(elem: GenericElement) {
        nodeMap?.put(elem.index, elem)
        elem.children.forEach { child ->
            traverseElement(child)
        }
    }
    traverseElement(element)
}
```

### 2. 动作分发
```kotlin
private fun executeUIAction(action: String, args: JSONObject) {
    val index = args.getInt("index")
    val targetElement = nodeMap?.get(index)
    
    when (action) {
        "click" -> executeClickAction(currentActivity, targetElement)
        "input" -> executeInputAction(currentActivity, targetElement, inputText)
        // ... 其他动作
    }
}
```

### 3. Controller统一调用
```kotlin
ElementController.clickElement(activity, element.resourceId) { success ->
    if (success) {
        screenNeedUpdate = true
        xmlPending = true
    } else {
        sendActionError("点击动作执行失败")
    }
}
```

## 注意事项

1. **元素索引**：确保AI服务器发送的index与当前屏幕的XML中的index一致
2. **页面类型**：系统会自动检测页面类型（原生/WebView/无障碍）并选择合适的Controller
3. **错误处理**：所有动作执行都有完整的错误处理和日志记录
4. **屏幕更新**：动作执行成功后会触发屏幕更新，发送新的XML和截图给AI服务器

## 调试信息

系统会输出详细的调试日志：
- 动作执行开始和结果
- 元素定位信息
- 错误详情
- nodeMap构建状态

可以通过Logcat查看"MobileGPT_Service"标签的日志来调试问题。
