package Agent

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Agent Ask对话框
 * 用于显示AI问题并收集用户答案
 */
class AgentAskDialog(context: Context) : Dialog(context) {
    
    private lateinit var tvQuestion: TextView
    private lateinit var editAnswer: EditText
    private lateinit var btnSend: Button
    private lateinit var btnCancel: Button
    private lateinit var tvCharCount: TextView
    
    private var maxLength = 200
    private var onSendAnswerListener: ((String, String, String) -> Unit)? = null
    private var infoName: String = ""
    private var question: String = ""
    // 在show()之前调用setQuestion时暂存
    private var pendingInfoName: String? = null
    private var pendingQuestion: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置无标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        // 设置布局
        setContentView(context.resources.getIdentifier("agent_ask_dialog", "layout", context.packageName).let { 
            if (it != 0) it else android.R.layout.simple_list_item_1 
        })
        
        // 设置对话框样式
        setupDialogStyle()
        
        // 初始化视图
        setupViews()
        
        // 设置监听器
        setupListeners()

        // 如果在show()之前已设置问题，渲染到视图
        if (!question.isEmpty()) {
            tvQuestion.text = question
        } else if (pendingQuestion != null && pendingInfoName != null) {
            infoName = pendingInfoName!!
            question = pendingQuestion!!
            tvQuestion.text = question
            pendingInfoName = null
            pendingQuestion = null
        }
    }
    
    /**
     * 设置对话框样式
     */
    private fun setupDialogStyle() {
        window?.let { window ->
            // 设置背景透明
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
    
    /**
     * 初始化视图
     */
    private fun setupViews() {
        tvQuestion = findViewById(context.resources.getIdentifier("tv_question", "id", context.packageName))
        editAnswer = findViewById(context.resources.getIdentifier("edit_answer", "id", context.packageName))
        btnSend = findViewById(context.resources.getIdentifier("btn_send_answer", "id", context.packageName))
        btnCancel = findViewById(context.resources.getIdentifier("btn_cancel", "id", context.packageName))
        tvCharCount = findViewById(context.resources.getIdentifier("tv_char_count", "id", context.packageName))
        
        // 设置初始状态
        updateCharCount(0)
        btnSend.isEnabled = false
    }
    
    /**
     * 设置监听器
     */
    private fun setupListeners() {
        // 答案输入框文本变化监听
        editAnswer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                updateCharCount(length)
                btnSend.isEnabled = length > 0
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // 发送答案按钮点击
        btnSend.setOnClickListener {
            val answer = editAnswer.text.toString().trim()
            if (answer.isNotEmpty()) {
                handleSendAnswer(answer)
            } else {
                Toast.makeText(context, "请输入答案", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 取消按钮点击
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        // 点击外部关闭
        setCanceledOnTouchOutside(true)
    }
    
    /**
     * 设置问题
     */
    fun setQuestion(info: String, question: String) {
        this.infoName = info
        this.question = question
        if (this::tvQuestion.isInitialized && this::editAnswer.isInitialized) {
            tvQuestion.text = question
            // 清空之前的答案
            clearAnswer()
            // 自动聚焦到答案输入框
            editAnswer.requestFocus()
            // 显示软键盘
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editAnswer, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            // 视图尚未创建，先暂存
            pendingInfoName = info
            pendingQuestion = question
        }
    }
    
    /**
     * 更新字符计数
     */
    private fun updateCharCount(count: Int) {
        tvCharCount.text = "$count/$maxLength"
        
        // 根据字符数量改变颜色
        if (count > maxLength * 0.8) {
            tvCharCount.setTextColor(context.getColor(android.R.color.holo_red_dark))
        } else {
            tvCharCount.setTextColor(context.getColor(android.R.color.darker_gray))
        }
    }
    
    /**
     * 处理发送答案
     */
    private fun handleSendAnswer(answer: String) {
        try {
            // 调用发送答案监听器
            onSendAnswerListener?.invoke(infoName, question, answer)
            
            // 显示发送成功提示
            Toast.makeText(context, "答案已发送", Toast.LENGTH_SHORT).show()
            
            // 关闭对话框
            dismiss()
            
        } catch (e: Exception) {
            Toast.makeText(context, "发送答案失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 设置发送答案监听器
     */
    fun setOnSendAnswerListener(listener: (String, String, String) -> Unit) {
        onSendAnswerListener = listener
    }
    
    /**
     * 设置最大长度
     */
    fun setMaxLength(length: Int) {
        maxLength = length
        editAnswer.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
    }
    
    /**
     * 获取答案内容
     */
    fun getAnswerText(): String {
        return editAnswer.text.toString().trim()
    }
    
    /**
     * 清空答案内容
     */
    fun clearAnswer() {
        editAnswer.setText("")
    }
    
    /**
     * 设置答案内容
     */
    fun setAnswerText(text: String) {
        editAnswer.setText(text)
        editAnswer.setSelection(text.length)
    }
    
    /**
     * 隐藏软键盘
     */
    private fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editAnswer.windowToken, 0)
    }
    
    override fun dismiss() {
        hideSoftKeyboard()
        super.dismiss()
    }
}
