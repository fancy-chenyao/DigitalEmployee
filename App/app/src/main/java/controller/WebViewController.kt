package controller

import android.content.Context
import android.graphics.Rect
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object WebViewController {
    private const val JS_INTERFACE_NAME = "AndroidNativeBridge"
    
    // 复用的注入脚本构造函数，避免不同方法之间脚本重复
    private fun getInjectScript(): String {
        return (
            """
            window.__NativeBridge = {
                getElementTree: function() {
                    try {
                        // 为本次树构建维护一个唯一的索引计数器
                        let indexCounter = 0;
                        function parseNode(node) {
                            if (!node || !node.getBoundingClientRect) {
                                return null;
                            }
                            
                            const rect = node.getBoundingClientRect();
                            const children = [];
                            
                            if (node.children && node.children.length > 0) {
                                for (let i = 0; i < node.children.length; i++) {
                                    const child = parseNode(node.children[i]);
                                    if (child) {
                                        children.push(child);
                                    }
                                }
                            }
                            
                            return {
                                resourceId: node.id || '',
                                className: node.tagName || '',
                                text: (node.innerText || node.value || '').substring(0, 100), // 限制文本长度
                                contentDesc: node.placeholder || '',
                                bounds: {
                                    left: Math.round(rect.left + window.pageXOffset) || 0,
                                    top: Math.round(rect.top + window.pageYOffset) || 0,
                                    right: Math.round(rect.right + window.pageXOffset) || 0,
                                    bottom: Math.round(rect.bottom + window.pageYOffset) || 0
                                },
                                important: !!(node.offsetWidth || node.offsetHeight || (node.getClientRects && node.getClientRects().length)),
                                enabled: !node.disabled,
                                checked: node.checked || false,
                                clickable: node.onclick !== null || node.addEventListener !== undefined,
                                checkable: node.type === 'checkbox' || node.type === 'radio',
                                scrollable: node.scrollHeight > node.clientHeight || node.scrollWidth > node.clientWidth,
                                longClickable: false,
                                selected: node.selected || false,
                                index: (indexCounter++),
                                naf: false,
                                additionalProps: {
                                    className: node.className || '',
                                    tagName: node.tagName || '',
                                    name: node.name || ''
                                },
                                children: children
                            };
                        }
                        
                        const rootElement = document.documentElement || document.body;
                        if (!rootElement) {
                            return JSON.stringify({
                                resourceId: 'root',
                                className: 'html',
                                text: '',
                                contentDesc: '',
                                bounds: { left: 0, top: 0, right: 0, bottom: 0 },
                                important: true,
                                enabled: true,
                                checked: false,
                                clickable: false,
                                checkable: false,
                                scrollable: false,
                                longClickable: false,
                                selected: false,
                                index: (indexCounter++),
                                naf: false,
                                additionalProps: {},
                                children: []
                            });
                        }
                        
                        const result = parseNode(rootElement);
                        return JSON.stringify(result || {
                            resourceId: 'error',
                            className: 'Error',
                            text: '解析失败',
                            contentDesc: '',
                            bounds: { left: 0, top: 0, right: 0, bottom: 0 },
                            important: false,
                            enabled: false,
                            checked: false,
                            clickable: false,
                            checkable: false,
                            scrollable: false,
                            longClickable: false,
                            selected: false,
                            index: (indexCounter++),
                            naf: false,
                            additionalProps: {},
                            children: []
                        });
                    } catch (error) {
                        return JSON.stringify({
                            resourceId: 'error',
                            className: 'Error',
                            text: 'JavaScript错误: ' + error.message,
                            contentDesc: '',
                            bounds: { left: 0, top: 0, right: 0, bottom: 0 },
                            important: false,
                            enabled: false,
                            checked: false,
                            clickable: false,
                            checkable: false,
                            scrollable: false,
                            longClickable: false,
                            selected: false,
                            index: (indexCounter++),
                            naf: false,
                            additionalProps: {},
                            children: []
                        });
                    }
                },
                clickElement: function(elementId) {
                    try {
                        const element = document.getElementById(elementId);
                        if (element && element.click) {
                            element.click();
                            return true;
                        }
                        return false;
                    } catch (error) {
                        return false;
                    }
                },
                setInputValue: function(elementId, text) {
                    try {
                        const element = document.getElementById(elementId);
                        if (element && element.value !== undefined) {
                            element.value = text || '';
                            // 触发输入事件
                            const event = new Event('input', { bubbles: true });
                            element.dispatchEvent(event);
                            return true;
                        }
                        return false;
                    } catch (error) {
                        return false;
                    }
                }
            };
            """
        ).trimIndent()
    }
    
    fun initWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(WebAppInterface(webView.context), JS_INTERFACE_NAME)

        // 注入JS脚本
        val injectScript = getInjectScript()
        
        // 始终在页面完成加载后再次注入，避免被页面刷新覆盖
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript("(function(){ $injectScript })();", null)
            }
        }
        // 首次也注入一次（以防页面已加载）
        webView.evaluateJavascript("(function(){ $injectScript })();", null)
    }
    
    fun getElementTree(webView: WebView, callback: (GenericElement) -> Unit) {
        // 确保JS可用
        try { webView.settings.javaScriptEnabled = true } catch (_: Exception) {}

        fun evaluateAndParse(value: String?) {
            try {
                val raw = value ?: "null"
                Log.d("WebViewController", "getElementTree raw: $raw")
                if (raw == "null" || raw == "undefined" || raw.isBlank()) {
                    callback(createErrorElement("WebView未就绪或JS未注入"))
                    return
                }

                var jsonString = raw
                if (jsonString.length >= 2 && jsonString.first() == '"' && jsonString.last() == '"') {
                    jsonString = jsonString.substring(1, jsonString.length - 1)
                }
                jsonString = jsonString
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")

                if (!jsonString.trim().startsWith("{")) {
                    callback(createErrorElement("返回数据非法"))
                    return
                }

                val element = parseJsonToGenericElement(jsonString)
                callback(element)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(createErrorElement("解析错误: ${e.message}"))
            }
        }

        fun fetch(attempt: Int) {
            // 检查桥接是否已就绪
            webView.evaluateJavascript("(function(){return !!(window.__NativeBridge && window.__NativeBridge.getElementTree)})()") { ready ->
                if (ready == "true") {
                    webView.evaluateJavascript("window.__NativeBridge.getElementTree();") { value ->
                        evaluateAndParse(value)
                    }
                } else {
                    // 尝试注入脚本后重试一次
                    val injectScript = getInjectScript()
                    webView.evaluateJavascript("(function(){ $injectScript })();") { _ ->
                        if (attempt < 1) {
                            fetch(attempt + 1)
                        } else {
                            evaluateAndParse(null)
                        }
                    }
                }
            }
        }

        fetch(0)
    }
    
    fun clickElement(webView: WebView, elementId: String, callback: (Boolean) -> Unit) {
        webView.evaluateJavascript("window.__NativeBridge.clickElement('$elementId');") { result ->
            callback(result == "true")
        }
    }
    
    fun setInputValue(webView: WebView, elementId: String, text: String, callback: (Boolean) -> Unit) {
        val escapedText = text.replace("'", "\\'")
        webView.evaluateJavascript("window.__NativeBridge.setInputValue('$elementId', '$escapedText');") { result ->
            callback(result == "true")
        }
    }
    
    /**
     * 模拟长按操作
     */
    fun longClickElement(webView: WebView, elementId: String, callback: (Boolean) -> Unit) {
        // Web页面暂不支持长按操作
        callback(false)
    }
    
    private fun parseJsonToGenericElement(jsonString: String): GenericElement {
        val jsonObject = JSONObject(jsonString)
        return parseJsonNode(jsonObject)
    }
    
    private fun parseJsonNode(jsonObject: JSONObject): GenericElement {
        // 安全地解析bounds，提供默认值
        val bounds = try {
            if (jsonObject.has("bounds") && !jsonObject.isNull("bounds")) {
                val boundsObject = jsonObject.getJSONObject("bounds")
                Rect(
                    boundsObject.optInt("left", 0),
                    boundsObject.optInt("top", 0),
                    boundsObject.optInt("right", 0),
                    boundsObject.optInt("bottom", 0)
                )
            } else {
                Rect(0, 0, 0, 0)
            }
        } catch (e: Exception) {
            Rect(0, 0, 0, 0)
        }
        
        // 安全地解析additionalProps
        val additionalProps = mutableMapOf<String, String>()
        try {
            if (jsonObject.has("additionalProps") && !jsonObject.isNull("additionalProps")) {
                val additionalPropsObject = jsonObject.getJSONObject("additionalProps")
                val keys = additionalPropsObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!additionalPropsObject.isNull(key)) {
                        additionalProps[key] = additionalPropsObject.optString(key, "")
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误，使用空map
        }
        
        // 安全地解析children
        val children = mutableListOf<GenericElement>()
        try {
            if (jsonObject.has("children") && !jsonObject.isNull("children")) {
                val childrenArray = jsonObject.getJSONArray("children")
                for (i in 0 until childrenArray.length()) {
                    try {
                        val childObject = childrenArray.getJSONObject(i)
                        children.add(parseJsonNode(childObject))
                    } catch (e: Exception) {
                        // 跳过有问题的子元素
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误，使用空列表
        }
        
        return GenericElement(
            resourceId = jsonObject.optString("resourceId", ""),
            className = jsonObject.optString("className", ""),
            text = jsonObject.optString("text", ""),
            contentDesc = jsonObject.optString("contentDesc", ""),
            bounds = bounds,
            important = jsonObject.optBoolean("important", true),
            enabled = jsonObject.optBoolean("enabled", true),
            checked = jsonObject.optBoolean("checked", false),
            clickable = jsonObject.optBoolean("clickable", false),
            checkable = jsonObject.optBoolean("checkable", false),
            scrollable = jsonObject.optBoolean("scrollable", false),
            longClickable = jsonObject.optBoolean("longClickable", false),
            selected = jsonObject.optBoolean("selected", false),
            index = jsonObject.optInt("index", 0),
            naf = jsonObject.optBoolean("naf", false),
            additionalProps = additionalProps,
            children = children
        )
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
            children = emptyList()
        )
    }
    
    class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}