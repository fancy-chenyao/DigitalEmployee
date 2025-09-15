package com.example.emplab

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class LeaveTimeActivity : AppCompatActivity() {
    
    private lateinit var tvStartDate: TextView
    private lateinit var tvEndDate: TextView
    private lateinit var tvStartTimeType: TextView
    private lateinit var tvEndTimeType: TextView
    private lateinit var btnConfirm: Button
    
    private var startDate: Date = Date()
    private var endDate: Date = Date()
    private var startTimeType = "全天"
    private var endTimeType = "全天"
    
    private val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave_time)
        
        initViews()
        setupClickListeners()
        updateDisplay()
    }
    
    private fun initViews() {
        tvStartDate = findViewById(R.id.tvStartDate)
        tvEndDate = findViewById(R.id.tvEndDate)
        tvStartTimeType = findViewById(R.id.tvStartTimeType)
        tvEndTimeType = findViewById(R.id.tvEndTimeType)
        btnConfirm = findViewById(R.id.btnConfirm)
    }
    
    private fun setupClickListeners() {
        // 返回按钮
        findViewById<ImageView>(R.id.iv_back).setOnClickListener {
            finish()
        }
        
        // 开始日期点击
        findViewById<LinearLayout>(R.id.layoutStartDate).setOnClickListener {
            showDatePickerDialog(true)
        }
        
        // 结束日期点击
        findViewById<LinearLayout>(R.id.layoutEndDate).setOnClickListener {
            showDatePickerDialog(false)
        }
        
        // 开始时间类型点击
        findViewById<LinearLayout>(R.id.layoutStartTimeType).setOnClickListener {
            showTimeTypeDialog(true)
        }
        
        // 结束时间类型点击
        findViewById<LinearLayout>(R.id.layoutEndTimeType).setOnClickListener {
            showTimeTypeDialog(false)
        }
        
        // 确认按钮
        btnConfirm.setOnClickListener {
            // 跳转到请假详情页面
            val intent = Intent(this, LeaveDetailsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun showDatePickerDialog(isStartDate: Boolean) {
        val calendar = Calendar.getInstance()
        if (isStartDate) {
            calendar.time = startDate
        } else {
            calendar.time = endDate
        }
        
        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }.time
                
                if (isStartDate) {
                    startDate = selectedDate
                    // 如果开始日期晚于结束日期，自动调整结束日期
                    if (startDate.after(endDate)) {
                        endDate = startDate
                    }
                } else {
                    endDate = selectedDate
                    // 如果结束日期早于开始日期，自动调整开始日期
                    if (endDate.before(startDate)) {
                        startDate = endDate
                    }
                }
                updateDisplay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        
        datePickerDialog.show()
    }
    
    private fun showTimeTypeDialog(isStartTime: Boolean) {
        val timeOptions = arrayOf("全天", "上午", "下午")
        val currentSelection = if (isStartTime) startTimeType else endTimeType
        val currentIndex = timeOptions.indexOf(currentSelection)
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (isStartTime) "选择开始时间" else "选择结束时间")
        builder.setSingleChoiceItems(timeOptions, currentIndex) { dialog, which ->
            val selectedTimeType = timeOptions[which]
            if (isStartTime) {
                startTimeType = selectedTimeType
            } else {
                endTimeType = selectedTimeType
            }
            updateDisplay()
            dialog.dismiss()
        }
        builder.show()
    }
    
    private fun updateDisplay() {
        tvStartDate.text = dateFormat.format(startDate)
        tvEndDate.text = dateFormat.format(endDate)
        tvStartTimeType.text = startTimeType
        tvEndTimeType.text = endTimeType
        
        val days = calculateLeaveDays()
        btnConfirm.text = "拟请假${days}天, 确认"
    }
    
    private fun calculateLeaveDays(): Int {
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        val start = calendar.get(Calendar.DAY_OF_YEAR)
        
        calendar.time = endDate
        val end = calendar.get(Calendar.DAY_OF_YEAR)
        
        return end - start + 1
    }
}
