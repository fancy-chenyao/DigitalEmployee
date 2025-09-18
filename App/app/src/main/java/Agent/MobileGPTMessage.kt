package Agent

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * MobileGPT统一消息结构体
 * 用于统一管理所有类型的消息发送
 */
data class MobileGPTMessage(
    var messageType: String = "",           // 消息类型：I(指令)、S(截图)、X(XML)、A(问答)、E(错误)、G(获取操作)
    var instruction: String = "",           // 原始指令
    var curXml: String = "",                // 当前XML字符串
    var preXml: String = "",                // 上一次的XML字符串 (预留)
    var qaMessage: String = "",             // 问答字符串
    var errType: String = "",               // 错误类型
    var errMessage: String = "",            // 错误信息
    var advice: String = "",                // AI服务器发送过来的建议 (预留)
    var action: String = "",                // 当前执行的动作
    var actionMessage: String = "",         // 动作执行的返回信息 (预留)
    var remark: String = "",                // 备注 (预留)
    var screenshot: Bitmap? = null          // 截图数据 (仅用于截图消息)
) {
    
    companion object {
        // 消息类型常量
        const val TYPE_INSTRUCTION = "I"
        const val TYPE_SCREENSHOT = "S"
        const val TYPE_XML = "X"
        const val TYPE_QA = "A"
        const val TYPE_ERROR = "E"
        const val TYPE_GET_ACTIONS = "G"
        
        // 错误类型常量
        const val ERROR_TYPE_NETWORK = "NETWORK"
        const val ERROR_TYPE_ACTION = "ACTION"
        const val ERROR_TYPE_SYSTEM = "SYSTEM"
        const val ERROR_TYPE_UNKNOWN = "UNKNOWN"
        
        /**
         * 从JSON字符串创建消息对象
         */
        fun fromJsonString(jsonString: String): MobileGPTMessage {
            val json = JSONObject(jsonString)
            return MobileGPTMessage(
                messageType = json.optString("messageType", ""),
                instruction = json.optString("instruction", ""),
                curXml = json.optString("curXml", ""),
                preXml = json.optString("preXml", ""),
                qaMessage = json.optString("qaMessage", ""),
                errType = json.optString("errType", ""),
                errMessage = json.optString("errMessage", ""),
                advice = json.optString("advice", ""),
                action = json.optString("action", ""),
                actionMessage = json.optString("actionMessage", ""),
                remark = json.optString("remark", "")
            )
        }
    }
    
    /**
     * 创建指令消息
     */
    fun createInstructionMessage(instruction: String): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_INSTRUCTION,
            instruction = instruction
        )
    }
    
    /**
     * 创建截图消息
     */
    fun createScreenshotMessage(bitmap: Bitmap): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_SCREENSHOT,
            screenshot = bitmap
        )
    }
    
    /**
     * 创建XML消息
     */
    fun createXmlMessage(xml: String): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_XML,
            curXml = xml
        )
    }
    
    /**
     * 创建问答消息
     */
    fun createQAMessage(qaString: String): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_QA,
            qaMessage = qaString
        )
    }
    
    /**
     * 创建错误消息
     */
    fun createErrorMessage(errType: String, errMessage: String): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_ERROR,
            errType = errType,
            errMessage = errMessage
        )
    }
    
    /**
     * 创建获取操作列表消息
     */
    fun createGetActionsMessage(): MobileGPTMessage {
        return this.copy(
            messageType = TYPE_GET_ACTIONS
        )
    }
    
    /**
     * 将消息转换为JSON字符串
     * 注意：截图数据不包含在JSON中，需要单独处理
     */
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("messageType", messageType)
        json.put("instruction", instruction)
        json.put("curXml", curXml)
        json.put("preXml", preXml)
        json.put("qaMessage", qaMessage)
        json.put("errType", errType)
        json.put("errMessage", errMessage)
        json.put("advice", advice)
        json.put("action", action)
        json.put("actionMessage", actionMessage)
        json.put("remark", remark)
        json.put("hasScreenshot", screenshot != null)
        return json.toString()
    }
    
    
    /**
     * 获取截图字节数组
     */
    fun getScreenshotBytes(): ByteArray? {
        return screenshot?.let { bitmap ->
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
            byteArrayOutputStream.toByteArray()
        }
    }
    
    /**
     * 检查消息是否有效
     */
    fun isValid(): Boolean {
        return when (messageType) {
            TYPE_INSTRUCTION -> instruction.isNotEmpty()
            TYPE_SCREENSHOT -> screenshot != null
            TYPE_XML -> curXml.isNotEmpty()
            TYPE_QA -> qaMessage.isNotEmpty()
            TYPE_ERROR -> errMessage.isNotEmpty()
            TYPE_GET_ACTIONS -> true
            else -> false
        }
    }
    
    /**
     * 获取消息描述
     */
    fun getDescription(): String {
        return when (messageType) {
            TYPE_INSTRUCTION -> "指令消息: $instruction"
            TYPE_SCREENSHOT -> "截图消息: ${screenshot?.width}x${screenshot?.height}"
            TYPE_XML -> "XML消息: ${curXml.length} 字符"
            TYPE_QA -> "问答消息: $qaMessage"
            TYPE_ERROR -> "错误消息: $errType - $errMessage${if (preXml.isNotEmpty()) " (包含preXml: ${preXml.length}字符)" else ""}"
            TYPE_GET_ACTIONS -> "获取操作列表消息"
            else -> "未知消息类型: $messageType"
        }
    }
}
