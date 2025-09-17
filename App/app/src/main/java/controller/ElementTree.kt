package controller

/**
 * 页面元素树
 */
data class ElementTree(
    val elements: List<ElementInfo>,
    val type: String
)

/**
 * 页面元素信息
 */
data class ElementInfo(
    val id: String,
    val type: String,
    val text: String?,
    val bounds: String,
    val clickable: Boolean,
    val focusable: Boolean,
    val enabled: Boolean,
    val visible: Boolean
)






