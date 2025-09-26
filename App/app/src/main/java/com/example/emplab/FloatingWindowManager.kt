// package com.example.emplab

// import android.app.Activity
// import android.content.Context
// import android.content.Intent
// import android.graphics.PixelFormat
// import android.view.Gravity
// import android.view.View
// import android.view.WindowManager
// import android.widget.Toast
// import Agent.MobileGPTGlobal

// /**
//  * 悬浮窗管理器
//  * 负责管理APP内部悬浮窗的显示、隐藏和交互
//  */
// class FloatingWindowManager(private val activity: Activity) {
    
//     private var windowManager: WindowManager? = null
//     private var floatingView: FloatingView? = null
//     private var isShowing = false
    
//     /**
//      * 显示悬浮窗
//      */
//     fun showFloatingWindow() {
//         if (isShowing) {
//             Toast.makeText(activity, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
//             return
//         }
        
//         try {
//             // 获取WindowManager
//             windowManager = activity.windowManager
            
//             // 创建悬浮窗视图
//             createFloatingView()
            
//             // 设置布局参数
//             val layoutParams = createLayoutParams()
            
//             // 设置WindowManager和LayoutParams到FloatingView
//             floatingView?.setWindowManager(windowManager!!, layoutParams)
            
//             // 添加到窗口
//             windowManager?.addView(floatingView, layoutParams)
//             isShowing = true
            
//             Toast.makeText(activity, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
            
//         } catch (e: Exception) {
//             Toast.makeText(activity, "悬浮窗显示失败: ${e.message}", Toast.LENGTH_SHORT).show()
//         }
//     }
    
//     /**
//      * 隐藏悬浮窗
//      */
//     fun hideFloatingWindow() {
//         if (!isShowing) return
        
//         try {
//             windowManager?.removeView(floatingView)
//             isShowing = false
//             Toast.makeText(activity, "悬浮窗已隐藏", Toast.LENGTH_SHORT).show()
//         } catch (e: Exception) {
//             Toast.makeText(activity, "悬浮窗隐藏失败: ${e.message}", Toast.LENGTH_SHORT).show()
//         }
//     }
    
//     /**
//      * 切换悬浮窗显示状态
//      */
//     fun toggleFloatingWindow() {
//         if (isShowing) {
//             hideFloatingWindow()
//         } else {
//             showFloatingWindow()
//         }
//     }
    
//     /**
//      * 创建悬浮窗视图
//      */
//     private fun createFloatingView() {
//         floatingView = FloatingView(activity)
//         // 设置发送命令回调
//         floatingView?.onSendCommand = { command ->
//             sendCommandToServer(command)
//         }
//         // 注意：不再设置OnClickListener，因为拖拽功能会处理触摸事件
//     }
    
//     /**
//      * 切换输入对话框
//      */
//     private fun toggleInputDialog() {
//         floatingView?.toggleInputDialog()
//     }
    
//     /**
//      * 创建布局参数
//      */
//     private fun createLayoutParams(): WindowManager.LayoutParams {
//         val params = WindowManager.LayoutParams()
        
//         // 设置窗口大小
//         params.width = 120
//         params.height = 120
        
//         // 设置窗口位置（使用绝对坐标，不使用Gravity）
//         params.gravity = Gravity.TOP or Gravity.LEFT
//         params.x = 30  // 距离左边30dp
//         params.y = 100 // 距离顶部100dp
        
//         // 设置窗口类型和标志
//         params.type = WindowManager.LayoutParams.TYPE_APPLICATION
//         params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
//                       WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        
//         // 设置像素格式
//         params.format = PixelFormat.TRANSLUCENT
        
//         return params
//     }
    
//     /**
//      * 检查悬浮窗是否正在显示
//      */
//     fun isFloatingWindowShowing(): Boolean {
//         return isShowing
//     }
    
//     /**
//      * 发送命令到服务器
//      */
//     private fun sendCommandToServer(command: String) {
//         try {
//             // 创建广播Intent发送命令到MobileService
//             val intent = Intent(MobileGPTGlobal.STRING_ACTION)
//             intent.putExtra(MobileGPTGlobal.INSTRUCTION_EXTRA, command)
//             activity.sendBroadcast(intent)
            
//             Toast.makeText(activity, "命令已发送: $command", Toast.LENGTH_SHORT).show()
//         } catch (e: Exception) {
//             Toast.makeText(activity, "发送命令失败: ${e.message}", Toast.LENGTH_SHORT).show()
//         }
//     }
    
//     /**
//      * 清理资源
//      */
//     fun cleanup() {
//         if (isShowing) {
//             hideFloatingWindow()
//         }
//         floatingView = null
//         windowManager = null
//     }
// }
