package controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ElementAccessibilityService : AccessibilityService() {
    
    companion object {
        private var instance: ElementAccessibilityService? = null
        
        fun getInstance(): ElementAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // 配置无障碍服务信息
        val info = AccessibilityServiceInfo().apply {
            // 设置事件类型
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            
            // 设置反馈类型
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // 设置标志
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            
            // 设置通知超时
            notificationTimeout = 100
            
            // 设置包名（可以指定特定应用，这里设置为所有应用）
            packageNames = null
        }
        
        serviceInfo = info
        
        Toast.makeText(this, "无障碍服务已启动", Toast.LENGTH_SHORT).show()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // 处理无障碍事件
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // 处理点击事件
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // 处理文本变化事件
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 处理窗口状态变化事件
                }
            }
        }
    }
    
    override fun onInterrupt() {
        // 服务中断时的处理
        Toast.makeText(this, "无障碍服务已中断", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Toast.makeText(this, "无障碍服务已停止", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 通过无障碍服务查找元素
     */
    fun findElementByText(text: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }
    
    /**
     * 通过无障碍服务查找元素（通过ID）
     */
    fun findElementById(viewId: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }
    
    /**
     * 通过无障碍服务查找元素（通过描述）
     */
    fun findElementByDescription(description: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(description)?.firstOrNull()
    }
    
    /**
     * 通过无障碍服务查找元素（通过hint）
     */
    fun findElementByHint(hint: String): AccessibilityNodeInfo? {
        // 首先尝试查找可编辑的元素
        val editableElement = findElementWithPredicate { node ->
            // 排除显示元素树信息的TextView（通过viewId识别）
            if (node.viewIdResourceName?.contains("tvOutput") == true) {
                return@findElementWithPredicate false
            }
            
            // 对于EditText，优先匹配hint属性
            val isEditText = node.className == "android.widget.EditText"
            val matchesHint = if (isEditText) {
                // EditText优先匹配hintText和contentDescription
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                 node.hintText?.contains(hint, ignoreCase = true) == true) ||
                node.contentDescription?.contains(hint, ignoreCase = true) == true ||
                node.text?.contains(hint, ignoreCase = true) == true
            } else {
                // 其他元素的匹配逻辑
                node.viewIdResourceName?.contains(hint, ignoreCase = true) == true ||
                node.contentDescription?.contains(hint, ignoreCase = true) == true ||
                node.text?.contains(hint, ignoreCase = true) == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                 node.hintText?.contains(hint, ignoreCase = true) == true)
            }
            
            // 优先返回可编辑且匹配的元素
            matchesHint && (node.isEditable || isEditText)
        }
        
        // 如果找到可编辑元素，直接返回
        if (editableElement != null) {
            return editableElement
        }
        
        // 否则返回任何匹配的元素（但仍要排除tvOutput）
        return findElementWithPredicate { node ->
            // 排除显示元素树信息的TextView
            if (node.viewIdResourceName?.contains("tvOutput") == true) {
                return@findElementWithPredicate false
            }
            
            node.viewIdResourceName?.contains(hint, ignoreCase = true) == true ||
            node.contentDescription?.contains(hint, ignoreCase = true) == true ||
            node.text?.contains(hint, ignoreCase = true) == true ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
             node.hintText?.contains(hint, ignoreCase = true) == true)
        }
    }
    
    /**
     * 通过自定义谓词查找元素
     */
    private fun findElementWithPredicate(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return traverseAndFind(root, predicate)
    }
    
    /**
     * 遍历节点树并查找符合条件的元素
     */
    private fun traverseAndFind(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let {
                val result = traverseAndFind(it, predicate)
                if (result != null) return result
            }
        }
        
        return null
    }
    
    /**
     * 点击元素
     */
    fun clickElement(nodeInfo: AccessibilityNodeInfo?): Boolean {
        return nodeInfo?.let { node ->
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                // 如果元素本身不可点击，尝试查找父级可点击元素
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
                false
            }
        } ?: false
    }
    
    /**
     * 设置文本
     */
    fun setText(nodeInfo: AccessibilityNodeInfo?, text: String): Boolean {
        nodeInfo?.let { node ->
            // 创建参数Bundle
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            
            // 打印调试信息
            Log.d("AccessibilityService", "尝试设置文本: $text")
            Log.d("AccessibilityService", "节点信息 - ClassName: ${node.className}, isEditable: ${node.isEditable}, isEnabled: ${node.isEnabled}, isFocusable: ${node.isFocusable}")
            
            // 对于EditText类型的元素，即使isEditable为false也尝试设置文本
            if (node.className == "android.widget.EditText") {
                Log.d("AccessibilityService", "检测到EditText，尝试直接设置文本")
                
                // 先尝试聚焦
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                
                // 尝试点击激活
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                // 尝试设置文本
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    Log.d("AccessibilityService", "EditText文本设置成功")
                    return true
                }
            }
            
            // 首先尝试直接在节点上执行设置文本操作
            // 有时节点可能实际上可以接受文本输入，即使isEditable返回false
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.d("AccessibilityService", "直接设置文本成功")
                return true
            }
            
            // 如果元素本身可编辑但设置失败，尝试先聚焦再设置
            if (node.isEditable) {
                Log.d("AccessibilityService", "元素可编辑，尝试聚焦后设置")
                // 尝试聚焦元素
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d("AccessibilityService", "聚焦后设置文本结果: $result")
                return result
            } else {
                Log.d("AccessibilityService", "元素不可编辑，查找父级可编辑元素")
                // 如果元素本身不可编辑，尝试查找父级可编辑元素
                var parent = node.parent
                while (parent != null) {
                    if (parent.isEditable || parent.className == "android.widget.EditText") {
                        Log.d("AccessibilityService", "找到可编辑父元素: ${parent.className}")
                        // 尝试聚焦可编辑的父元素
                        parent.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        
                        val parentArguments = Bundle()
                        parentArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, parentArguments)
                        Log.d("AccessibilityService", "父元素设置文本结果: $result")
                        return result
                    }
                    parent = parent.parent
                }
            }
            
            // 所有方法都失败了
            Log.d("AccessibilityService", "所有文本设置方法都失败了")
            return false
        } ?: return false  // nodeInfo为null
    }
    
    /**
     * 清除文本
     */
    fun clearText(nodeInfo: AccessibilityNodeInfo?): Boolean {
        return nodeInfo?.let { node ->
            if (node.isEditable) {
                // 使用 ACTION_SET_TEXT 设置空文本来清除内容
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                true
            } else {
                false
            }
        } ?: false
    }
    
    /**
     * 滚动操作
     */
    fun scroll(nodeInfo: AccessibilityNodeInfo?, direction: Int): Boolean {
        return nodeInfo?.let { node ->
            when (direction) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                else -> false
            }
            true
        } ?: false
    }
    
    /**
     * 长按操作
     */
    fun longClick(nodeInfo: AccessibilityNodeInfo?): Boolean {
        return nodeInfo?.let { node ->
            if (node.isLongClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                true
            } else {
                false
            }
        } ?: false
    }
    
    /**
     * 获取当前页面的所有可交互元素
     */
    fun getAllInteractiveElements(): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val root = rootInActiveWindow ?: return elements
        
        collectInteractiveElements(root, elements)
        return elements
    }
    
    private fun collectInteractiveElements(node: AccessibilityNodeInfo, elements: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable || node.isEditable || node.isLongClickable || node.isScrollable) {
            elements.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let { collectInteractiveElements(it, elements) }
        }
    }
}






