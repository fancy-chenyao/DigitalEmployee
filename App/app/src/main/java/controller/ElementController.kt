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
                Log.d(TAG, "当前页面为Native页面")
                NativeController.getElementTree(activity) { elementTree ->
                    callback(elementTree)
                }
            }
            PageSniffer.PageType.WEB_VIEW -> {
                Log.d(TAG, "当前页面为WebView页面")
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
                
                // 在主线程中执行点击操作
                view.post {
                    try {
                        Log.d(TAG, "执行view.performClick()")
                        view.performClick()
                        Log.d(TAG, "view.performClick()结果: true")
                        callback(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "点击操作失败: ${e.message}")
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
            PageSniffer.PageType.WEB_VIEW -> {
                val webView = findWebView(activity)
                if (webView != null) {
                    WebViewController.clickByCoordinateDp(webView, xDp.toFloat(), yDp.toFloat()) { success ->
                        if (!success) {
                            // WebView坐标点击失败
                            Log.e(TAG, "WebView坐标点击失败")
                            callback(false)
                        } else {
                            callback(true)
                        }
                    }
                } else {
                    // 未找到WebView时,报错
                    Log.e(TAG, "页面识别为WebView，但是未找到WebView")
                    callback(false)
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

    /**
     * 使用GenericElement中的view引用直接进行长按操作
     * @param element 包含view引用的GenericElement对象
     * @param callback 长按操作的回调函数
     */
    fun longClickElementByView(element: GenericElement, callback: (Boolean) -> Unit) {
        try {
            val view = element.view
            if (view != null && view.isLongClickable && view.isEnabled) {
                Log.d(TAG, "开始执行view引用长按操作")
                
                // 在主线程中执行长按操作
                view.post {
                    try {
                        Log.d(TAG, "执行view.performLongClick()")
                        val result = view.performLongClick()
                        Log.d(TAG, "view.performLongClick()结果: $result")
                        callback(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "长按操作失败: ${e.message}")
                        callback(false)
                    }
                }
            } else {
                Log.w(TAG, "View不可长按或不可用: view=${view}, longClickable=${view?.isLongClickable}, enabled=${view?.isEnabled}")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "长按元素时发生异常: ${e.message}")
            callback(false)
        }
    }

    /**
     * 通过坐标进行长按操作（dp版本）
     * @param activity 当前Activity
     * @param xDp 长按的X坐标（dp单位）
     * @param yDp 长按的Y坐标（dp单位）
     * @param callback 回调函数，返回操作是否成功
     */
    fun longClickByCoordinateDp(activity: Activity, xDp: Float, yDp: Float, callback: (Boolean) -> Unit) {
        // 使用NativeController的坐标长按功能
        when (PageSniffer.getCurrentPageType(activity)) {
            PageSniffer.PageType.NATIVE -> {
                NativeController.longClickByCoordinateDp(activity, xDp.toFloat(), yDp.toFloat()) { success ->
                    callback(success)
                }
            }
            else -> {
                // 对于其他类型，尝试使用ElementController
                Log.d(TAG,"其他类型页面进行坐标长按")
                // 可以在这里添加其他页面类型的坐标长按支持
                callback(false)
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