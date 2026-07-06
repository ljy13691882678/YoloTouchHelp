package com.xunlei.ai.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.xunlei.ai.manager.ConfigManager
import com.xunlei.ai.inference.JniCallBack
import com.xunlei.ai.util.ProjectionHolder
import com.xunlei.ai.service.FloatService

class SettingsActivity : AppCompatActivity() {

    private val PAGE_BG = Color.parseColor("#F1F5F9")
    private val SURFACE_BG = Color.parseColor("#F7F8FC")
    private val CARD_BG = Color.parseColor("#FFFFFF")
    private val TEXT_MAIN = Color.parseColor("#1F2937")
    private val TEXT_SUB = Color.parseColor("#6B7280")
    private val PRIMARY = Color.parseColor("#5B67F0")
    private val BORDER = Color.parseColor("#E5E7EB")

    private val THREAD_VALUES = intArrayOf(1, 2, 4, 6, 8)

    private val displayDensity: Float by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * displayDensity + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        setContentView(createLayout())
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BG)
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "高级设置"
            setTitleTextColor(TEXT_MAIN)
            navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.apply {
                setTint(TEXT_MAIN)
            }
            setNavigationOnClickListener { finish() }
            setBackgroundColor(SURFACE_BG)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(toolbar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // === 推理设置 section ===
        content.addView(createSectionHeader("推理设置"))

        // CPU 推理开关
        val cpuRow = createSwitchRow(
            title = "使用 CPU 推理",
            subtitle = "强制使用 CPU 进行模型推理，不使用 NPU 加速"
        )
        val cpuSwitch = cpuRow.tag as MaterialSwitch
        cpuSwitch.isChecked = ConfigManager.getConfig().useCpuInference
        content.addView(cpuRow)

        // CPU 线程数设置（仅 CPU 推理时显示）
        val threadRow = createThreadSliderRow()
        threadRow.visibility = if (cpuSwitch.isChecked) View.VISIBLE else View.GONE
        content.addView(threadRow)

        cpuSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showCpuWarningDialog(cpuSwitch, threadRow)
            } else {
                ConfigManager.updateConfig { useCpuInference = false }
                JniCallBack.setForceCpu(false)
                ProjectionHolder.needsModelReload = true
                threadRow.visibility = View.GONE
                reloadModelIfServiceRunning()
            }
        }

        scrollView.addView(content)
        root.addView(scrollView)
        return root
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(16), dp(4), dp(10))
        }
    }

    private fun createSwitchRow(title: String, subtitle: String): LinearLayout {
        val switch = MaterialSwitch(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(TEXT_MAIN)
        }

        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(TEXT_SUB)
            setPadding(0, dp(2), 0, 0)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        textCol.addView(titleView)
        textCol.addView(subtitleView)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = createCardBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        row.addView(textCol)
        row.addView(switch)
        row.tag = switch
        row.setOnClickListener { switch.toggle() }
        return row
    }

    private fun createThreadSliderRow(): LinearLayout {
        val cfg = ConfigManager.getConfig()
        val currentIndex = THREAD_VALUES.indexOf(cfg.cpuThreadCount).let { if (it < 0) 2 else it } // default 4

        val titleView = TextView(this).apply {
            text = "CPU 线程数"
            textSize = 16f
            setTextColor(TEXT_MAIN)
        }

        val valueLabel = TextView(this).apply {
            text = "${THREAD_VALUES[currentIndex]} 线程"
            textSize = 14f
            setTextColor(TEXT_SUB)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(0))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel)
        }

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = (THREAD_VALUES.size - 1).toFloat()
            stepSize = 1f
            value = currentIndex.toFloat()
            setPadding(dp(12), dp(4), dp(12), dp(12))
            setLabelFormatter { value -> "${THREAD_VALUES[value.toInt()]}" }
            addOnChangeListener { _, value, _ ->
                val threads = THREAD_VALUES[value.toInt()]
                valueLabel.text = "$threads 线程"
                ConfigManager.updateConfig { cpuThreadCount = threads }
                JniCallBack.setCpuThreads(threads)
                reloadModelIfServiceRunning()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        col.addView(headerRow)
        col.addView(slider)
        return col
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(CARD_BG)
            setStroke(dp(1), BORDER)
        }
    }

    private fun reloadModelIfServiceRunning() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "RELOAD_MODEL"
            })
        }
    }

    private fun showCpuWarningDialog(cpuSwitch: MaterialSwitch, threadRow: LinearLayout) {
        MaterialAlertDialogBuilder(this)
            .setTitle("使用 CPU 推理？")
            .setMessage(
                "切换为 CPU 推理后，可能出现以下问题：\n\n" +
                "• 推理速度显著下降，帧率降低\n" +
                "• 设备发烫严重，影响游戏体验\n" +
                "• CPU 占用过高，可能导致游戏卡顿\n" +
                "• 耗电量大幅增加\n\n" +
                "仅建议在 NPU 加速不可用时使用。\n" +
                "确定切换为 CPU 推理吗？"
            )
            .setPositiveButton("确定") { _, _ ->
                ConfigManager.updateConfig { useCpuInference = true }
                JniCallBack.setForceCpu(true)
                ProjectionHolder.needsModelReload = true
                threadRow.visibility = View.VISIBLE
                reloadModelIfServiceRunning()
            }
            .setNegativeButton("取消") { _, _ ->
                cpuSwitch.isChecked = false
            }
            .setOnCancelListener {
                cpuSwitch.isChecked = false
            }
            .show()
    }
}

