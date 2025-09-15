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
        Log.d("MobileGPTclient","开始连接 socket")
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
     * 发送指令到服务器
     * @param instruction 要发送的指令
     */
    fun sendInstruction(instruction: String) {
        Log.d("MobileGPTclient","发送指令")
        try {
            socket?.let {
                dos?.writeByte('I'.code)
                dos?.write((instruction + "\n").toByteArray(Charsets.UTF_8))
                dos?.flush()
            } ?: Log.d(TAG, "socket not connected yet")
        } catch (e: IOException) {
            Log.e(TAG, "server offline")
        }
    }
    /**
     * 发送XML数据到服务器
     * @param xml 要发送的XML字符串
     */
    fun sendXML(xml: String) {
        Log.d("MobileGPTclient","发送XML")
        try {
            socket?.let {
                dos?.writeByte('X'.code)
                val size = xml.toByteArray(Charsets.UTF_8).size
                val fileSize = "$size\n"
                dos?.write(fileSize.toByteArray())

                // 发送xml
                dos?.write(xml.toByteArray(StandardCharsets.UTF_8))
                dos?.flush()

                Log.v(TAG, "xml sent successfully")
            }
        } catch (e: IOException) {
            Log.e(TAG, "server offline")
        }
    }
    
    /**
     * 发送问答数据到服务器
     * @param qaString 问答字符串
     */
    fun sendQA(qaString: String) {
        Log.d("MobileGPTclient","发送问答")
        try {
            socket?.let {
                dos?.writeByte('A'.code)
                dos?.write((qaString + "\n").toByteArray(Charsets.UTF_8))
                dos?.flush()
                Log.d(TAG, "QA sent successfully")
            } ?: Log.d(TAG, "socket not connected yet")
        } catch (e: IOException) {
            Log.d(TAG, "server offline")
            Log.e(TAG, "IOException: ${e.message}")
        }
    }
    
    /**
     * 发送错误信息到服务器
     * @param msg 错误消息
     */
    fun sendError(msg: String) {
        try {
            socket?.let {
                dos?.writeByte('E'.code)
                dos?.write((msg + "\n").toByteArray(Charsets.UTF_8))
                dos?.flush()
            } ?: Log.d(TAG, "socket not connected yet")
        } catch (e: IOException) {
            Log.d(TAG, "server offline")
        }
    }
    
    /**
     * 请求获取操作列表
     */
    fun getActions() {
        try {
            socket?.let {
                dos?.writeByte('G'.code)
                dos?.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "server offline")
        }
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