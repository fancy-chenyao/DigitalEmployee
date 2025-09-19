package controller

import android.graphics.Rect
import android.view.View

data class GenericElement(
    val resourceId: String,  // 对应 "resource-id"
    val className: String,   // 对应 "class"
    val text: String,        // 对应 "text"
    val contentDesc: String, // 对应 "content-desc"
    val bounds: Rect,        // 对应 "bounds"
    val important: Boolean,  // 对应 "important"
    val enabled: Boolean,    // 对应 "enabled"
    val checked: Boolean,    // 对应 "checked"
    val clickable: Boolean = false,  // 对应 "clickable"
    val checkable: Boolean = false,  // 对应 "checkable"
    val scrollable: Boolean = false, // 对应 "scrollable"
    val longClickable: Boolean = false, // 对应 "long-clickable"
    val selected: Boolean = false,   // 对应 "selected"
    val index: Int = 0,      // 对应 "index"
    val naf: Boolean = false, // 对应 "NAF"
    val additionalProps: Map<String, String>,
    val children: List<GenericElement>,
    val view: View? = null   // 直接引用对应的View对象，仅在Native页面中有效
) {
    override fun toString(): String {
        return "GenericElement(resourceId='$resourceId', className='$className', text='$text', contentDesc='$contentDesc', " +
                "bounds=$bounds, important=$important, enabled=$enabled, checked=$checked, " +
                "clickable=$clickable, checkable=$checkable, scrollable=$scrollable, " +
                "longClickable=$longClickable, selected=$selected, index=$index, naf=$naf, " +
                "additionalProps=$additionalProps, childrenCount=${children.size})"
    }

    fun toFormattedString(indent: Int = 0): String {
        val indentStr = "  ".repeat(indent)
        val sb = StringBuilder()

        sb.append("$indentStr$className [resource-id=$resourceId")
        if (text.isNotEmpty()) sb.append(", text='$text'")
        if (contentDesc.isNotEmpty()) sb.append(", content-desc='$contentDesc'")
        if (bounds != Rect()) sb.append(", bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        sb.append("]")

        children.forEach { child ->
            sb.append("\n${child.toFormattedString(indent + 1)}")
        }

        return sb.toString()
    }

    fun toXmlString(indent: Int = 0): String {
        val indentStr = "  ".repeat(indent)
        val sb = StringBuilder()
        
        // 构建属性列表，按照AccessibilityNodeInfoDumper的格式
        val attributes = mutableListOf<String>()
        if (naf) attributes.add("NAF=\"true\"")
        if (resourceId.isNotEmpty()) attributes.add("resource-id=\"${resourceId.escapeXml()}\"")
        attributes.add("important=\"$important\"")
        attributes.add("index=\"$index\"")
        if (text.isNotEmpty()) attributes.add("text=\"${text.escapeXml()}\"")
        if (className.isNotEmpty()) attributes.add("class=\"${className.escapeXml()}\"")
        if (contentDesc.isNotEmpty()) attributes.add("content-desc=\"${contentDesc.escapeXml()}\"")
        attributes.add("checkable=\"$checkable\"")
        attributes.add("checked=\"$checked\"")
        attributes.add("clickable=\"$clickable\"")
        attributes.add("enabled=\"$enabled\"")
        attributes.add("scrollable=\"$scrollable\"")
        attributes.add("long-clickable=\"$longClickable\"")
        attributes.add("selected=\"$selected\"")
        attributes.add("bounds=\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\"")
        
        // 添加additionalProps作为属性
        additionalProps.forEach { (key, value) ->
            attributes.add("${key}=\"${value.escapeXml()}\"")
        }
        
        if (children.isEmpty()) {
            // 自闭合标签
            sb.append("$indentStr<node ${attributes.joinToString(" ")}/>\n")
        } else {
            // 有子元素的标签
            sb.append("$indentStr<node ${attributes.joinToString(" ")}>\n")
            children.forEach { child ->
                sb.append(child.toXmlString(indent + 1))
            }
            sb.append("$indentStr</node>\n")
        }
        
        return sb.toString()
    }
    
    private fun String.escapeXml(): String {
        return this.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    

}