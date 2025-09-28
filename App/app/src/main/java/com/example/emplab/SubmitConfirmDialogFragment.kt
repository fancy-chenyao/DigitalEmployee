package com.example.emplab

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment

class SubmitConfirmDialogFragment : DialogFragment() {

    interface OnConfirmListener {
        fun onConfirm()
    }

    var onConfirmListener: OnConfirmListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_submit_confirm, container, false)

        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvMessage = view.findViewById<TextView>(R.id.tv_message)
        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)
        val btnOk = view.findViewById<Button>(R.id.btn_ok)

        tvTitle.text = "提交确认"
        tvMessage.text = "确定要提交请假申请吗？"

        btnCancel.setOnClickListener { dismiss() }
        btnOk.setOnClickListener {
            onConfirmListener?.onConfirm()
            dismiss()
        }
        return view
    }
}


