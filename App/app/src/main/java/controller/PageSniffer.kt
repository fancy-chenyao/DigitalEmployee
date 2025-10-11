package controller

import android.R
import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.lang.reflect.Field

object PageSniffer {
    enum class PageType {
        NATIVE, WEB_VIEW, UNKNOWN
    }

    fun getCurrentPageType(activity: Activity): PageType {
        val rootView = activity.window.decorView.findViewById<View>(R.id.content)

        // 基于视图/类/字段特征判定（与具体Activity无关，通用）
        return when {
            findWebView(rootView) -> PageType.WEB_VIEW // 只要包含一个WebView组件，就认为是WebView
            hasVisibleNativeControls(rootView) -> PageType.NATIVE // 仅当所有控件都是原生控件时才认为页面为Native
            else -> PageType.UNKNOWN
        }
    }

    private fun findWebView(view: View): Boolean {
        // 仅当 WebView 实际可见时才认为是 Web 页面
        if (view is WebView && isActuallyVisible(view)) {
            return true
        }
        if (view is ViewGroup && isContainerTraversable(view)) {
            for (i in 0 until view.childCount) {
                if (findWebView(view.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }


    /**
     * 获取当前页面的详细技术栈信息
     */
    fun getPageTechStack(activity: Activity): Map<String, Any> {
        val rootView = activity.window.decorView.findViewById<View>(R.id.content)
        val result = mutableMapOf<String, Any>()

        result["pageType"] = getCurrentPageType(activity).name

        // 收集视图树中的技术栈特征
        val techSignatures = mutableSetOf<String>()
        collectTechSignatures(rootView, techSignatures)
        result["techSignatures"] = techSignatures.toList()

        return result
    }

    private fun collectTechSignatures(view: View, signatures: MutableSet<String>) {
        // 添加当前视图的类名作为特征
        signatures.add(view.javaClass.name)

        // 递归处理子视图
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTechSignatures(view.getChildAt(i), signatures)
            }
        }
    }

    /**
     * 检查容器是否可遍历
     */
    private fun isContainerTraversable(view: View): Boolean {
        return view.visibility == View.VISIBLE && view.isShown && view.alpha > 0f
    }
    
    /**
     * 检查视图是否实际可见
     */
    private fun isActuallyVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (!view.isShown) return false
        if (view.alpha <= 0f) return false
        if (view.width <= 0 || view.height <= 0) return false
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rect.width() > 0 && rect.height() > 0
    }
    
    /**
     * 检查是否有可见的原生Android控件
     * 基于Android原生技术栈的特征进行识别
     */
    private fun hasVisibleNativeControls(view: View): Boolean {
        // 放宽可见性要求
        if (view.visibility != View.VISIBLE || !view.isShown) {
            return false
        }
        
        // 方法1: 检查是否是Android原生控件
        val isNativeControl = when (view) {
            is Button -> true
            is EditText -> true
            is CheckBox -> true
            is CompoundButton -> true
            is SeekBar -> true
            is TextView -> true
            is ImageView -> true
            is Switch -> true
            is RadioButton -> true
            is Spinner -> true
            else -> false
        }
        if (isNativeControl) return true

        // 方法2: 检查类名是否属于Android原生技术栈
        val className = view.javaClass.name
        if (className.startsWith("android.widget.") || 
            className.startsWith("android.view.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.google.android.material.")) {
            return true
        }

        // 方法3: 递归检查子视图
        if (view is ViewGroup && isContainerTraversable(view)) {
            for (i in 0 until view.childCount) {
                if (hasVisibleNativeControls(view.getChildAt(i))) return true
            }
        }
        return false
    }
    

}