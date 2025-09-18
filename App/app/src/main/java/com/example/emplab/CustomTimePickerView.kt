package com.example.emplab

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView

/**
 * 自定义时间类型选择器View
 * 可以直接添加到Activity的View树中，能够被页面变化检测到
 */
class CustomTimePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var tvTimePickerTitle: TextView
    private lateinit var layoutAllDay: LinearLayout
    private lateinit var layoutMorning: LinearLayout
    private lateinit var layoutAfternoon: LinearLayout
    private lateinit var rbAllDay: RadioButton
    private lateinit var rbMorning: RadioButton
    private lateinit var rbAfternoon: RadioButton
    private lateinit var tvCancel: TextView
    private lateinit var tvOk: TextView

    private var selectedTimeType = "全天"
    private var onTimeSelectedListener: ((String) -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null

    private val timeOptions = listOf("全天", "上午", "下午")
    private val radioButtons = mutableListOf<RadioButton>()
    private val layouts = mutableListOf<LinearLayout>()

    init {
        initView()
        setupClickListeners()
    }

    private fun initView() {
        LayoutInflater.from(context).inflate(R.layout.custom_time_picker, this, true)
        
        tvTimePickerTitle = findViewById(R.id.tvTimePickerTitle)
        layoutAllDay = findViewById(R.id.layoutAllDay)
        layoutMorning = findViewById(R.id.layoutMorning)
        layoutAfternoon = findViewById(R.id.layoutAfternoon)
        rbAllDay = findViewById(R.id.rbAllDay)
        rbMorning = findViewById(R.id.rbMorning)
        rbAfternoon = findViewById(R.id.rbAfternoon)
        tvCancel = findViewById(R.id.tvCancel)
        tvOk = findViewById(R.id.tvOk)

        // 初始化列表
        radioButtons.addAll(listOf(rbAllDay, rbMorning, rbAfternoon))
        layouts.addAll(listOf(layoutAllDay, layoutMorning, layoutAfternoon))
    }

    private fun setupClickListeners() {
        // 设置选项点击事件
        layoutAllDay.setOnClickListener {
            selectTimeType("全天")
        }

        layoutMorning.setOnClickListener {
            selectTimeType("上午")
        }

        layoutAfternoon.setOnClickListener {
            selectTimeType("下午")
        }

        // 设置单选按钮点击事件
        rbAllDay.setOnClickListener {
            selectTimeType("全天")
        }

        rbMorning.setOnClickListener {
            selectTimeType("上午")
        }

        rbAfternoon.setOnClickListener {
            selectTimeType("下午")
        }

        // 取消按钮
        tvCancel.setOnClickListener {
            onCancelListener?.invoke()
            hide()
        }

        // 确认按钮
        tvOk.setOnClickListener {
            onTimeSelectedListener?.invoke(selectedTimeType)
            hide()
        }
    }

    private fun selectTimeType(timeType: String) {
        selectedTimeType = timeType
        
        // 更新单选按钮状态
        when (timeType) {
            "全天" -> {
                rbAllDay.isChecked = true
                rbMorning.isChecked = false
                rbAfternoon.isChecked = false
            }
            "上午" -> {
                rbAllDay.isChecked = false
                rbMorning.isChecked = true
                rbAfternoon.isChecked = false
            }
            "下午" -> {
                rbAllDay.isChecked = false
                rbMorning.isChecked = false
                rbAfternoon.isChecked = true
            }
        }
    }

    /**
     * 设置标题
     */
    fun setTitle(title: String) {
        tvTimePickerTitle.text = title
    }

    /**
     * 设置当前选中的时间类型
     */
    fun setSelectedTimeType(timeType: String) {
        if (timeType in timeOptions) {
            selectTimeType(timeType)
        }
    }

    /**
     * 获取当前选中的时间类型
     */
    fun getSelectedTimeType(): String {
        return selectedTimeType
    }

    /**
     * 设置时间选择监听器
     */
    fun setOnTimeSelectedListener(listener: (String) -> Unit) {
        onTimeSelectedListener = listener
    }

    /**
     * 设置取消监听器
     */
    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }

    /**
     * 显示时间选择器
     */
    fun show() {
        visibility = View.VISIBLE
    }

    /**
     * 隐藏时间选择器
     */
    fun hide() {
        visibility = View.GONE
    }

    /**
     * 检查是否正在显示
     */
    fun isShowing(): Boolean {
        return visibility == View.VISIBLE
    }
}
