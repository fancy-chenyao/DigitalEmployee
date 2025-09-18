package com.example.emplab

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
    private lateinit var datePickerContainer: FrameLayout
    private lateinit var timePickerContainer: FrameLayout
    
    private var startDate: Date = Date()
    private var endDate: Date = Date()
    private var startTimeType = "全天"
    private var endTimeType = "全天"
    
    private var customDatePicker: CustomDatePickerView? = null
    private var customTimePicker: CustomTimePickerView? = null
    private var isSelectingStartDate = true
    private var isSelectingStartTime = true
    
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
        datePickerContainer = findViewById(R.id.datePickerContainer)
        timePickerContainer = findViewById(R.id.timePickerContainer)
        
        // 初始化自定义日期选择器
        initCustomDatePicker()
        // 初始化自定义时间选择器
        initCustomTimePicker()
    }
    
    private fun setupClickListeners() {
        // 返回按钮
        findViewById<ImageView>(R.id.iv_back).setOnClickListener {
            finish()
        }
        
        // 开始日期点击
        findViewById<LinearLayout>(R.id.layoutStartDate).setOnClickListener {
            showCustomDatePicker(true)
        }
        
        // 结束日期点击
        findViewById<LinearLayout>(R.id.layoutEndDate).setOnClickListener {
            showCustomDatePicker(false)
        }
        
        // 开始时间类型点击
        findViewById<LinearLayout>(R.id.layoutStartTimeType).setOnClickListener {
            showCustomTimePicker(true)
        }
        
        // 结束时间类型点击
        findViewById<LinearLayout>(R.id.layoutEndTimeType).setOnClickListener {
            showCustomTimePicker(false)
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
    
    /**
     * 初始化自定义日期选择器
     */
    private fun initCustomDatePicker() {
        customDatePicker = CustomDatePickerView(this)
        datePickerContainer.addView(customDatePicker)
        
        // 设置日期选择监听器
        customDatePicker?.setOnDateSelectedListener { selectedDate ->
            if (isSelectingStartDate) {
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
        }
        
        // 设置取消监听器
        customDatePicker?.setOnCancelListener {
            hideCustomDatePicker()
        }
    }
    
    /**
     * 显示自定义日期选择器
     */
    private fun showCustomDatePicker(isStartDate: Boolean) {
        isSelectingStartDate = isStartDate
        
        // 设置当前选中的日期
        val currentDate = if (isStartDate) startDate else endDate
        customDatePicker?.setSelectedDate(currentDate)
        
        // 显示日期选择器
        customDatePicker?.show()
        datePickerContainer.visibility = View.VISIBLE
        
        Log.d("LeaveTimeActivity", "显示自定义日期选择器 - 等待ViewTreeObserver自动检测")
        // 不再手动触发，依赖ViewTreeObserver自动检测
    }
    
    /**
     * 隐藏自定义日期选择器
     */
    private fun hideCustomDatePicker() {
        customDatePicker?.hide()
        datePickerContainer.visibility = View.GONE
        
        Log.d("LeaveTimeActivity", "隐藏自定义日期选择器 - 等待ViewTreeObserver自动检测")
        // 不再手动触发，依赖ViewTreeObserver自动检测
    }
    
    /**
     * 触发页面变化检测
     */
    private fun triggerPageChangeDetection() {
        // 这里可以调用MobileService的页面变化检测方法
        // 由于LeaveTimeActivity没有直接访问MobileService的权限，
        // 可以通过广播或其他方式通知MobileService
        Log.d("LeaveTimeActivity", "触发页面变化检测 - 日期选择器状态: ${customDatePicker?.isShowing()}")
        val intent = Intent("com.example.emplab.TRIGGER_PAGE_CHANGE")
        sendBroadcast(intent)
    }
    
    /**
     * 初始化自定义时间选择器
     */
    private fun initCustomTimePicker() {
        customTimePicker = CustomTimePickerView(this)
        timePickerContainer.addView(customTimePicker)
        
        // 设置时间选择监听器
        customTimePicker?.setOnTimeSelectedListener { selectedTimeType ->
            if (isSelectingStartTime) {
                startTimeType = selectedTimeType
            } else {
                endTimeType = selectedTimeType
            }
            updateDisplay()
        }
        
        // 设置取消监听器
        customTimePicker?.setOnCancelListener {
            hideCustomTimePicker()
        }
    }
    
    /**
     * 显示自定义时间选择器
     */
    private fun showCustomTimePicker(isStartTime: Boolean) {
        isSelectingStartTime = isStartTime
        
        // 设置标题
        val title = if (isStartTime) "选择开始时间" else "选择结束时间"
        customTimePicker?.setTitle(title)
        
        // 设置当前选中的时间类型
        val currentTimeType = if (isStartTime) startTimeType else endTimeType
        customTimePicker?.setSelectedTimeType(currentTimeType)
        
        // 显示时间选择器
        customTimePicker?.show()
        timePickerContainer.visibility = View.VISIBLE
        
        Log.d("LeaveTimeActivity", "显示自定义时间选择器 - 等待ViewTreeObserver自动检测")
        // 不再手动触发，依赖ViewTreeObserver自动检测
    }
    
    /**
     * 隐藏自定义时间选择器
     */
    private fun hideCustomTimePicker() {
        customTimePicker?.hide()
        timePickerContainer.visibility = View.GONE
        
        Log.d("LeaveTimeActivity", "隐藏自定义时间选择器 - 等待ViewTreeObserver自动检测")
        // 不再手动触发，依赖ViewTreeObserver自动检测
    }
}
