package com.example.emplab

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Handler
import android.os.Looper
import android.util.Log
import controller.NativeController

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
            showOverlayConfirm()
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
    
    private fun showOverlayConfirm() {
        val overlay = findViewById<View>(R.id.confirmOverlay)
        val btnOk = findViewById<Button>(R.id.btnOkOverlay)
        val btnCancel = findViewById<Button>(R.id.btnCancelOverlay)
        overlay.visibility = View.VISIBLE

        btnCancel.setOnClickListener {
            overlay.visibility = View.GONE
        }
        btnOk.setOnClickListener {
            overlay.visibility = View.GONE
            showSuccessDialog()
        }

        // // 延时1秒，抓取当前元素树并打印到前端日志
        // Handler(Looper.getMainLooper()).postDelayed({
        //     try {
        //         NativeController.getElementTree(this) { tree ->
        //             Log.d("LeaveDetailsActivity", "ElementTree(full):\n${tree.toFormattedString(0)}")
        //             Toast.makeText(this, "元素树已打印到日志", Toast.LENGTH_SHORT).show()
        //         }
        //     } catch (_: Exception) {
        //         Toast.makeText(this, "元素树抓取失败", Toast.LENGTH_SHORT).show()
        //     }
        // }, 1000)
    }
    
    private fun showSuccessDialog() {
        val overlay = findViewById<View>(R.id.successOverlay)
        val btnOk = findViewById<Button>(R.id.btnOkSuccess)
        overlay.visibility = View.VISIBLE

        btnOk.setOnClickListener {
            overlay.visibility = View.GONE
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        // // 延时打印当前元素树，便于解析器捕捉
        // Handler(Looper.getMainLooper()).postDelayed({
        //     try {
        //         NativeController.getElementTree(this) { tree ->
        //             Log.d("LeaveDetailsActivity", "SuccessOverlay ElementTree:\n${tree.toFormattedString(0)}")
        //             Toast.makeText(this, "成功弹层元素树已打印", Toast.LENGTH_SHORT).show()
        //         }
        //     } catch (_: Exception) {}
        // }, 800)
    }
}
