package com.example.emplab

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LeaveApplicationActivity : AppCompatActivity() {
    
    private lateinit var listViewLeaveTypes: ListView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave_application)
        
        initViews()
        setupLeaveTypes()
    }
    
    private fun initViews() {
        listViewLeaveTypes = findViewById(R.id.listViewLeaveTypes)
        
        // 设置返回按钮点击事件
        findViewById<ImageView>(R.id.iv_back).setOnClickListener {
            finish() // 关闭当前Activity，返回上一页
        }
    }
    
    private fun setupLeaveTypes() {
        // 请假类型列表
        val leaveTypes = listOf(
            "年休假",
            "事假", 
            "病假",
            "婚假",
            "法定产假",
            "经组织批准产假",
            "探亲假",
            "丧假",
            "工伤假",
            "其它假"
        )
        
        // 创建自定义适配器
        val adapter = LeaveTypeAdapter(leaveTypes)
        listViewLeaveTypes.adapter = adapter
        
        // 设置点击事件
        listViewLeaveTypes.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selectedLeaveType = leaveTypes[position]
            
            // 如果是年休假，跳转到拟请假时间页面
            if (selectedLeaveType == "年休假") {
                val intent = Intent(this, LeaveTimeActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(this, "选择了：$selectedLeaveType", Toast.LENGTH_SHORT).show()
                // 其他请假类型暂时只显示Toast
            }
        }
    }
    
    // 自定义适配器
    private inner class LeaveTypeAdapter(private val leaveTypes: List<String>) : BaseAdapter() {
        
        override fun getCount(): Int = leaveTypes.size
        
        override fun getItem(position: Int): Any = leaveTypes[position]
        
        override fun getItemId(position: Int): Long = position.toLong()
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_leave_type, parent, false)
            
            val tvLeaveType = view.findViewById<TextView>(R.id.tvLeaveType)
            tvLeaveType.text = leaveTypes[position]
            
            return view
        }
    }
}
