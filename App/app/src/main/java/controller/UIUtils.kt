package controller

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * UI工具类，包含通用的UI操作方法
 */
object UIUtils {
    
    /**
     * 将dp转换为px
     * @param activity Activity对象
     * @param dp dp值
     * @return 对应的px值
     */
    fun dpToPx(activity: Activity, dp: Float): Float {
        val density = activity.resources.displayMetrics.density
        return dp * density
    }
    
    /**
     * 将px转换为dp
     * @param activity Activity对象
     * @param px px值
     * @return 对应的dp值
     */
    fun pxToDp(activity: Activity, px: Float): Float {
        val density = activity.resources.displayMetrics.density
        return px / density
    }
    
    /**
     * 获取状态栏高度
     * @param activity Activity对象
     * @return 状态栏高度（px）
     */
    fun getStatusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }
    
    /**
     * 将内容区域坐标转换为窗口坐标
     * @param activity Activity对象
     * @param contentX 内容区域X坐标
     * @param contentY 内容区域Y坐标
     * @return Pair<Float, Float> 窗口坐标 (windowX, windowY)
     */
    fun contentToWindowCoordinates(activity: Activity, contentX: Float, contentY: Float): Pair<Float, Float> {
        val statusBarHeight = getStatusBarHeight(activity)
        return Pair(contentX, contentY + statusBarHeight)
    }
    
    /**
     * 将窗口坐标转换为内容区域坐标
     * @param activity Activity对象
     * @param windowX 窗口X坐标
     * @param windowY 窗口Y坐标
     * @return Pair<Float, Float> 内容区域坐标 (contentX, contentY)
     */
    fun windowToContentCoordinates(activity: Activity, windowX: Float, windowY: Float): Pair<Float, Float> {
        val statusBarHeight = getStatusBarHeight(activity)
        return Pair(windowX, windowY - statusBarHeight)
    }
    
    /**
     * 在指定坐标位置显示发光效果
     * @param activity Activity对象
     * @param x 点击位置的x坐标（px）
     * @param y 点击位置的y坐标（px）
     * @param duration 发光效果持续时间（毫秒）
     */
    fun showGlowEffect(activity: Activity, x: Float, y: Float, duration: Long = 1000) {
        activity.runOnUiThread {
            try {
                // 获取内容区域根视图，与点击事件使用相同的坐标系统
                val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                
                // 创建发光效果视图
                val glowView = View(activity).apply {
                    // 设置发光效果的背景
                    val glowDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#4CAF50")) // 绿色发光
                        setStroke(4, Color.parseColor("#81C784")) // 浅绿色边框
                    }
                    background = glowDrawable
                    
                    // 设置视图大小和位置
                    val size = dpToPx(activity, 40f).toInt() // 40dp的圆形
                    layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                        leftMargin = (x - size / 2).toInt()
                        topMargin = (y - size / 2).toInt()
                    }
                    
                    // 设置初始透明度
                    alpha = 0f
                }
                
                // 添加到根视图
                rootView.addView(glowView)
                
                // 创建动画效果
                val scaleXAnimator = ObjectAnimator.ofFloat(glowView, "scaleX", 0.5f, 1.5f, 0.5f)
                val scaleYAnimator = ObjectAnimator.ofFloat(glowView, "scaleY", 0.5f, 1.5f, 0.5f)
                val alphaAnimator = ObjectAnimator.ofFloat(glowView, "alpha", 0f, 0.8f, 0f)
                
                val animatorSet = AnimatorSet().apply {
                    playTogether(scaleXAnimator, scaleYAnimator, alphaAnimator)
                    this.duration = duration
                }
                
                // 启动动画
                animatorSet.start()
                
                // 动画结束后移除视图
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        rootView.removeView(glowView)
                    } catch (e: Exception) {
                        Log.e("UIUtils", "移除发光效果视图时出错: ${e.message}")
                    }
                }, duration)
                
            } catch (e: Exception) {
                Log.e("UIUtils", "显示发光效果时出错: ${e.message}")
            }
        }
    }
}