package controller

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.io.File
import java.io.FileWriter

object ElementController {
    private const val TAG = "ElementController"
    fun getCurrentElementTree(activity: Activity, callback: (GenericElement) -> Unit) {
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.getElementTree(activity) { elementTree ->
                    callback(elementTree)
                }
            }
            PageSniffer.PageType.WEB_VIEW -> {
                val webView = findWebView(activity)
                if (webView != null) {
                    WebViewController.getElementTree(webView) { elementTree ->
                        callback(elementTree)
                    }
                } else {
                    callback(createErrorElement("未找到WebView"))
                }
            }
            else -> {
                AccessibilityController.getElementTree(activity) { elementTree ->
                    callback(convertToGenericElement(elementTree))
                }
            }
        }
    }
    
    fun clickElement(activity: Activity, elementId: String, callback: (Boolean) -> Unit) {
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.clickElement(activity, elementId, callback)
            }
            PageSniffer.PageType.WEB_VIEW -> {
                val webView = findWebView(activity)
                if (webView != null) {
                    WebViewController.clickElement(webView, elementId, callback)
                } else {
                    callback(false)
                }
            }
            else -> {
                AccessibilityController.clickElement(activity, elementId, callback)
            }
        }
    }

    /**
     * 使用GenericElement中的view引用直接进行点击操作
     * @param element 包含view引用的GenericElement对象
     * @param callback 点击操作的回调函数
     */
    fun clickElementByView(element: GenericElement, callback: (Boolean) -> Unit) {
        try {
            val view = element.view
            if (view != null && view.isClickable && view.isEnabled) {
                Log.d(TAG, "开始执行view引用点击操作")
                
                // 使用Handler确保回调一定会被执行
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var callbackExecuted = false
                
                // 设置超时处理，如果3秒内没有执行回调，则认为失败
                val timeoutRunnable = Runnable {
                    if (!callbackExecuted) {
                        callbackExecuted = true
                        Log.w(TAG, "view.post点击操作超时，执行失败回调")
                        callback(false)
                    }
                }
                handler.postDelayed(timeoutRunnable, 3000)
                
                // 在主线程中执行点击操作
                val postResult = view.post {
                    try {
                        if (!callbackExecuted) {
                            Log.d(TAG, "执行view.performClick()")
                            val success = view.performClick()
                            callbackExecuted = true
                            handler.removeCallbacks(timeoutRunnable)
                            Log.d(TAG, "view.performClick()结果: $success")
                            callback(success)
                        }
                    } catch (e: Exception) {
                        if (!callbackExecuted) {
                            callbackExecuted = true
                            handler.removeCallbacks(timeoutRunnable)
                            Log.e(TAG, "点击操作失败: ${e.message}")
                            callback(false)
                        }
                    }
                }
                
                // 如果view.post返回false，说明无法将任务添加到消息队列
                if (!postResult) {
                    if (!callbackExecuted) {
                        callbackExecuted = true
                        handler.removeCallbacks(timeoutRunnable)
                        Log.w(TAG, "view.post返回false，无法添加到消息队列")
                        callback(false)
                    }
                }
                
            } else {
                Log.w(TAG, "View不可点击或不可用: view=${view}, clickable=${view?.isClickable}, enabled=${view?.isEnabled}")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "点击元素时发生异常: ${e.message}")
            callback(false)
        }
    }
    fun clickByCoordinateDp(activity: Activity, xDp: Float, yDp: Float, callback: (Boolean) -> Unit) {
        // 使用NativeController的坐标点击功能
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.clickByCoordinateDp(activity, xDp.toFloat(), yDp.toFloat()) { success ->
                    callback(success)
                }
            }
            else -> {
                // 对于其他类型，尝试使用ElementController
                Log.d(TAG,"其他类型页面进行坐标点击")
            }
        }
    }

    fun setInputValue(activity: Activity, elementId: String, text: String, callback: (Boolean) -> Unit) {
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.setInputValue(activity, elementId, text, callback)
            }
            PageSniffer.PageType.WEB_VIEW -> {
                val webView = findWebView(activity)
                if (webView != null) {
                    WebViewController.setInputValue(webView, elementId, text, callback)
                } else {
                    callback(false)
                }
            }
            else -> {
                AccessibilityController.setInputValue(activity, elementId, text, callback)
            }
        }
    }

    /**
     * 模拟长按操作
     */
    fun longClickElement(activity: Activity, elementId: String, callback: (Boolean) -> Unit) {
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.longClickElement(activity, elementId, callback)
            }
            PageSniffer.PageType.WEB_VIEW -> {
                val webView = findWebView(activity)
                if (webView != null) {
                    WebViewController.longClickElement(webView, elementId, callback)
                } else {
                    callback(false)
                }
            }
            else -> {
                AccessibilityController.longClickElement(activity, elementId, callback)
            }
        }
    }


    private fun String.escapeXml(): String {
        return this.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    
    private fun findWebView(activity: Activity): WebView? {
        val rootView = activity.window.decorView.findViewById<View>(android.R.id.content)
        return findWebViewRecursive(rootView)
    }
    
    private fun findWebViewRecursive(view: View): WebView? {
        // 仅返回实际可见的 WebView
        if (view is WebView && isActuallyVisible(view)) {
            return view
        }
        if (view is ViewGroup && isContainerTraversable(view)) {
            for (i in 0 until view.childCount) {
                val result = findWebViewRecursive(view.getChildAt(i))
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
    


    private fun isContainerTraversable(view: View): Boolean {
        return view.visibility == View.VISIBLE && view.isShown && view.alpha > 0f
    }

    private fun isActuallyVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (!view.isShown) return false
        if (view.alpha <= 0f) return false
        if (view.width <= 0 || view.height <= 0) return false
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rect.width() > 0 && rect.height() > 0
    }
    
    private fun createErrorElement(message: String): GenericElement {
        return GenericElement(
            resourceId = "error",
            className = "Error",
            text = message,
            contentDesc = "",
            bounds = Rect(),
            important = false,
            enabled = false,
            checked = false,
            clickable = false,
            checkable = false,
            scrollable = false,
            longClickable = false,
            selected = false,
            index = 0,
            naf = false,
            additionalProps = emptyMap(),
            children = emptyList(),
            view = null  // Error元素没有对应的View
        )
    }
    
    /**
     * 将ElementTree转换为GenericElement
     */
    private fun convertToGenericElement(elementTree: ElementTree): GenericElement {
        var indexCounter = 0
        
        return GenericElement(
            resourceId = "root",
            className = elementTree.type,
            text = "",
            contentDesc = "",
            bounds = Rect(),
            important = true,
            enabled = true,
            checked = false,
            clickable = false,
            checkable = false,
            scrollable = false,
            longClickable = false,
            selected = false,
            index = indexCounter++,
            naf = false,
            additionalProps = mapOf("pageType" to elementTree.type),
            children = elementTree.elements.map { element ->
                GenericElement(
                    resourceId = element.id,
                    className = element.type,
                    text = element.text ?: "",
                    contentDesc = "",
                    bounds = Rect(),
                    important = true,
                    enabled = element.enabled,
                    checked = false,
                    clickable = element.clickable,
                    checkable = false,
                    scrollable = false,
                    longClickable = false,
                    selected = false,
                    index = indexCounter++,
                    naf = false,
                    additionalProps = mapOf(
                        "bounds" to element.bounds,
                        "clickable" to element.clickable.toString(),
                        "focusable" to element.focusable.toString()
                    ),
                    children = emptyList(),
                    view = null  // AccessibilityController生成的元素没有直接的View引用
                )
            },
            view = null  // ElementTree根元素没有直接的View引用
        )
    }
}