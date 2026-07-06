package com.xunlei.ai.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.xunlei.ai.R
import com.xunlei.ai.manager.LicenseException
import com.xunlei.ai.manager.LicenseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivationDialog(
    context: Context,
    private val license: LicenseManager,
    private val onSuccess: () -> Unit
) : Dialog(context) {

    private lateinit var et: EditText
    private lateinit var btn: Button
    private lateinit var pb: ProgressBar
    private lateinit var tv: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(LayoutInflater.from(context).inflate(R.layout.dialog_activation, null))
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        et = findViewById(R.id.et_kami)!!
        btn = findViewById(R.id.btn_activate)!!
        pb = findViewById(R.id.progress_bar)!!
        tv = findViewById(R.id.tv_status)!!

        btn.setOnClickListener {
            val k = et.text.toString().trim()
            if (k.length < 4) { tv.text = "请输入正确的卡密"; return@setOnClickListener }
            doActivate(k)
        }
    }

    private fun doActivate(kami: String) {
        loading(true); tv.text = "正在激活..."
        CoroutineScope(Dispatchers.IO).launch {
            val r = license.activate(kami)
            withContext(Dispatchers.Main) {
                if (r.isSuccess) {
                    tv.text = "激活成功！剩余：${license.remainingFormatted()}"
                    dismiss(); onSuccess()
                } else {
                    tv.text = when (val e = r.exceptionOrNull()) {
                        is LicenseException -> e.message ?: "失败(${e.code})"
                        else -> e?.message ?: "网络错误"
                    }
                    loading(false)
                }
            }
        }
    }

    private fun loading(v: Boolean) {
        pb.visibility = if (v) View.VISIBLE else View.GONE
        btn.isEnabled = !v; et.isEnabled = !v
    }
}