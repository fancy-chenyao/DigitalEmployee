package controller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat.startActivity
import com.example.emplab.MainActivity

object NativeController {
    
    /**
     * 获取页面的元素树
     */
    fun getElementTree(activity: Activity, callback: (GenericElement) -> Unit) {
//        val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
        val rootView = activity.findViewById<View>(android.R.id.content)
//        val rootView = activity.window.decorView as ViewGroup
        var indexCounter = 0
        val elementTree = parseView(rootView, activity) { indexCounter++ }
        callback(elementTree)
    }
    
    /**
     * 解析视图为GenericElement
     * @param view 要解析的视图
     * @param activity Activity对象，用于px到dp的转换
     * @param getNextIndex 获取下一个唯一索引的函数
     * @return GenericElement对象，其中bounds坐标为dp单位
     */
    private fun parseView(view: View, activity: Activity, getNextIndex: () -> Int): GenericElement {
        val location = IntArray(2)
        view.getLocationOnScreen(location) // 获取屏幕坐标（px单位）
        
        // 获取视图文本内容
        val text = when (view) {
            is TextView -> view.text.toString()
            is Button -> view.text.toString()
            is EditText -> view.text.toString()
            is CheckBox -> view.text.toString()
            else -> ""
        }
        
        // 获取内容描述
        val contentDesc = view.contentDescription?.toString() ?: ""

        // 获取checked状态
        val checked = when (view) {
            is CompoundButton -> view.isChecked
            else -> false
        }

        // 构建边界矩形（dp单位）
        val bounds = Rect(
            UIUtils.pxToDp(activity, location[0].toFloat()).toInt(),                    // 左边界（dp）
            UIUtils.pxToDp(activity, location[1].toFloat()).toInt(),                    // 上边界（dp）
            UIUtils.pxToDp(activity, (location[0] + view.width).toFloat()).toInt(),     // 右边界（dp）
            UIUtils.pxToDp(activity, (location[1] + view.height).toFloat()).toInt()     // 下边界（dp）
        )

        // 构建附加属性
        val additionalProps = mutableMapOf<String, String>()
        if (view.id != View.NO_ID) {
            try {
                val resourceName = view.resources.getResourceEntryName(view.id)
                additionalProps["resourceName"] = resourceName
            } catch (e: Exception) {
                // 忽略资源找不到的异常
            }
        }
        
        // 处理子视图
        val children = if (view is ViewGroup && isContainerTraversable(view)) {
            (0 until view.childCount).map { i -> parseView(view.getChildAt(i), activity, getNextIndex) }
        } else {
            emptyList()
        }

        return GenericElement(
//            resourceId = if (view.id != View.NO_ID) {
//                try {
//                    view.resources.getResourceEntryName(view.id)
//                } catch (e: Exception) {
//                    "view_${view.hashCode()}"
//                }
//            } else {
//                "view_${view.hashCode()}"
//            },
            resourceId = "view_${view.hashCode()}",
            className = view.javaClass.name,
            text = text,
            contentDesc = contentDesc,
            bounds = bounds,
            important = view.isImportantForAccessibility,
            enabled = view.isEnabled,
            checked = checked,
            clickable = isViewActuallyClickable(view),
            checkable = when (view) {
                is CompoundButton -> true
                else -> false
            },
            scrollable = when (view) {
                is ScrollView, is HorizontalScrollView -> true
                else -> false
            },
            longClickable = view.isLongClickable,
            selected = view.isSelected,
            index = getNextIndex(),
            naf = false, // 默认为false，可根据需要调整
            additionalProps = additionalProps,
            children = children,
            view = view  // 添加view引用，用于直接操作
        )
    }
    
    /**
     * 点击元素（通过元素ID）
     */
    fun clickElement(activity: Activity, elementId: String, callback: (Boolean) -> Unit) {
        val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
        val targetView = findViewByResourceName(rootView, elementId)
        
        if (targetView != null && targetView.isEnabled && targetView.isClickable) {
            targetView.performClick()
            callback(true)
        } else {
            callback(false)
        }
    }
    
    /**
     * 通过坐标点击元素（dp版本）
     * @param activity 当前Activity
     * @param xDp 点击的X坐标（dp单位）
     * @param yDp 点击的Y坐标（dp单位）
     * @param callback 回调函数，返回操作是否成功
     */
    /**
     * 根据dp坐标点击屏幕位置，并显示发光效果
     * @param activity Activity对象
     * @param xDp 点击位置的x坐标（dp）
     * @param yDp 点击位置的y坐标（dp）
     * @param callback 点击结果回调
     */
    fun clickByCoordinateDp(activity: Activity, xDp: Float, yDp: Float, callback: (Boolean) -> Unit) {
        val xPx = UIUtils.dpToPx(activity, xDp)
        val yPx = UIUtils.dpToPx(activity, yDp)-UIUtils.getStatusBarHeight(activity)
        
        // 打印坐标转换信息
        Log.d("clickByCoordinate", "输入坐标 - xDp: $xDp, yDp: $yDp")
        Log.d("clickByCoordinate", "转换后并减去系统栏的坐标 - xPx: $xPx, yPx: $yPx")
        Log.d("clickByCoordinate", "屏幕密度: ${activity.resources.displayMetrics.density}")
        Log.d("clickByCoordinate", "状态栏高度: ${UIUtils.getStatusBarHeight(activity)}px")
        
        // 显示发光效果（现在使用相同的坐标系统）
        UIUtils.showGlowEffect(activity, xPx, yPx)
        
        // 执行点击操作
        clickByCoordinate(activity, xPx, yPx, callback)
    }
    
    /**
     * 通过坐标点击元素（px版本）
     * @param activity 当前Activity
     * @param x 点击的X坐标（px单位）
     * @param y 点击的Y坐标（px单位）
     * @param callback 回调函数，返回操作是否成功
     */
    fun clickByCoordinate(activity: Activity, x: Float, y: Float, callback: (Boolean) -> Unit) {
        try {
            // 获取整个Activity根视图
            val rootView = activity.findViewById<View>(android.R.id.content)
            
            // 创建ACTION_DOWN事件
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
            )
            
            // 创建ACTION_UP事件
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, x, y, 0
            )
            
            // 从根视图分发触摸事件
            val downResult = rootView.dispatchTouchEvent(downEvent)
            Thread.sleep(50) // 短暂延迟模拟真实点击
            val upResult = rootView.dispatchTouchEvent(upEvent)
            
            // 清理事件对象
            downEvent.recycle()
            upEvent.recycle()
            
            callback(downResult && upResult)
            
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 设置输入值
     */
    fun setInputValue(activity: Activity, elementId: String, text: String, callback: (Boolean) -> Unit) {
        val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
        val targetView = findViewByResourceName(rootView, elementId)
        
        if (targetView is EditText) {
            targetView.setText(text)
            callback(true)
        } else if (targetView is TextView) {
            // 尝试设置TextView的文本
            try {
                targetView.text = text
                callback(true)
            } catch (e: Exception) {
                callback(false)
            }
        } else {
            callback(false)
        }
    }
    
    /**
     * 模拟长按操作
     */
    fun longClickElement(activity: Activity, elementId: String, callback: (Boolean) -> Unit) {
        val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
        val targetView = findViewByResourceName(rootView, elementId)
        
        if (targetView != null && targetView.isEnabled && targetView.isLongClickable) {
            val result = targetView.performLongClick()
            callback(result)
        } else {
            callback(false)
        }
    }
    
    /**
     * 根据dp坐标长按屏幕位置，并显示发光效果
     * @param activity Activity对象
     * @param xDp 长按位置的x坐标（dp）
     * @param yDp 长按位置的y坐标（dp）
     * @param callback 长按结果回调
     */
    fun longClickByCoordinateDp(activity: Activity, xDp: Float, yDp: Float, callback: (Boolean) -> Unit) {
        val xPx = UIUtils.dpToPx(activity, xDp)
        val yPx = UIUtils.dpToPx(activity, yDp)-UIUtils.getStatusBarHeight(activity)
        
        // 打印坐标转换信息
        Log.d("longClickByCoordinate", "输入坐标 - xDp: $xDp, yDp: $yDp")
        Log.d("longClickByCoordinate", "转换后并减去系统栏的坐标 - xPx: $xPx, yPx: $yPx")
        Log.d("longClickByCoordinate", "屏幕密度: ${activity.resources.displayMetrics.density}")
        Log.d("longClickByCoordinate", "状态栏高度: ${UIUtils.getStatusBarHeight(activity)}px")
        
        // 显示发光效果（现在使用相同的坐标系统）
        UIUtils.showGlowEffect(activity, xPx, yPx)
        
        // 执行长按操作
        longClickByCoordinate(activity, xPx, yPx, callback)
    }
    
    /**
     * 通过坐标长按元素（px版本）
     * @param activity 当前Activity
     * @param x 长按的X坐标（px单位）
     * @param y 长按的Y坐标（px单位）
     * @param callback 回调函数，返回操作是否成功
     */
    fun longClickByCoordinate(activity: Activity, x: Float, y: Float, callback: (Boolean) -> Unit) {
        try {
            // 获取整个Activity根视图
            val rootView = activity.findViewById<View>(android.R.id.content)
            
            // 创建ACTION_DOWN事件
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
            )
            
            // 分发ACTION_DOWN事件
            val downResult = rootView.dispatchTouchEvent(downEvent)
            
            // 长按需要保持按下状态一段时间（通常500ms以上）
            Thread.sleep(600) // 长按持续时间
            
            // 创建ACTION_UP事件
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, x, y, 0
            )
            
            // 分发ACTION_UP事件
            val upResult = rootView.dispatchTouchEvent(upEvent)
            
            // 清理事件对象
            downEvent.recycle()
            upEvent.recycle()
            
            callback(downResult && upResult)
            
        } catch (e: Exception) {
            Log.e("longClickByCoordinate", "长按操作失败: ${e.message}")
            callback(false)
        }
    }
    
    /**
     * 执行拖拽操作（dp版本）
     * @param activity 当前Activity
     * @param startXDp 起始X坐标（dp单位）
     * @param startYDp 起始Y坐标（dp单位）
     * @param endXDp 结束X坐标（dp单位）
     * @param endYDp 结束Y坐标（dp单位）
     * @param duration 拖拽持续时间（毫秒）
     * @param callback 回调函数，返回操作是否成功
     */
    fun dragByCoordinateDp(
        activity: Activity,
        startXDp: Float,
        startYDp: Float,
        endXDp: Float,
        endYDp: Float,
        duration: Long = 500,
        callback: (Boolean) -> Unit
    ) {
        val startXPx = UIUtils.dpToPx(activity, startXDp)
        val startYPx = UIUtils.dpToPx(activity, startYDp)-UIUtils.getStatusBarHeight(activity)
        val endXPx = UIUtils.dpToPx(activity, endXDp)
        val endYPx = UIUtils.dpToPx(activity, endYDp)-UIUtils.getStatusBarHeight(activity)
        dragByCoordinate(activity, startXPx, startYPx, endXPx, endYPx, duration, callback)
    }
    
    /**
     * 执行拖拽操作（px版本）
     * @param activity 当前Activity
     * @param startX 起始X坐标（px单位）
     * @param startY 起始Y坐标（px单位）
     * @param endX 结束X坐标（px单位）
     * @param endY 结束Y坐标（px单位）
     * @param duration 拖拽持续时间（毫秒）
     * @param callback 回调函数，返回操作是否成功
     */
    fun dragByCoordinate(
        activity: Activity,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 500,
        callback: (Boolean) -> Unit
    ) {
        try {
            // 获取视图容器
            val containerView = activity.findViewById<View>(android.R.id.content)
            
            val downTime = SystemClock.uptimeMillis()
            
            // ACTION_DOWN事件
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0
            )
            containerView.dispatchTouchEvent(downEvent)
            
            // 计算移动步数
            val steps = (duration / 20).toInt() // 每20ms一步
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val currentX = startX + (endX - startX) * progress
                val currentY = startY + (endY - startY) * progress
                
                val moveTime = SystemClock.uptimeMillis()
                val moveEvent = MotionEvent.obtain(
                    downTime, moveTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0
                )
                containerView.dispatchTouchEvent(moveEvent)
                moveEvent.recycle()
                
                Thread.sleep(20) // 每步间隔20ms
            }
            
            // ACTION_UP事件
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0
            )
            containerView.dispatchTouchEvent(upEvent)
            
            // 清理事件对象
            downEvent.recycle()
            upEvent.recycle()
            
            callback(true)
            
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 执行滑动/滚动操作（dp版本）
     * @param activity 当前Activity
     * @param startXDp 起始X坐标（dp单位）
     * @param startYDp 起始Y坐标（dp单位）
     * @param endXDp 结束X坐标（dp单位）
     * @param endYDp 结束Y坐标（dp单位）
     * @param duration 滑动持续时间（毫秒）
     * @param callback 回调函数，返回操作是否成功
     */
    fun scrollByTouchDp(
        activity: Activity,
        startXDp: Float,
        startYDp: Float,
        endXDp: Float,
        endYDp: Float,
        duration: Long = 200,
        callback: (Boolean) -> Unit
    ) {
        val startXPx = UIUtils.dpToPx(activity, startXDp)
        val startYPx = UIUtils.dpToPx(activity, startYDp)
        val endXPx = UIUtils.dpToPx(activity, endXDp)
        val endYPx = UIUtils.dpToPx(activity, endYDp)
        scrollByTouch(activity, startXPx, startYPx, endXPx, endYPx, duration, callback)
    }
    
    /**
     * 执行滑动/滚动操作（px版本）
     * @param activity 当前Activity
     * @param startX 起始X坐标（px单位）
     * @param startY 起始Y坐标（px单位）
     * @param endX 结束X坐标（px单位）
     * @param endY 结束Y坐标（px单位）
     * @param duration 滑动持续时间（毫秒）
     * @param callback 回调函数，返回操作是否成功
     */
    fun scrollByTouch(
        activity: Activity,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 200,
        callback: (Boolean) -> Unit
    ) {
        try {
            // 获取视图容器
            val containerView = activity.findViewById<View>(android.R.id.content)
            
            val downTime = SystemClock.uptimeMillis()
            
            // ACTION_DOWN
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0
            )
            containerView.dispatchTouchEvent(downEvent)
            
            // 快速滑动的MOVE事件序列
            val steps = (duration / 20).toInt() // 每20ms一步
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val currentX = startX + (endX - startX) * progress
                val currentY = startY + (endY - startY) * progress
                
                val moveTime = SystemClock.uptimeMillis()
                val moveEvent = MotionEvent.obtain(
                    downTime, moveTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0
                )
                containerView.dispatchTouchEvent(moveEvent)
                moveEvent.recycle()
                
                Thread.sleep(20) // 每步间隔20ms
            }
            
            // ACTION_UP
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0
            )
            containerView.dispatchTouchEvent(upEvent)
            
            downEvent.recycle()
            upEvent.recycle()
            
            callback(true)
            
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 通过坐标激活输入框并输入文本（dp版本）
     * @param activity 当前Activity
     * @param inputXDp 输入框的X坐标（dp单位）
     * @param inputYDp 输入框的Y坐标（dp单位）
     * @param inputContent 要输入的文本内容
     * @param clearBeforeInput 输入前是否清空现有内容
     * @param callback 回调函数，返回操作是否成功
     */
    fun inputTextByCoordinateDp(
        activity: Activity,
        inputXDp: Float,
        inputYDp: Float,
        inputContent: String,
        clearBeforeInput: Boolean = true,
        callback: (Boolean) -> Unit
    ) {
        val inputXPx = UIUtils.dpToPx(activity, inputXDp)
        val inputYPx = UIUtils.dpToPx(activity, inputYDp)-UIUtils.getStatusBarHeight(activity)
        inputTextByCoordinate(activity, inputXPx, inputYPx, inputContent, clearBeforeInput, callback)
    }
    
    /**
     * 通过坐标激活输入框并输入文本（px版本）
     * @param activity 当前Activity
     * @param inputX 输入框的X坐标（px单位）
     * @param inputY 输入框的Y坐标（px单位）
     * @param inputContent 要输入的文本内容
     * @param clearBeforeInput 输入前是否清空现有内容
     * @param callback 回调函数，返回操作是否成功
     */
    fun inputTextByCoordinate(
        activity: Activity,
        inputX: Float,
        inputY: Float,
        inputContent: String,
        clearBeforeInput: Boolean = true,
        callback: (Boolean) -> Unit
    ) {
        try {
            // 获取视图容器
            val containerView = activity.findViewById<View>(android.R.id.content)
            
            // 使用clickByCoordinate方法点击坐标激活输入框
            clickByCoordinate(activity, inputX, inputY) { clickSuccess ->
                if (!clickSuccess) {
                    callback(false)
                    return@clickByCoordinate
                }
                
                try {
                    // 获取输入法管理器
                    val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    
                    // 给软键盘一些时间弹出
                    Thread.sleep(500)
                    
                    // 处理clearBeforeInput参数
                    if (clearBeforeInput) {
                        // 全选并删除现有文本
                        deleteContent(activity, "TEXT_SELECTED") { _ -> }
                    }
                    
                    // 通过KeyEvent模拟软键盘输入文本
                    for (char in inputContent) {
                        val keyDownTime = SystemClock.uptimeMillis()
                        
                        // 根据字符获取对应的键码
                        val keyCode = when (char) {
                            ' ' -> KeyEvent.KEYCODE_SPACE
                            '\n' -> KeyEvent.KEYCODE_ENTER
                            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
                            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
                            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (char - 'A')
                            else -> KeyEvent.KEYCODE_UNKNOWN
                        }
                        
                        // 创建按键按下事件
                        val downKeyEvent = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            KeyEvent(
                                keyDownTime,
                                keyDownTime,
                                KeyEvent.ACTION_DOWN,
                                keyCode,
                                0,
                                0,
                                0,
                                0,
                                KeyEvent.FLAG_SOFT_KEYBOARD
                            )
                        } else {
                            // 对于特殊字符，使用字符代码
                            KeyEvent(
                                keyDownTime,
                                keyDownTime,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_UNKNOWN,
                                0,
                                0,
                                0,
                                char.code,
                                KeyEvent.FLAG_SOFT_KEYBOARD
                            )
                        }
                        
                        // 分发按键按下事件到根视图
                        containerView.dispatchKeyEvent(downKeyEvent)
                        
                        // 创建按键抬起事件
                        val upKeyEvent = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            KeyEvent(
                                keyDownTime,
                                SystemClock.uptimeMillis(),
                                KeyEvent.ACTION_UP,
                                keyCode,
                                0,
                                0,
                                0,
                                0,
                                KeyEvent.FLAG_SOFT_KEYBOARD
                            )
                        } else {
                            KeyEvent(
                                keyDownTime,
                                SystemClock.uptimeMillis(),
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_UNKNOWN,
                                0,
                                0,
                                0,
                                char.code,
                                KeyEvent.FLAG_SOFT_KEYBOARD
                            )
                        }
                        
                        // 分发按键抬起事件到根视图
                        containerView.dispatchKeyEvent(upKeyEvent)
                        
                        // 短暂延迟模拟真实的打字速度
                        Thread.sleep(100)
                    }
                    
                    callback(true)
                    
                } catch (e: Exception) {
                    callback(false)
                }
            }
            
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 执行后退操作
     * @param activity 当前Activity
     * @param backType 后退类型（SYSTEM_BACK, APP_CUSTOM_BACK）
     * @param callback 回调函数，返回操作是否成功
     */
    fun goBack(activity: Activity, backType: String = "SYSTEM_BACK", callback: (Boolean) -> Unit) {
        try {
            when (backType) {
                "SYSTEM_BACK" -> {
                    // 模拟系统后退键
                    val downTime = SystemClock.uptimeMillis()
                    val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
                    val upEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
                    
                    val rootView = activity.findViewById<View>(android.R.id.content)
                    val downResult = rootView.dispatchKeyEvent(downEvent)
                    val upResult = rootView.dispatchKeyEvent(upEvent)
                    
                    callback(downResult && upResult)
                }
                "APP_CUSTOM_BACK" -> {
                    // 应用自定义后退逻辑
                    activity.onBackPressed()
                    callback(true)
                }
                else -> {
                    // 默认使用系统后退
                    activity.onBackPressed()
                    callback(true)
                }
            }
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 执行删除操作
     * @param activity 当前Activity
     * @param deleteType 删除类型（TEXT_CHAR, TEXT_SELECTED, ITEM_SELECTED）
     * @param deleteCount 删除数量（仅对TEXT_CHAR有效）
     * @param callback 回调函数，返回操作是否成功
     */
    fun deleteContent(
        activity: Activity,
        deleteType: String,
        deleteCount: Int = 1,
        callback: (Boolean) -> Unit
    ) {
        try {
            // 获取整个Activity根视图
            val rootView = activity.findViewById<View>(android.R.id.content)
            
            when (deleteType) {
                "TEXT_CHAR" -> {
                    // 删除指定数量的字符
                    var allSuccess = true
                    for (i in 1..deleteCount) {
                        val downTime = SystemClock.uptimeMillis()
                        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0)
                        val upEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0)
                        
                        // 分发按键事件到根视图
                        val downResult = rootView.dispatchKeyEvent(downEvent)
                        val upResult = rootView.dispatchKeyEvent(upEvent)
                        
                        if (!downResult || !upResult) {
                            allSuccess = false
                        }
                        
                        Thread.sleep(100) // 字符间间隔
                    }
                    callback(allSuccess)
                }
                "TEXT_SELECTED" -> {
                    // 删除选中文本
                    val downTime = SystemClock.uptimeMillis()
                    val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0)
                    val upEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0)
                    
                    val downResult = rootView.dispatchKeyEvent(downEvent)
                    val upResult = rootView.dispatchKeyEvent(upEvent)
                    
                    callback(downResult && upResult)
                }
                "ITEM_SELECTED" -> {
                    // 删除选中项
                    val downTime = SystemClock.uptimeMillis()
                    val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0)
                    val upEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0)
                    
                    val downResult = rootView.dispatchKeyEvent(downEvent)
                    val upResult = rootView.dispatchKeyEvent(upEvent)
                    
                    callback(downResult && upResult)
                }
                else -> {
                    callback(false)
                }
            }
            
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 复制内容到剪贴板
     * @param activity 当前Activity
     * @param text 要复制的文本内容
     * @param label 剪贴板标签（可选）
     * @param callback 回调函数，返回操作是否成功
     */
    fun copyToClipboard(
        activity: Activity,
        text: String,
        label: String = "copied_text",
        callback: (Boolean) -> Unit
    ) {
        try {
            val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clipData)
            
            // 验证复制是否成功
            val copiedText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            val success = copiedText == text
            
            callback(success)
        } catch (e: Exception) {
            callback(false)
        }
    }
    
    /**
     * 从剪贴板粘贴内容（dp版本）
     * @param activity 当前Activity
     * @param targetCoordinateXDp 目标坐标X（dp单位，可选，用于坐标粘贴）
     * @param targetCoordinateYDp 目标坐标Y（dp单位，可选，用于坐标粘贴）
     * @param pasteMode 粘贴模式（FOCUS_PASTE, COORDINATE_PASTE）
     * @param callback 回调函数，返回粘贴的内容和操作是否成功
     */
    fun pasteFromClipboardDp(
        activity: Activity,
        targetCoordinateXDp: Float? = null,
        targetCoordinateYDp: Float? = null,
        pasteMode: String = "FOCUS_PASTE",
        callback: (String?, Boolean) -> Unit
    ) {
        val targetCoordinateXPx = targetCoordinateXDp?.let { UIUtils.dpToPx(activity, it) }
        val targetCoordinateYPx = targetCoordinateYDp?.let { UIUtils.dpToPx(activity, it) }
        pasteFromClipboard(activity, targetCoordinateXPx, targetCoordinateYPx, pasteMode, callback)
    }
    
    /**
     * 从剪贴板粘贴内容（px版本）
     * @param activity 当前Activity
     * @param targetCoordinateX 目标坐标X（px单位，可选，用于坐标粘贴）
     * @param targetCoordinateY 目标坐标Y（px单位，可选，用于坐标粘贴）
     * @param pasteMode 粘贴模式（FOCUS_PASTE, COORDINATE_PASTE）
     * @param callback 回调函数，返回粘贴的内容和操作是否成功
     */
    fun pasteFromClipboard(
        activity: Activity,
        targetCoordinateX: Float? = null,
        targetCoordinateY: Float? = null,
        pasteMode: String = "FOCUS_PASTE",
        callback: (String?, Boolean) -> Unit
    ) {
        try {
            val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            // 检查剪贴板是否有内容
            if (!clipboardManager.hasPrimaryClip()) {
                callback(null, false)
                return
            }
            
            val clipData = clipboardManager.primaryClip
            val clipText = clipData?.getItemAt(0)?.text?.toString()
            
            if (clipText.isNullOrEmpty()) {
                callback(null, false)
                return
            }
            
            when (pasteMode) {
                "FOCUS_PASTE" -> {
                    // 在当前焦点位置粘贴
                    val rootView = activity.findViewById<View>(android.R.id.content)
                    
                    // 模拟Ctrl+V粘贴
                    val downTime = SystemClock.uptimeMillis()
                    val ctrlDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0)
                    val vDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                    val vUpEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                    val ctrlUpEvent = KeyEvent(downTime, downTime + 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)
                    
                    val result1 = rootView.dispatchKeyEvent(ctrlDownEvent)
                    val result2 = rootView.dispatchKeyEvent(vDownEvent)
                    val result3 = rootView.dispatchKeyEvent(vUpEvent)
                    val result4 = rootView.dispatchKeyEvent(ctrlUpEvent)
                    
                    val success = result1 && result2 && result3 && result4
                    callback(clipText, success)
                }
                "COORDINATE_PASTE" -> {
                    // 在指定坐标位置粘贴
                    if (targetCoordinateX != null && targetCoordinateY != null) {
                        // 先点击目标坐标激活输入
                        clickByCoordinate(activity, targetCoordinateX, targetCoordinateY) { clickSuccess ->
                            if (clickSuccess) {
                                // 等待输入框激活
                                Thread.sleep(300)
                                
                                // 执行粘贴操作
                                val rootView = activity.findViewById<View>(android.R.id.content)
                                val downTime = SystemClock.uptimeMillis()
                                val ctrlDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0)
                                val vDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                                val vUpEvent = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                                val ctrlUpEvent = KeyEvent(downTime, downTime + 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)
                                
                                val result1 = rootView.dispatchKeyEvent(ctrlDownEvent)
                                val result2 = rootView.dispatchKeyEvent(vDownEvent)
                                val result3 = rootView.dispatchKeyEvent(vUpEvent)
                                val result4 = rootView.dispatchKeyEvent(ctrlUpEvent)
                                
                                val success = result1 && result2 && result3 && result4
                                callback(clipText, success)
                            } else {
                                callback(clipText, false)
                            }
                        }
                    } else {
                        callback(clipText, false)
                    }
                }
                else -> {
                    callback(clipText, false)
                }
            }
            
        } catch (e: Exception) {
            callback(null, false)
        }
    }
    
    /**
     * 返回APP主页
     * @param activity 当前Activity
     * @param callback 回调函数，返回操作是否成功
     */
    fun goToAppHome(activity: Activity, callback: (Boolean) -> Unit) {
        try {
            Log.d("NativeController", "开始执行返回APP主页操作")
            // 调用返回主页函数
            val mainIntent = Intent(activity, MainActivity::class.java)
            mainIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            mainIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            Log.d("NativeController", "准备启动MainActivity")
            activity.startActivity(mainIntent)
            Log.d("NativeController", "已成功启动MainActivity")
            
            callback(true)
        } catch (e: Exception) {
            Log.e("NativeController", "返回APP主页操作失败", e)
            callback(false)
        }
    }
    
    /**
     * 通过资源名称查找视图
     */
    private fun findViewByResourceName(view: View, resourceName: String): View? {
        if (view.id != View.NO_ID) {
            try {
                val name = view.resources.getResourceEntryName(view.id)
                if (name == resourceName) {
                    return view
                }
            } catch (e: Exception) {
                // 忽略资源找不到的异常
            }
        }
        
        // 也尝试通过生成的ID匹配
        val generatedId = "view_${view.hashCode()}"
        if (generatedId == resourceName) {
            return view
        }
        
        if (view is ViewGroup && isContainerTraversable(view)) {
            for (i in 0 until view.childCount) {
                val result = findViewByResourceName(view.getChildAt(i), resourceName)
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
    }
    
    /**
     * 判断容器是否可遍历
     */
    private fun isContainerTraversable(view: View): Boolean {
        return view.visibility == View.VISIBLE && view.isShown && view.alpha > 0f
    }
    
    /**
     * 判断视图是否实际可见
     */
    private fun isActuallyVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (!view.isShown) return false
        if (view.alpha <= 0f) return false
        if (view.width <= 0 || view.height <= 0) return false
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rect.width() > 0 && rect.height() > 0
    }
    
    /**
     * 更准确地判断视图是否真正可点击
     * 解决 ListView 中 LinearLayout 容器 clickable 属性获取不准确的问题
     * @param view 要检测的视图
     * @return 视图是否真正可点击
     */
    private fun isViewActuallyClickable(view: View): Boolean {
        // 首先检查基本的 clickable 属性和启用状态
        if (!view.isClickable || !view.isEnabled) {
            return false
        }
        
        // 对于特定类型的视图，使用专门的检测逻辑
        when (view) {
            is ListView -> {
                // ListView本身需要检查是否有OnItemClickListener
                return isListViewClickable(view)
            }
            is Button, is ImageButton -> {
                // 按钮类型通常可信任其clickable属性
                return true
            }
            is TextView -> {
                // TextView需要检查是否有实际的点击监听器
                return hasActualClickListener(view)
            }
            is ImageView -> {
                // ImageView需要检查是否有实际的点击监听器
                return hasActualClickListener(view)
            }
        }
        
        // 检查视图是否有实际的点击监听器
        // 使用反射检查 hasOnClickListeners() 方法（API 15+）
        try {
            val hasOnClickListenersMethod = View::class.java.getDeclaredMethod("hasOnClickListeners")
            hasOnClickListenersMethod.isAccessible = true
            val hasListeners = hasOnClickListenersMethod.invoke(view) as Boolean
            
            // 如果没有点击监听器，进一步检查是否为特殊容器
            if (!hasListeners) {
                return isSpecialClickableContainer(view)
            }
            
            return hasListeners
        } catch (e: Exception) {
            // 如果反射失败，使用备用检测方法
            return isClickableByFallbackMethod(view)
        }
    }
    
    /**
     * 检查ListView是否真正可点击
     * @param listView 要检测的ListView
     * @return 是否可点击
     */
    private fun isListViewClickable(listView: ListView): Boolean {
        try {
            val onItemClickListenerField = ListView::class.java.getDeclaredField("mOnItemClickListener")
            onItemClickListenerField.isAccessible = true
            val listener = onItemClickListenerField.get(listView)
            return listener != null
        } catch (e: Exception) {
            // 如果反射失败，保守地返回false，因为ListView本身通常不应该被直接点击
            return false
        }
    }
    
    /**
     * 检查视图是否有实际的点击监听器
     * @param view 要检测的视图
     * @return 是否有点击监听器
     */
    private fun hasActualClickListener(view: View): Boolean {
        try {
            val hasOnClickListenersMethod = View::class.java.getDeclaredMethod("hasOnClickListeners")
            hasOnClickListenersMethod.isAccessible = true
            return hasOnClickListenersMethod.invoke(view) as Boolean
        } catch (e: Exception) {
            // 如果反射失败，假设有监听器（保守策略）
            return true
        }
    }
    
    /**
     * 检查是否为特殊的可点击容器
     * 某些容器虽然没有直接的点击监听器，但仍然应该被认为是可点击的
     * @param view 要检测的视图
     * @return 是否为特殊可点击容器
     */
    private fun isSpecialClickableContainer(view: View): Boolean {
        val parent = view.parent
        
        // 对于ListView本身，需要检查是否真的有点击监听器
        if (view is ListView) {
            // ListView本身通常不应该被认为是可点击的，除非明确设置了OnItemClickListener
            try {
                val onItemClickListenerField = ListView::class.java.getDeclaredField("mOnItemClickListener")
                onItemClickListenerField.isAccessible = true
                val listener = onItemClickListenerField.get(view)
                return listener != null
            } catch (e: Exception) {
                // 如果反射失败，保守地返回false
                return false
            }
        }
        
        // 检查是否为 ListView 中的项目容器（直接子项）
        if (parent is ListView) {
            // ListView 中的直接子项通常是可点击的
            return true
        }
        
        // 检查是否为 RecyclerView 中的项目容器
        if (parent != null && parent.javaClass.name.contains("RecyclerView")) {
            return true
        }
        
        // 检查是否为具有特定类名的可点击容器
        val className = view.javaClass.simpleName
        if (className.contains("Item") || className.contains("Row") || className.contains("Cell")) {
            return view.isClickable
        }
        
        // 对于 LinearLayout 等容器，如果在 ListView 中且设置了 clickable，需要进一步验证
        if (view is LinearLayout || view is RelativeLayout || view is FrameLayout) {
            // 检查父容器链中是否有 ListView 或 RecyclerView
            var currentParent = view.parent
            var depth = 0
            while (currentParent != null && depth < 5) { // 限制检查深度避免无限循环
                if (currentParent is ListView || 
                    currentParent.javaClass.name.contains("RecyclerView")) {
                    // 在列表容器中的布局容器，如果没有实际监听器，通常不应该被认为是可点击的
                    return false
                }
                currentParent = currentParent.parent
                depth++
            }
        }
        
        return view.isClickable
    }
    
    /**
     * 备用的 clickable 检测方法
     * 当反射方法失败时使用
     * @param view 要检测的视图
     * @return 是否可点击
     */
    private fun isClickableByFallbackMethod(view: View): Boolean {
        // 对于某些已知的可点击控件类型，直接返回 true
        when (view) {
            is Button, is ImageButton -> return view.isClickable && view.isEnabled
            is TextView -> {
                // TextView 如果设置了 clickable 且有文本，通常是真正可点击的
                return view.isClickable && view.text.isNotEmpty()
            }
            is ImageView -> {
                // ImageView 如果设置了 clickable 且有图片，通常是真正可点击的
                return view.isClickable && view.drawable != null
            }
        }
        
        // 对于容器类型，使用特殊检测逻辑
        if (view is ViewGroup) {
            return isSpecialClickableContainer(view)
        }
        
        // 默认情况下，相信 isClickable 的结果，但要求视图必须是启用状态
        return view.isClickable && view.isEnabled
    }
}