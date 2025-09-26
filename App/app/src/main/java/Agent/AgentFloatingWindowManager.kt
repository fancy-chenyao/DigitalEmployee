package Agent

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * Agent悬浮窗管理器
 * 负责管理Agent悬浮窗的显示、隐藏和交互
 * 支持指令发送和ask功能
 */
class AgentFloatingWindowManager(private val context: Context) {
    
    private var windowManager: WindowManager? = null
    private var floatingView: AgentFloatingView? = null
    private var isShowing = false
    private var askDialog: AgentAskDialog? = null
    
    /**
     * 显示悬浮窗
     */
    fun showFloatingWindow() {
        if (isShowing) {
            Toast.makeText(context, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // 获取WindowManager
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // 仅应用内悬浮窗：不再请求系统悬浮窗权限

            // 创建悬浮窗视图
            createFloatingView()
            
            // 设置布局参数
            val layoutParams = createLayoutParams()
            
            // 设置WindowManager和LayoutParams到FloatingView
            floatingView?.setWindowManager(windowManager!!, layoutParams)
            
            // 添加到窗口
            windowManager?.addView(floatingView, layoutParams)
            isShowing = true
            
            Toast.makeText(context, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            AgentErrorHandler.handleFloatingWindowError(context, "悬浮窗显示失败: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏悬浮窗
     */
    fun hideFloatingWindow() {
        if (!isShowing) return
        
        try {
            windowManager?.removeView(floatingView)
            isShowing = false
            Toast.makeText(context, "悬浮窗已隐藏", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AgentErrorHandler.handleFloatingWindowError(context, "悬浮窗隐藏失败: ${e.message}", e)
        }
    }
    
    /**
     * 切换悬浮窗显示状态
     */
    fun toggleFloatingWindow() {
        if (isShowing) {
            hideFloatingWindow()
        } else {
            showFloatingWindow()
        }
    }
    
    /**
     * 显示ask对话框
     */
    fun showAskDialog(infoName: String, question: String) {
        if (!AgentErrorHandler.validateQuestion(infoName, question)) {
            AgentErrorHandler.handleValidationError(context, "问题参数无效: infoName='$infoName', question='$question'")
            return
        }
        
        try {
            // 已经有悬浮按钮时，走悬浮按钮内置的显示逻辑
            floatingView?.let {
                it.showAskDialog(infoName, question)
                return
            }

            // 没有悬浮按钮时，直接以对话框方式显示（仅应用内）
            if (context is android.app.Activity) {
                askDialog?.dismiss()
                askDialog = AgentAskDialog(context).apply {
                    setQuestion(infoName, question)
                    setOnSendAnswerListener { info, ques, answer ->
                        sendAnswerToServer(info, ques, answer)
                        dismiss()
                    }
                    setOnDismissListener { askDialog = null }
                }
                askDialog?.show()
            } else {
                AgentErrorHandler.handleDialogError(context, "当前上下文非Activity，无法显示应用内对话框")
            }
        } catch (e: Exception) {
            AgentErrorHandler.handleDialogError(context, "显示ask对话框失败: ${e.message}", e)
        }
    }
    
    /**
     * 创建悬浮窗视图
     */
    private fun createFloatingView() {
        floatingView = AgentFloatingView(context)
        // 设置发送命令回调
        floatingView?.onSendCommand = { command ->
            sendCommandToServer(command)
        }
        // 设置发送答案回调
        floatingView?.onSendAnswer = { infoName, question, answer ->
            sendAnswerToServer(infoName, question, answer)
        }
    }
    
    /**
     * 创建布局参数
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams()
        
        // 设置窗口大小
        params.width = 120
        params.height = 120
        
        // 设置窗口位置（使用绝对坐标，不使用Gravity）
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = 30  // 距离左边30dp
        params.y = 100 // 距离顶部100dp
        
        // 设置窗口类型和标志
        // 仅应用内悬浮窗（依附当前Activity窗口）
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        
        // 设置像素格式
        params.format = PixelFormat.TRANSLUCENT
        
        return params
    }

    /**
     * 判断是否可以使用 TYPE_APPLICATION（仅限Activity上下文）
     */
    private fun canUseApplicationWindow(): Boolean {
        return context is android.app.Activity
    }

    /**
     * 是否已拥有悬浮窗权限
     */
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * 引导用户开启悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
    
    /**
     * 检查悬浮窗是否正在显示
     */
    fun isFloatingWindowShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 发送命令到服务器
     */
    private fun sendCommandToServer(command: String) {
        try {
            // 创建广播Intent发送命令到MobileService
            val intent = Intent(MobileGPTGlobal.STRING_ACTION)
            intent.putExtra(MobileGPTGlobal.INSTRUCTION_EXTRA, command)
            context.sendBroadcast(intent)
            
            Toast.makeText(context, "命令已发送: $command", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "发送命令失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 发送答案到服务器
     */
    private fun sendAnswerToServer(infoName: String, question: String, answer: String) {
        try {
            // 验证输入参数
            if (!AgentErrorHandler.validateAnswer(infoName, question, answer)) {
                AgentErrorHandler.handleValidationError(context, "答案信息不完整: infoName='$infoName', question='$question', answer='$answer'")
                return
            }
            
            // 创建广播Intent发送答案到MobileService
            val intent = Intent(MobileGPTGlobal.ANSWER_ACTION)
            intent.putExtra(MobileGPTGlobal.INFO_NAME_EXTRA, infoName)
            intent.putExtra(MobileGPTGlobal.QUESTION_EXTRA, question)
            intent.putExtra(MobileGPTGlobal.ANSWER_EXTRA, answer)
            
            // 添加时间戳用于调试
            intent.putExtra("timestamp", System.currentTimeMillis())
            
            context.sendBroadcast(intent)
            
            Toast.makeText(context, "答案已发送: $answer", Toast.LENGTH_SHORT).show()
            
            // 记录日志
            android.util.Log.d("AgentFloatingWindowManager", "答案已发送: $infoName - $question - $answer")
            
        } catch (e: Exception) {
            AgentErrorHandler.handleCommunicationError(context, "发送答案失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        if (isShowing) {
            hideFloatingWindow()
        }
        floatingView = null
        windowManager = null
    }
}
