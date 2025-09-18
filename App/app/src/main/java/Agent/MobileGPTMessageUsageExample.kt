package Agent

import android.graphics.Bitmap
import android.util.Log

/**
 * MobileGPT统一消息结构体使用示例
 * 展示如何在实际项目中使用新的消息结构体
 */
class MobileGPTMessageUsageExample {
    
    companion object {
        private const val TAG = "MobileGPTMessageUsageExample"
        
        /**
         * 使用示例：发送指令消息
         */
        fun sendInstructionExample(client: MobileGPTClient) {
            Log.d(TAG, "发送指令消息示例...")
            
            // 创建指令消息
            val message = MobileGPTMessage().createInstructionMessage("打开微信")
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "指令消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：发送截图消息
         */
        fun sendScreenshotExample(client: MobileGPTClient, bitmap: Bitmap) {
            Log.d(TAG, "发送截图消息示例...")
            
            // 创建截图消息
            val message = MobileGPTMessage().createScreenshotMessage(bitmap)
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "截图消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：发送XML消息
         */
        fun sendXmlExample(client: MobileGPTClient, xmlContent: String) {
            Log.d(TAG, "发送XML消息示例...")
            
            // 创建XML消息
            val message = MobileGPTMessage().createXmlMessage(xmlContent)
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "XML消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：发送问答消息
         */
        fun sendQAExample(client: MobileGPTClient, infoName: String, question: String, answer: String) {
            Log.d(TAG, "发送问答消息示例...")
            
            // 构建问答字符串
            val qaString = "$infoName\\$question\\$answer"
            
            // 创建问答消息
            val message = MobileGPTMessage().createQAMessage(qaString)
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "问答消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：发送错误消息
         */
        fun sendErrorExample(client: MobileGPTClient, errorType: String, errorMessage: String) {
            Log.d(TAG, "发送错误消息示例...")
            
            // 创建错误消息
            val message = MobileGPTMessage().createErrorMessage(errorType, errorMessage)
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "错误消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：发送获取操作列表消息
         */
        fun sendGetActionsExample(client: MobileGPTClient) {
            Log.d(TAG, "发送获取操作列表消息示例...")
            
            // 创建获取操作列表消息
            val message = MobileGPTMessage().createGetActionsMessage()
            
            // 发送消息
            client.sendMessage(message)
            
            Log.d(TAG, "获取操作列表消息发送完成: ${message.getDescription()}")
        }
        
        /**
         * 使用示例：创建包含多个字段的复杂消息
         */
        fun sendComplexMessageExample(client: MobileGPTClient) {
            Log.d(TAG, "发送复杂消息示例...")
            
            // 创建包含多个字段的消息
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_XML
                curXml = "<hierarchy><node>test</node></hierarchy>"
                action = "click"
                remark = "这是一个测试消息"
            }
            
            // 验证消息有效性
            if (message.isValid()) {
                client.sendMessage(message)
                Log.d(TAG, "复杂消息发送完成: ${message.getDescription()}")
            } else {
                Log.e(TAG, "消息验证失败，无法发送")
            }
        }
        
        /**
         * 使用示例：批量发送消息
         */
        fun sendBatchMessagesExample(client: MobileGPTClient) {
            Log.d(TAG, "批量发送消息示例...")
            
            val messages = listOf(
                MobileGPTMessage().createInstructionMessage("打开应用"),
                MobileGPTMessage().createXmlMessage("<test>xml</test>"),
                MobileGPTMessage().createQAMessage("用户\\姓名\\张三"),
                MobileGPTMessage().createErrorMessage(MobileGPTMessage.ERROR_TYPE_SYSTEM, "系统错误")
            )
            
            messages.forEach { message ->
                if (message.isValid()) {
                    client.sendMessage(message)
                    Log.d(TAG, "批量消息发送: ${message.getDescription()}")
                } else {
                    Log.e(TAG, "无效消息，跳过发送: ${message.messageType}")
                }
            }
            
            Log.d(TAG, "批量消息发送完成")
        }
        
        /**
         * 使用示例：消息的JSON序列化
         */
        fun jsonSerializationExample() {
            Log.d(TAG, "JSON序列化示例...")
            
            // 创建消息
            val message = MobileGPTMessage().apply {
                messageType = MobileGPTMessage.TYPE_INSTRUCTION
                instruction = "测试指令"
                curXml = "<test>xml</test>"
                action = "click"
                remark = "测试备注"
            }
            
            // 序列化为JSON
            val jsonString = message.toJsonString()
            Log.d(TAG, "序列化结果: $jsonString")
            
            // 从JSON反序列化
            val deserializedMessage = MobileGPTMessage.fromJsonString(jsonString)
            Log.d(TAG, "反序列化结果: ${deserializedMessage.getDescription()}")
        }
        
        /**
         * 使用示例：错误处理
         */
        fun errorHandlingExample(client: MobileGPTClient) {
            Log.d(TAG, "错误处理示例...")
            
            try {
                // 尝试执行某个操作
                val result = performSomeOperation()
                
                when (result) {
                    is OperationResult.Success -> {
                        // 操作成功，发送成功消息
                        val message = MobileGPTMessage().createInstructionMessage("操作成功完成")
                        client.sendMessage(message)
                    }
                    is OperationResult.Error -> {
                        // 操作失败，发送错误消息
                        val message = MobileGPTMessage().createErrorMessage(
                            MobileGPTMessage.ERROR_TYPE_ACTION,
                            "操作失败: ${result.errorMessage}"
                        )
                        client.sendMessage(message)
                    }
                }
            } catch (e: Exception) {
                // 捕获异常，发送系统错误消息
                val message = MobileGPTMessage().createErrorMessage(
                    MobileGPTMessage.ERROR_TYPE_SYSTEM,
                    "系统异常: ${e.message}"
                )
                client.sendMessage(message)
            }
        }
        
        /**
         * 模拟操作结果
         */
        private fun performSomeOperation(): OperationResult {
            // 模拟操作逻辑
            return if (Math.random() > 0.5) {
                OperationResult.Success("操作成功")
            } else {
                OperationResult.Error("操作失败")
            }
        }
        
        /**
         * 操作结果数据类
         */
        sealed class OperationResult {
            data class Success(val message: String) : OperationResult()
            data class Error(val errorMessage: String) : OperationResult()
        }
    }
}
