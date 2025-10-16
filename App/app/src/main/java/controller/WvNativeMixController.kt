package controller

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * 混合页面控制器（WebView + Native）
 * 聚焦三个功能：元素树获取、点击、文本输入
 * 参考 NativeController 与 WebViewController 的实现，统一对混合页面进行处理。
 */
object WvNativeMixController {
    private const val TAG = "WvNativeMixController"

    /**
     * 获取混合页面的元素树（GenericElement）
     * - Native 部分：复用 NativeController
     * - Web 部分：查找可见 WebView，复用 WebViewController，并将其作为一个容器节点嵌入
     * - 坐标单位：
     *   - NativeController 已返回 dp 坐标
     *   - WebViewController 返回 px 坐标，这里转换为 dp，以统一单位
     */
    fun getElementTree(activity: Activity, callback: (GenericElement) -> Unit) {
        try {
            val webView = findWebView(activity)

            // 没有 WebView，直接返回原生树
            if (webView == null) {
                NativeController.getElementTree(activity) { nativeTree ->
                    callback(tagTreeWithPageType(nativeTree, "Native"))
                }
                return
            }

            // 同时获取 Native 与 Web 树，随后合并
            NativeController.getElementTree(activity) { nativeTree ->
                WebViewController.getElementTree(webView) { webTree ->
                    val merged = mergeNativeAndWebTrees(activity, nativeTree, webView, webTree)
                    callback(merged)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取混合元素树失败: ${e.message}")
            callback(createErrorElement("获取混合元素树失败: ${e.message}"))
        }
    }

    /**
     * 点击元素（优先尝试原生，失败后回退到 WebView）
     * elementId：
     * - 原生：资源 entryName
     * - WebView：DOM 元素的 id
     */
    fun clickElement(activity: Activity, elementId: String, callback: (Boolean) -> Unit, pageType: String? = null) {
        try {
            when (pageType) {
                "Native" -> {
                    NativeController.clickElement(activity, elementId) { callback(it) }
                }
                "WebView" -> {
                    val webView = findWebView(activity)
                    if (webView != null) {
                        WebViewController.clickElement(webView, elementId) { callback(it) }
                    } else {
                        callback(false)
                    }
                }
                else -> {
                    // 未指定页面类型时输出Log
                    Log.d(TAG, "点击元素失败: 节点未指定页面类型")
                    callback(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "点击元素失败: ${e.message}")
            callback(false)
        }
    }

    /**
     * 文本输入（优先尝试原生，失败后回退到 WebView）
     * 接收完整的元素对象，ID 转换下沉到各自的底层控制器。
     */
    fun setInputValue(activity: Activity, element: GenericElement, text: String, callback: (Boolean) -> Unit) {
        try {
            when (element.pageType) {
                "Native" -> {
                    NativeController.setInputValue(activity, element, text) { callback(it) }
                }
                "WebView" -> {
                    val webView = findWebView(activity)
                    if (webView != null) {
                        WebViewController.setInputValue(webView, element, text) { callback(it) }
                    } else {
                        callback(false)
                    }
                }
                else -> {
                    Log.d(TAG, "设置输入值失败: 节点未指定页面类型 pageType='${element.pageType}'")
                    callback(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置输入值失败: ${e.message}")
            callback(false)
        }
    }


    /**
     * 使用元素进行坐标点击（dp单位）
     * 坐标计算下沉到底层控制器，且根据元素的 pageType 进行判断
     */
    fun clickByCoordinateDp(activity: Activity, element: GenericElement, callback: (Boolean) -> Unit) {
        try {
            when (element.pageType) {
                "Native" -> {
                    // 交由 NativeController 处理并在其内部计算坐标
                    Log.d(TAG, "节点为Native节点，分发给NativeController")
                    NativeController.clickByCoordinateDp(activity, element) { callback(it) }
                }
                "WebView" -> {
                    Log.d(TAG, "节点为WebView节点，分发给WebViewController")
                    val webView = findWebView(activity)
                    
                     if (webView != null) {
                         // 将元素直接传递给 WebViewController，由其计算坐标
                         WebViewController.clickByCoordinateDp(activity,webView, element) { callback(it) }
                     } else {
                         Log.e(TAG, "点击失败：未找到WebView的view块")
                         callback(false)
                     }
                }
                else -> {
                    Log.d(TAG, "点击失败：元素未指定有效的页面类型 pageType='${element.pageType}'")
                    callback(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "元素坐标点击失败: ${e.message}")
            callback(false)
        }
    }

    // ---------------------- 辅助方法 ----------------------

    private fun mergeNativeAndWebTrees(
        activity: Activity,
        nativeRoot: GenericElement,
        webView: WebView,
        webRoot: GenericElement
    ): GenericElement {
        var indexCounter = 0

        fun pxRectToDp(rect: Rect): Rect {
            return Rect(
                UIUtils.pxToDp(activity, rect.left.toFloat()).toInt(),
                UIUtils.pxToDp(activity, rect.top.toFloat()).toInt(),
                UIUtils.pxToDp(activity, rect.right.toFloat()).toInt(),
                UIUtils.pxToDp(activity, rect.bottom.toFloat()).toInt()
            )
        }

        // WebView 的 JS 返回的是 CSS 像素的视口坐标；在 Android 上 CSS px ~ dp 的语义一致，
        // 为避免二次缩放，这里直接按 dp 使用（不再除以 density）。
        fun cssRectToDp(rect: Rect): Rect {
            return Rect(rect.left, rect.top, rect.right, rect.bottom)
        }

        fun transformWithSource(element: GenericElement, source: String, convertBoundsToDp: Boolean): GenericElement {
            val newChildren = element.children.map { child ->
                transformWithSource(child, source, convertBoundsToDp)
            }
            val newProps = element.additionalProps.toMutableMap().apply { put("source", source) }
            // native: 已是 dp；web: CSS px 直接按 dp 使用，避免误除 density
            val newBounds = if (convertBoundsToDp) cssRectToDp(element.bounds) else element.bounds

            return GenericElement(
                resourceId = element.resourceId,
                className = element.className,
                text = element.text,
                contentDesc = element.contentDesc,
                bounds = newBounds,
                important = element.important,
                enabled = element.enabled,
                checked = element.checked,
                clickable = element.clickable,
                checkable = element.checkable,
                scrollable = element.scrollable,
                longClickable = element.longClickable,
                selected = element.selected,
                index = indexCounter++,
                naf = element.naf,
                pageType = if (source == "native") "Native" else "WebView",
                additionalProps = newProps,
                children = newChildren,
                view = element.view
            )
        }

        // 变换两个子树，统一 index 与 source 标记
        val nativeTransformed = transformWithSource(nativeRoot, "native", convertBoundsToDp = false)
        val webTransformed = transformWithSource(webRoot, "web", convertBoundsToDp = true)

        // 创建 WebView 容器节点，将 Web 树挂载到该容器下
        val loc = IntArray(2)
        webView.getLocationOnScreen(loc)
        val webBoundsDp = Rect(
            UIUtils.pxToDp(activity, loc[0].toFloat()).toInt(),
            UIUtils.pxToDp(activity, loc[1].toFloat()).toInt(),
            UIUtils.pxToDp(activity, (loc[0] + webView.width).toFloat()).toInt(),
            UIUtils.pxToDp(activity, (loc[1] + webView.height).toFloat()).toInt()
        )

        val webContainerProps = mapOf(
            "source" to "web",
            "container" to "WebView"
        )

        val webContainer = GenericElement(
            resourceId = "webview_${webView.hashCode()}",
            className = webView.javaClass.name,
            text = "",
            contentDesc = webView.contentDescription?.toString() ?: "",
            bounds = webBoundsDp,
            important = webView.isImportantForAccessibility,
            enabled = webView.isEnabled,
            checked = false,
            clickable = webView.isClickable,
            checkable = false,
            scrollable = webView.canScrollHorizontally(1) || webView.canScrollHorizontally(-1) ||
                    webView.canScrollVertically(1) || webView.canScrollVertically(-1),
            longClickable = webView.isLongClickable,
            selected = webView.isSelected,
            index = indexCounter++,
            naf = false,
            pageType = "WebView",
            additionalProps = webContainerProps,
            children = listOf(webTransformed),
            view = webView
        )

        // 顶层混合根节点
        return GenericElement(
            resourceId = "root_mix",
            className = "MixedPage",
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
            pageType = "Mixed",
            additionalProps = emptyMap(),
            children = listOf(nativeTransformed, webContainer),
            view = null
        )
    }

    /**
     * 为整棵元素树统一设置页面类型标记
     */
    private fun tagTreeWithPageType(root: GenericElement, pageType: String): GenericElement {
        val newChildren = root.children.map { child -> tagTreeWithPageType(child, pageType) }
        return GenericElement(
            resourceId = root.resourceId,
            className = root.className,
            text = root.text,
            contentDesc = root.contentDesc,
            bounds = root.bounds,
            important = root.important,
            enabled = root.enabled,
            checked = root.checked,
            clickable = root.clickable,
            checkable = root.checkable,
            scrollable = root.scrollable,
            longClickable = root.longClickable,
            selected = root.selected,
            index = root.index,
            naf = root.naf,
            pageType = pageType,
            additionalProps = root.additionalProps,
            children = newChildren,
            view = root.view
        )
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
            view = null
        )
    }
}