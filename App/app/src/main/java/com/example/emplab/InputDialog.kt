// package com.example.emplab

// import android.app.Dialog
// import android.content.Context
// import android.os.Bundle
// import android.text.Editable
// import android.text.TextWatcher
// import android.view.View
// import android.view.Window
// import android.widget.Button
// import android.widget.EditText
// import android.widget.TextView
// import android.widget.Toast

// /**
//  * 悬浮窗输入对话框
//  * 提供快速输入功能
//  */
// class InputDialog(context: Context) : Dialog(context) {
    
//     private lateinit var editText: EditText
//     private lateinit var btnSend: Button
//     private lateinit var btnClose: Button
//     private lateinit var tvCharCount: TextView
    
//     private var maxLength = 200
//     private var onSendListener: ((String) -> Unit)? = null
    
//     override fun onCreate(savedInstanceState: Bundle?) {
//         super.onCreate(savedInstanceState)
        
//         // 设置无标题栏
//         requestWindowFeature(Window.FEATURE_NO_TITLE)
        
//         // 设置布局
//         setContentView(R.layout.dialog_floating_input)
        
//         // 设置对话框样式
//         setupDialogStyle()
        
//         // 初始化视图
//         setupViews()
        
//         // 设置监听器
//         setupListeners()
//     }
    
//     /**
//      * 设置对话框样式
//      */
//     private fun setupDialogStyle() {
//         window?.let { window ->
//             // 设置背景透明
//             window.setBackgroundDrawableResource(android.R.color.transparent)
            
//             // 设置动画
//             window.setWindowAnimations(R.style.DialogAnimation)
//         }
//     }
    
//     /**
//      * 初始化视图
//      */
//     private fun setupViews() {
//         editText = findViewById(R.id.edit_input)
//         btnSend = findViewById(R.id.btn_send)
//         btnClose = findViewById(R.id.btn_close)
//         tvCharCount = findViewById(R.id.tv_char_count)
        
//         // 设置初始状态
//         updateCharCount(0)
//         btnSend.isEnabled = false
//     }
    
//     /**
//      * 设置监听器
//      */
//     private fun setupListeners() {
//         // 输入框文本变化监听
//         editText.addTextChangedListener(object : TextWatcher {
//             override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
//             override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//                 val length = s?.length ?: 0
//                 updateCharCount(length)
//                 btnSend.isEnabled = length > 0
//             }
            
//             override fun afterTextChanged(s: Editable?) {}
//         })
        
//         // 发送按钮点击
//         btnSend.setOnClickListener {
//             val text = editText.text.toString().trim()
//             if (text.isNotEmpty()) {
//                 handleSend(text)
//             } else {
//                 Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show()
//             }
//         }
        
//         // 关闭按钮点击
//         btnClose.setOnClickListener {
//             dismiss()
//         }
        
//         // 点击外部关闭
//         setCanceledOnTouchOutside(true)
//     }
    
//     /**
//      * 更新字符计数
//      */
//     private fun updateCharCount(count: Int) {
//         tvCharCount.text = "$count/$maxLength"
        
//         // 根据字符数量改变颜色
//         if (count > maxLength * 0.8) {
//             tvCharCount.setTextColor(context.getColor(android.R.color.holo_red_dark))
//         } else {
//             tvCharCount.setTextColor(context.getColor(android.R.color.darker_gray))
//         }
//     }
    
//     /**
//      * 处理发送
//      */
//     private fun handleSend(text: String) {
//         try {
//             // 调用发送监听器
//             onSendListener?.invoke(text)
            
//             // 显示发送成功提示
//             Toast.makeText(context, "发送成功: $text", Toast.LENGTH_SHORT).show()
            
//             // 关闭对话框
//             dismiss()
            
//         } catch (e: Exception) {
//             Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
//         }
//     }
    
//     /**
//      * 设置发送监听器
//      */
//     fun setOnSendListener(listener: (String) -> Unit) {
//         onSendListener = listener
//     }
    
//     /**
//      * 设置最大长度
//      */
//     fun setMaxLength(length: Int) {
//         maxLength = length
//         editText.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
//     }
    
//     /**
//      * 设置提示文本
//      */
//     fun setHint(hint: String) {
//         editText.hint = hint
//     }
    
//     /**
//      * 获取输入内容
//      */
//     fun getInputText(): String {
//         return editText.text.toString().trim()
//     }
    
//     /**
//      * 清空输入内容
//      */
//     fun clearInput() {
//         editText.setText("")
//     }
    
//     /**
//      * 设置输入内容
//      */
//     fun setInputText(text: String) {
//         editText.setText(text)
//         editText.setSelection(text.length)
//     }
// }
