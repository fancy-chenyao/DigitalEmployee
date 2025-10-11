package com.example.emplab

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * 自定义日期选择器View
 * 可以直接添加到Activity的View树中，能够被页面变化检测到
 */
class CustomDatePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var tvYear: TextView
    private lateinit var tvSelectedDate: TextView
    private lateinit var ivClose: ImageView
    private lateinit var ivPrevMonth: ImageView
    private lateinit var ivNextMonth: ImageView
    private lateinit var tvMonth: TextView
    private lateinit var gridLayoutDates: GridLayout
    private lateinit var tvCancel: TextView
    private lateinit var tvOk: TextView

    private var currentCalendar = Calendar.getInstance()
    private var selectedDate: Date? = null
    private var onDateSelectedListener: ((Date) -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null

    private val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE, MMM dd", Locale.getDefault())

    init {
        initView()
        setupClickListeners()
        updateDisplay()
    }

    private fun initView() {
        LayoutInflater.from(context).inflate(R.layout.custom_date_picker, this, true)
        
        tvYear = findViewById(R.id.tvYear)
        tvSelectedDate = findViewById(R.id.tvSelectedDate)
        ivClose = findViewById(R.id.ivClose)
        ivPrevMonth = findViewById(R.id.ivPrevMonth)
        ivNextMonth = findViewById(R.id.ivNextMonth)
        tvMonth = findViewById(R.id.tvMonth)
        gridLayoutDates = findViewById(R.id.gridLayoutDates)
        tvCancel = findViewById(R.id.tvCancel)
        tvOk = findViewById(R.id.tvOk)
    }

    private fun setupClickListeners() {
        // 关闭按钮
        ivClose.setOnClickListener {
            hide()
        }

        // 上一个月
        ivPrevMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, -1)
            updateDisplay()
        }

        // 下一个月
        ivNextMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, 1)
            updateDisplay()
        }

        // 取消按钮
        tvCancel.setOnClickListener {
            onCancelListener?.invoke()
            hide()
        }

        // 确认按钮
        tvOk.setOnClickListener {
            selectedDate?.let { date ->
                onDateSelectedListener?.invoke(date)
            }
            hide()
        }
    }

    private fun updateDisplay() {
        // 更新年份显示
        tvYear.text = currentCalendar.get(Calendar.YEAR).toString()

        // 更新月份显示
        tvMonth.text = monthFormat.format(currentCalendar.time)

        // 更新选中日期显示
        selectedDate?.let { date ->
            tvSelectedDate.text = dayFormat.format(date)
        }

        // 更新日期网格
        updateDateGrid()
    }

    private fun updateDateGrid() {
        gridLayoutDates.removeAllViews()

        val calendar = currentCalendar.clone() as Calendar
        calendar.set(Calendar.DAY_OF_MONTH, 1)

        // 获取当月第一天是星期几（0=Sunday, 1=Monday, ...）
        val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        // 创建日历网格：6行 x 7列 = 42个位置
        val totalCells = 42
        val cells = mutableListOf<TextView>()

        // 初始化所有单元格
        for (i in 0 until totalCells) {
            val cell = TextView(context)
            cell.textSize = 16f
            cell.gravity = android.view.Gravity.CENTER
            cell.setPadding(16, 16, 16, 16)
            cell.isClickable = true
            cell.isFocusable = true
            cells.add(cell)
        }

        // 填充日期
        for (day in 1..daysInMonth) {
            val cellIndex = firstDayOfWeek + day - 1
            if (cellIndex < totalCells) {
                val cell = cells[cellIndex]
                cell.text = day.toString()
                cell.setOnClickListener { selectDate(day) }
                updateDateButtonStyle(cell, day)
            }
        }

        // 将所有单元格添加到GridLayout
        for (cell in cells) {
            val layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            cell.layoutParams = layoutParams
            gridLayoutDates.addView(cell)
        }
    }

    private fun createDateButton(day: Int): TextView {
        val button = TextView(context)
        button.text = day.toString()
        button.textSize = 16f
        button.gravity = android.view.Gravity.CENTER
        button.setPadding(16, 16, 16, 16)
        button.isClickable = true
        button.isFocusable = true

        // 设置布局参数，让GridLayout自动排列
        val layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(4, 4, 4, 4)
        }
        button.layoutParams = layoutParams

        // 设置样式
        updateDateButtonStyle(button, day)

        // 设置点击事件
        button.setOnClickListener {
            selectDate(day)
        }

        return button
    }

    /**
     * 更新日期按钮的样式和选中状态
     * @param button 日期按钮
     * @param day 日期数字
     */
    private fun updateDateButtonStyle(button: TextView, day: Int) {
        val calendar = currentCalendar.clone() as Calendar
        calendar.set(Calendar.DAY_OF_MONTH, day)
        val date = calendar.time

        // 检查是否是今天
        val today = Calendar.getInstance()
        val isToday = calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                     calendar.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                     calendar.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)

        // 检查是否是选中的日期
        val isSelected = selectedDate?.let { selected ->
            calendar.get(Calendar.YEAR) == Calendar.getInstance().apply { time = selected }.get(Calendar.YEAR) &&
            calendar.get(Calendar.MONTH) == Calendar.getInstance().apply { time = selected }.get(Calendar.MONTH) &&
            calendar.get(Calendar.DAY_OF_MONTH) == Calendar.getInstance().apply { time = selected }.get(Calendar.DAY_OF_MONTH)
        } ?: false

        // 重要：同步选中状态到View的isSelected属性，以便NativeController能够正确解析
        button.isSelected = isSelected

        when {
            isSelected -> {
                // 选中状态
                button.setTextColor(Color.WHITE)
                button.background = createCircleBackground("#6A4C93")
            }
            isToday -> {
                // 今天状态
                button.setTextColor(Color.WHITE)
                button.background = createCircleBackground("#2196F3")
            }
            else -> {
                // 普通状态
                button.setTextColor(Color.BLACK)
                button.background = null
            }
        }
    }

    private fun createCircleBackground(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    private fun selectDate(day: Int) {
        val calendar = currentCalendar.clone() as Calendar
        calendar.set(Calendar.DAY_OF_MONTH, day)
        selectedDate = calendar.time

//        // // 更新显示
//       updateDisplay()
//       updateDisplay()


        // 自动确认选择的日期并关闭日期选择器
         selectedDate?.let { date ->
             onDateSelectedListener?.invoke(date)
         }
         hide()
    }

    /**
     * 设置初始选中的日期
     */
    fun setSelectedDate(date: Date) {
        selectedDate = date
        currentCalendar.time = date
        updateDisplay()
    }

    /**
     * 设置日期选择监听器
     */
    fun setOnDateSelectedListener(listener: (Date) -> Unit) {
        onDateSelectedListener = listener
    }

    /**
     * 设置取消监听器
     */
    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }

    /**
     * 显示日期选择器
     */
    fun show() {
        visibility = View.VISIBLE
    }

    /**
     * 隐藏日期选择器
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
