package com.example.emplab

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class LeaveDetailsActivity : AppCompatActivity() {
    
    private lateinit var etReason: EditText
    private lateinit var etDestination: EditText
    private lateinit var tvReasonCount: TextView
    private lateinit var tvDestinationCount: TextView
    private lateinit var btnPrevious: Button
    private lateinit var btnSubmit: Button
    private lateinit var layoutImageUpload: LinearLayout
    private lateinit var ivUploadedImage: ImageView
    
    private val maxLength = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave_details)
        
        initViews()
        setupClickListeners()
        setupTextWatchers()
    }
    
    private fun initViews() {
        etReason = findViewById(R.id.etReason)
        etDestination = findViewById(R.id.etDestination)
        tvReasonCount = findViewById(R.id.tvReasonCount)
        tvDestinationCount = findViewById(R.id.tvDestinationCount)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnSubmit = findViewById(R.id.btnSubmit)
        layoutImageUpload = findViewById(R.id.layoutImageUpload)
        ivUploadedImage = findViewById(R.id.ivUploadedImage)
        
        // 初始化字数显示
        updateCharacterCount(etReason, tvReasonCount)
        updateCharacterCount(etDestination, tvDestinationCount)
    }
    
    private fun setupClickListeners() {
        // 返回按钮
        findViewById<ImageView>(R.id.iv_back).setOnClickListener {
            finish()
        }
        
        // 上一步按钮
        btnPrevious.setOnClickListener {
            finish() // 返回上一步
        }
        
        // 提交按钮
        btnSubmit.setOnClickListener {
            showSubmitDialog()
        }
        
        // 图片上传区域
        layoutImageUpload.setOnClickListener {
            showImageUploadDialog()
        }
    }
    
    private fun setupTextWatchers() {
        // 请假事由字数监听
        etReason.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCharacterCount(etReason, tvReasonCount)
            }
        })
        
        // 拟前往地区字数监听
        etDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCharacterCount(etDestination, tvDestinationCount)
            }
        })
    }
    
    private fun updateCharacterCount(editText: EditText, textView: TextView) {
        val currentLength = editText.text.length
        val remaining = maxLength - currentLength
        textView.text = "剩余字数 $remaining"
        
        // 根据剩余字数改变颜色
        if (remaining < 10) {
            textView.setTextColor(getColor(R.color.icon_orange))
        } else {
            textView.setTextColor(getColor(R.color.text_secondary))
        }
    }
    
    private fun showImageUploadDialog() {
        val options = arrayOf("拍照", "从相册选择", "取消")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("选择图片")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    // 拍照
                    Toast.makeText(this, "拍照功能暂未实现", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    // 从相册选择
                    Toast.makeText(this, "相册选择功能暂未实现", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    // 取消
                }
            }
        }
        builder.show()
    }
    
    private fun showSubmitDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("提交确认")
        builder.setMessage("确定要提交请假申请吗？")
        builder.setPositiveButton("确定") { _, _ ->
            // 显示提交成功消息
            showSuccessDialog()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }
    
    private fun showSuccessDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("提交成功")
        builder.setMessage("您的请假申请已提交")
        builder.setPositiveButton("确定") { _, _ ->
            // 返回首页
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
        builder.setCancelable(false)
        builder.show()
    }
}
