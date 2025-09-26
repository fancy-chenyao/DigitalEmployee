package Agent

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Agent错误处理器
 * 统一处理Agent组件中的各种错误和异常情况
 */
object AgentErrorHandler {
    
    private const val TAG = "AgentErrorHandler"
    
    /**
     * 错误类型枚举
     */
    enum class ErrorType {
        FLOATING_WINDOW_ERROR,    // 悬浮窗错误
        DIALOG_ERROR,             // 对话框错误
        COMMUNICATION_ERROR,      // 通信错误
        VALIDATION_ERROR,         // 验证错误
        NETWORK_ERROR,            // 网络错误
        PERMISSION_ERROR,         // 权限错误
        UNKNOWN_ERROR             // 未知错误
    }
    
    /**
     * 处理错误
     */
    fun handleError(context: Context, errorType: ErrorType, message: String, exception: Throwable? = null) {
        // 记录错误日志
        when (errorType) {
            ErrorType.FLOATING_WINDOW_ERROR -> {
                Log.e(TAG, "悬浮窗错误: $message", exception)
                showUserMessage(context, "悬浮窗出现问题: $message")
            }
            ErrorType.DIALOG_ERROR -> {
                Log.e(TAG, "对话框错误: $message", exception)
                showUserMessage(context, "对话框出现问题: $message")
            }
            ErrorType.COMMUNICATION_ERROR -> {
                Log.e(TAG, "通信错误: $message", exception)
                showUserMessage(context, "通信出现问题: $message")
            }
            ErrorType.VALIDATION_ERROR -> {
                Log.w(TAG, "验证错误: $message", exception)
                showUserMessage(context, "输入验证失败: $message")
            }
            ErrorType.NETWORK_ERROR -> {
                Log.e(TAG, "网络错误: $message", exception)
                showUserMessage(context, "网络连接问题: $message")
            }
            ErrorType.PERMISSION_ERROR -> {
                Log.e(TAG, "权限错误: $message", exception)
                showUserMessage(context, "权限不足: $message")
            }
            ErrorType.UNKNOWN_ERROR -> {
                Log.e(TAG, "未知错误: $message", exception)
                showUserMessage(context, "发生未知错误: $message")
            }
        }
    }
    
    /**
     * 处理悬浮窗错误
     */
    fun handleFloatingWindowError(context: Context, message: String, exception: Throwable? = null) {
        handleError(context, ErrorType.FLOATING_WINDOW_ERROR, message, exception)
    }
    
    /**
     * 处理对话框错误
     */
    fun handleDialogError(context: Context, message: String, exception: Throwable? = null) {
        handleError(context, ErrorType.DIALOG_ERROR, message, exception)
    }
    
    /**
     * 处理通信错误
     */
    fun handleCommunicationError(context: Context, message: String, exception: Throwable? = null) {
        handleError(context, ErrorType.COMMUNICATION_ERROR, message, exception)
    }
    
    /**
     * 处理验证错误
     */
    fun handleValidationError(context: Context, message: String, exception: Throwable? = null) {
        handleError(context, ErrorType.VALIDATION_ERROR, message, exception)
    }
    
    /**
     * 验证输入参数
     */
    fun validateInput(vararg inputs: String?): Boolean {
        return inputs.all { it != null && it.isNotBlank() }
    }
    
    /**
     * 验证问题参数
     */
    fun validateQuestion(infoName: String?, question: String?): Boolean {
        return validateInput(infoName, question)
    }
    
    /**
     * 验证答案参数
     */
    fun validateAnswer(infoName: String?, question: String?, answer: String?): Boolean {
        return validateInput(infoName, question, answer)
    }
    
    /**
     * 显示用户消息
     */
    private fun showUserMessage(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "无法显示用户消息: $message", e)
        }
    }
    
    /**
     * 安全执行操作
     */
    inline fun <T> safeExecute(
        context: Context,
        errorType: ErrorType,
        operation: () -> T,
        onError: (String, Throwable?) -> Unit = { msg, ex -> handleError(context, errorType, msg, ex) }
    ): T? {
        return try {
            operation()
        } catch (e: Exception) {
            onError("操作执行失败: ${e.message}", e)
            null
        }
    }
}
