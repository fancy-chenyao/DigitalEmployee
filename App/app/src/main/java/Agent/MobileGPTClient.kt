package Agent

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets
/**
 * MobileGPT客户端类，负责与服务器通信
 */
class MobileGPTClient(private val serverAddress: String, private val serverPort: Int) {
    
    companion object {
        private const val TAG = "MobileGPT_CLIENT"
    }
    
    private var socket: Socket? = null
    private var dos: DataOutputStream? = null
    
    /**
     * 连接到服务器
     * @throws IOException 连接失败时抛出异常
     */
    @Throws(IOException::class)
    fun connect() {
        Log.d("MobileGPTclient","开始连接 socket ${serverAddress}:${serverPort}")
        socket = Socket(serverAddress, serverPort)
        dos = DataOutputStream(socket!!.getOutputStream())
    }
    
    /**
     * 断开与服务器的连接
     */
    fun disconnect() {
        Log.d("MobileGPTclient","断开连接 socket")
        try {
            socket?.let {
                dos?.close()
                it.close()
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }
    
    
    /**
     * 发送统一消息到服务器
     * @param message 要发送的统一消息对象
     */
    fun sendMessage(message: MobileGPTMessage) {
        Log.d("MobileGPTclient", "发送消息: ${message.getDescription()}")
        try {
            if (socket != null) {
                when (message.messageType) {
                    MobileGPTMessage.TYPE_INSTRUCTION -> {
                        dos?.writeByte('I'.code)
                        dos?.write((message.instruction + "\n").toByteArray(Charsets.UTF_8))
                        dos?.flush()
                    }
                    MobileGPTMessage.TYPE_SCREENSHOT -> {
                        dos?.writeByte('S'.code)
                        val screenshotBytes = message.getScreenshotBytes()
                        if (screenshotBytes != null) {
                            val size = screenshotBytes.size
                            val fileSize = "$size\n"
                            dos?.write(fileSize.toByteArray())
                            dos?.write(screenshotBytes)
                            dos?.flush()
                        }
                    }
                    MobileGPTMessage.TYPE_XML -> {
                        dos?.writeByte('X'.code)
                        val size = message.curXml.toByteArray(Charsets.UTF_8).size
                        val fileSize = "$size\n"
                        dos?.write(fileSize.toByteArray())
                        dos?.write(message.curXml.toByteArray(StandardCharsets.UTF_8))
                        dos?.flush()
                    }
                    MobileGPTMessage.TYPE_QA -> {
                        dos?.writeByte('A'.code)
                        dos?.write((message.qaMessage + "\n").toByteArray(Charsets.UTF_8))
                        dos?.flush()
                    }
                    MobileGPTMessage.TYPE_ERROR -> {
                        dos?.writeByte('E'.code)
                        // 构建包含preXml和Base64截图的错误消息
                        val errorData = buildErrorData(message)
                        val size = errorData.toByteArray(Charsets.UTF_8).size
                        val fileSize = "$size\n"
                        dos?.write(fileSize.toByteArray())
                        dos?.write(errorData.toByteArray(StandardCharsets.UTF_8))
                        dos?.flush()
                    }
                    MobileGPTMessage.TYPE_GET_ACTIONS -> {
                        dos?.writeByte('G'.code)
                        dos?.flush()
                    }
                    else -> {
                        Log.e(TAG, "未知消息类型: ${message.messageType}")
                    }
                }
            } else {
                Log.d(TAG, "socket not connected yet")
            }
        } catch (e: IOException) {
            Log.e(TAG, "server offline")
        }
    }

    /**
     * 发送指令到服务器 (保持向后兼容)
     * @param instruction 要发送的指令
     */
    fun sendInstruction(instruction: String) {
        val message = MobileGPTMessage().createInstructionMessage(instruction)
        sendMessage(message)
    }
    // fun sendScreenshot(bitmap: Bitmap) {
    //     try {
    //         socket?.let {
    //             dos?.writeByte('S'.code)

    //             val byteArrayOutputStream = ByteArrayOutputStream()
    //             bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
    //             val byteArray = byteArrayOutputStream.toByteArray()

    //             val size = byteArray.size
    //             val fileSize = "$size\n"
    //             dos?.write(fileSize.toByteArray())

    //             // 发送图片
    //             dos?.write(byteArray)
    //             dos?.flush()

    //             Log.v(Agent.MobileGPTClient.Companion.TAG, "screenshot sent successfully")
    //         }
    //     } catch (e: IOException) {
    //         Log.e(Agent.MobileGPTClient.Companion.TAG, "server offline")
    //     }
    // }


    /**
     * 发送截图到服务器 (保持向后兼容)
     * @param bitmap 要发送的截图
     */
    fun sendScreenshot(bitmap: Bitmap) {
        val message = MobileGPTMessage().createScreenshotMessage(bitmap)
        sendMessage(message)
    }
    

    /**
     * 发送XML数据到服务器 (保持向后兼容)
     * @param xml 要发送的XML字符串
     */
    fun sendXML(xml: String) {
        val message = MobileGPTMessage().createXmlMessage(xml)
        sendMessage(message)
    }
    
    /**
     * 发送问答数据到服务器 (保持向后兼容)
     * @param qaString 问答字符串
     */
    fun sendQA(qaString: String) {
        val message = MobileGPTMessage().createQAMessage(qaString)
        sendMessage(message)
    }
    
    /**
     * 发送错误信息到服务器 (保持向后兼容)
     * @param msg 错误消息
     */
    fun sendError(msg: String) {
        val message = MobileGPTMessage().createErrorMessage(MobileGPTMessage.ERROR_TYPE_UNKNOWN, msg)
        sendMessage(message)
    }
    
    /**
     * 请求获取操作列表 (保持向后兼容)
     */
    fun getActions() {
        val message = MobileGPTMessage().createGetActionsMessage()
        sendMessage(message)
    }
    
    /**
     * 接收服务器消息
     * @param onMessageReceived 消息接收回调接口
     */
    fun receiveMessages(onMessageReceived: OnMessageReceived) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                var message: String?
                while (reader.readLine().also { message = it } != null) {
                    message?.let { onMessageReceived.onReceived(it) }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }
    
    /**
     * 构建错误数据，包含preXml和curXml信息
     * @param message 错误消息对象
     * @return 格式化的错误数据字符串
     */
    private fun buildErrorData(message: MobileGPTMessage): String {
        val errorData = StringBuilder()
        errorData.append("ERROR_TYPE:${message.errType}\n")
        errorData.append("ERROR_MESSAGE:${message.errMessage}\n")
        
        // 如果有curXml，则包含在错误数据中
        if (message.curXml.isNotEmpty()) {
            errorData.append("CUR_XML:\n")
            errorData.append(message.curXml)
            errorData.append("\n")
        }
        
        // 如果有preXml，则包含在错误数据中
        if (message.preXml.isNotEmpty()) {
            errorData.append("PRE_XML:\n")
            errorData.append(message.preXml)
            errorData.append("\n")
        }
        
        // 如果有当前动作信息，也包含进去
        if (message.action.isNotEmpty()) {
            errorData.append("ACTION:${message.action}\n")
        }
        
        // 如果有当前指令信息，也包含进去
        if (message.instruction.isNotEmpty()) {
            errorData.append("INSTRUCTION:${message.instruction}\n")
        }
        
        // 如果有备注信息，也包含进去
        if (message.remark.isNotEmpty()) {
            errorData.append("REMARK:${message.remark}\n")
        }
        
        // 如果有截图，添加Base64编码的截图数据
        if (message.screenshot != null) {
            val screenshotBytes = message.getScreenshotBytes()
            if (screenshotBytes != null) {
                val base64Screenshot = android.util.Base64.encodeToString(screenshotBytes, android.util.Base64.NO_WRAP)
                errorData.append("SCREENSHOT_DATA:$base64Screenshot\n")
            }
        }
        
        return errorData.toString()
    }
    
    /**
     * 消息接收回调接口
     */
    interface OnMessageReceived {
        /**
         * 接收到消息时的回调
         * @param message 接收到的消息
         */
        fun onReceived(message: String)
    }
}