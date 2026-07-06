package com.yolotouchhelp.aimbot.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import com.yolotouchhelp.aimbot.R
import com.yolotouchhelp.aimbot.inference.JniCallBack
import com.yolotouchhelp.aimbot.manager.ConfigManager
import com.yolotouchhelp.aimbot.manager.LicenseManager
import com.yolotouchhelp.aimbot.service.FloatService
import com.yolotouchhelp.aimbot.ui.dialog.ActivationDialog
import com.yolotouchhelp.aimbot.util.ProjectionHolder
import com.yolotouchhelp.aimbot.util.ReleaseInfo
import com.yolotouchhelp.aimbot.util.UpdateChecker
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    enum class YoloTouchHelpState { STANDBY, RUNNING, INFERENCING }
    private data class HtmlDialogHandle(val dialog: AlertDialog, val webView: WebView)

    data class ModelInfo(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String,
        val classes: Map<Int, String> = emptyMap(),
        val sourcePath: String? = null
    )

    companion object {
        private const val USER_MODELS_FILE = "user_models.json"
        private const val IMPORTED_MODELS_DIR = "imported_models"
        private const val REQ_SHIZUKU = 10001
        const val ACTION_STATE_CHANGE = "com.yolotouchhelp.aimbot.STATE_CHANGE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MODEL_NAME = "model_name"
    }

    private val stateListener: (Int, String) -> Unit = { state, modelName ->
        runOnUiThread { setAppState(YoloTouchHelpState.entries[state], modelName) }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                updatePermissionStates()
                syncPageState()
            }
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        ProjectionHolder.resultCode = result.resultCode
        ProjectionHolder.resultData = data
        ProjectionHolder.modelList = modelList.map { model ->
            ProjectionHolder.ModelEntry(
                model.filename,
                model.displayName,
                model.precision,
                model.inputSize,
                model.outputSize,
                model.description,
                model.classes,
                model.sourcePath
            )
        }
        ProjectionHolder.selectedModelIndex = selectedModelIndex
        startForegroundService(Intent(this, FloatService::class.java))
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { ConfigManager.exportToUri(this, it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { ConfigManager.importFromUri(this, it) }
    }

    private val modelImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showImportModelDialog(it) }
    }

    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    private var modelList: List<ModelInfo> = emptyList()
    private var selectedModelIndex = 0
    private var appState = YoloTouchHelpState.STANDBY
    private lateinit var webView: WebView
    private var pageReady = false
    private var rootAvailable = false
    private var startupUpdateChecked = false

    private var permissionDialog: HtmlDialogHandle? = null
    private var modelPickerDialog: HtmlDialogHandle? = null
    private var disclaimerDialog: HtmlDialogHandle? = null
    private var changelogDialog: HtmlDialogHandle? = null
    private var acknowledgementsDialog: HtmlDialogHandle? = null
    private var deviceInfoDialog: HtmlDialogHandle? = null
    private var updateDialog: HtmlDialogHandle? = null
    private var currentReleaseInfo: ReleaseInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)

        // ========== 微验网络验证 ==========
        LicenseManager.init(this)
        when (LicenseManager.get().checkState()) {
            LicenseManager.State.UNACTIVATED, LicenseManager.State.EXPIRED -> {
                loadModelsFromJson()
                setContentView(R.layout.activity_main)
                bindViews()
                setupWebView()
                showActivationDialog()
                return
            }
            LicenseManager.State.ACTIVE -> {
                LicenseManager.get().startHeartbeat()
                Log.d("MainActivity", "许可证有效: ${LicenseManager.get().remainingFormatted()}")
            }
        }

        loadModelsFromJson()

        val cfgModelIndex = ConfigManager.getConfig().modelIndex
        if (cfgModelIndex !in 0 until modelList.size) {
            ConfigManager.updateConfig { modelIndex = 0 }
        }
        selectedModelIndex = if (cfgModelIndex in 0 until modelList.size) cfgModelIndex else 0
        ProjectionHolder.selectedModelIndex = selectedModelIndex

        setContentView(R.layout.activity_main)
        bindViews()
        setupWebView()

        ProjectionHolder.setStateListener(stateListener)
        ProjectionHolder.setModelIndexListener { idx ->
            runOnUiThread {
                if (idx in modelList.indices) {
                    selectedModelIndex = idx
                    syncPageState()
                }
            }
        }

        if (!isDisclaimerAccepted()) {
            showDisclaimerDialog()
        } else {
            initAfterDisclaimer()
        }
    }

    override fun onStart() {
        super.onStart()
        rootAvailable = isRootAvailable()
        if (!rootAvailable) {
            try {
                Shizuku.addRequestPermissionResultListener(shizukuListener)
            } catch (_: Exception) {
            }
            try {
                if (Shizuku.pingBinder() && !isShizukuGranted()) {
                    Shizuku.requestPermission(REQ_SHIZUKU)
                }
            } catch (_: Exception) {
            }
        }
        updatePermissionStates()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        if (ProjectionHolder.needsModelReload) {
            ProjectionHolder.needsModelReload = false
            loadDefaultModel()
        }

        val holderIndex = ProjectionHolder.selectedModelIndex
        if (holderIndex in modelList.indices && holderIndex != selectedModelIndex) {
            selectedModelIndex = holderIndex
        }
        syncStateFromHolder()
        refreshPermissionDialog()
        syncModelPickerDialogState()
    }

    override fun onStop() {
        super.onStop()
        if (!rootAvailable) {
            try {
                Shizuku.removeRequestPermissionResultListener(shizukuListener)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        ProjectionHolder.removeStateListener()
        ProjectionHolder.removeModelIndexListener()
        pageReady = false
        disclaimerDialog?.dialog?.dismiss()
        changelogDialog?.dialog?.dismiss()
        acknowledgementsDialog?.dialog?.dismiss()
        permissionDialog?.dialog?.dismiss()
        modelPickerDialog?.dialog?.dismiss()
        deviceInfoDialog?.dialog?.dismiss()
        updateDialog?.dialog?.dismiss()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("YoloTouchHelpApp")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.mainWebView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(WebAppBridge(), "YoloTouchHelpApp")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                syncPageState()
            }
        }
        webView.loadUrl("file:///android_asset/main_ui.html")
    }

    private fun showActivationDialog() {
        ActivationDialog(this, LicenseManager.get()) {
            runOnUiThread {
                Log.d("MainActivity", "激活成功，继续启动")
                if (!isDisclaimerAccepted()) showDisclaimerDialog()
                else initAfterDisclaimer()
            }
        }.show()
    }

    private fun initAfterDisclaimer() {
        android.os.Handler(mainLooper).postDelayed({ loadDefaultModel() }, 500)
        android.os.Handler(mainLooper).postDelayed({ checkForUpdatesOnStartup() }, 900)
        android.os.Handler(mainLooper).postDelayed({ checkPermissionsOnStart() }, 1500)
    }

    private fun isDisclaimerAccepted(): Boolean {
        val prefs = getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("accepted", false)
    }

    private fun setDisclaimerAccepted() {
        getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accepted", true)
            .putLong("accepted_at", System.currentTimeMillis())
            .apply()
    }

    private fun showDisclaimerDialog() {
        disclaimerDialog?.dialog?.dismiss()
        disclaimerDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_disclaimer.html",
            bridgeName = "DisclaimerHost",
            bridge = DisclaimerDialogBridge(),
            cancelable = false,
            heightRatio = 0.86f,
            onDismiss = { disclaimerDialog = null }
        )
    }

    private fun dp(value: Int): Int = (value * displayDensity + 0.5f).toInt()

    private fun onFabClick() {
        if (appState != YoloTouchHelpState.STANDBY) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ProjectionHolder.clearViews(wm)
            stopService(Intent(this, FloatService::class.java))
            ProjectionHolder.updateState(YoloTouchHelpState.STANDBY.ordinal, ProjectionHolder.currentModelName.ifEmpty { "---" })
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isInjectorAvailable()) {
            showPermissionHelpDialog()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun checkPermissionsOnStart() {
        if (!Settings.canDrawOverlays(this) || !isInjectorAvailable()) {
            showPermissionHelpDialog()
        }
    }

    private fun updatePermissionStates() {
        rootAvailable = isRootAvailable()
        syncPageState()
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun isInjectorAvailable(): Boolean {
        return rootAvailable || isShizukuGranted()
    }

    private fun getPrivilegeStatus(): String {
        if (rootAvailable) {
            return "Root"
        }
        return try {
            when {
                !Shizuku.pingBinder() -> "Shizuku 连接中"
                isShizukuGranted() -> "Shizuku 已授权"
                else -> "Shizuku 未授权"
            }
        } catch (_: Exception) {
            "Shizuku 不可用"
        }
    }

    private fun getOverlayStatus(): String {
        return if (Settings.canDrawOverlays(this)) "已开启" else "未开启"
    }

    private fun getTouchStatus(): String {
        return when {
            appState == YoloTouchHelpState.STANDBY -> "等待触摸服务"
            rootAvailable -> "Root 触摸注入"
            isShizukuGranted() -> "Shizuku 触摸注入"
            else -> "等待权限"
        }
    }

    private fun showPermissionHelpDialog() {
        permissionDialog?.dialog?.dismiss()
        permissionDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_permission.html",
            bridgeName = "PermissionHost",
            bridge = PermissionDialogBridge(),
            cancelable = true,
            heightRatio = 0.62f,
            onDismiss = { permissionDialog = null }
        )
    }

    private fun refreshPermissionDialog() {
        val handle = permissionDialog ?: return
        val privilegeStatus = getPrivilegeStatus()
        val payload = JSONObject().apply {
            put("privilegeValue", privilegeStatus)
            put("overlayValue", getOverlayStatus())
            put("canGrantPrivilege", !rootAvailable && !privilegeStatus.contains("已授权") && !privilegeStatus.contains("连接中"))
            put("canGrantOverlay", !Settings.canDrawOverlays(this@MainActivity))
        }
        handle.webView.post {
            val script = "window.PermissionDialog && window.PermissionDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun showMainMenuDialog() {
        val labels = arrayOf("导出配置", "导入配置", "导入模型", "更新日志", "高级设置")
        MaterialAlertDialogBuilder(this)
            .setTitle("更多操作")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("aimbot_config.json")
                    1 -> importLauncher.launch(arrayOf("application/json"))
                    2 -> modelImportLauncher.launch(arrayOf("application/octet-stream", "application/x-tflite", "*/*"))
                    3 -> showChangelogDialog()
                    4 -> openSettings()
                }
            }
            .show()
    }

    private fun showModelPickerDialog() {
        if (modelList.isEmpty()) {
            Toast.makeText(this, "当前没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }
        modelPickerDialog?.dialog?.dismiss()
        modelPickerDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_model_picker.html",
            bridgeName = "ModelPickerHost",
            bridge = ModelPickerDialogBridge(),
            cancelable = true,
            heightRatio = 0.72f,
            onDismiss = { modelPickerDialog = null }
        )
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun checkForUpdatesOnStartup() {
        if (startupUpdateChecked) return
        startupUpdateChecked = true
        checkForUpdates(showNoUpdateToast = false)
    }

    private fun checkForUpdates(showNoUpdateToast: Boolean) {
        if (showNoUpdateToast) Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()
        UpdateChecker.checkLatest(this) { result ->
            runOnUiThread {
                result.onSuccess { release ->
                    if (release != null) {
                        showUpdateDialog(release)
                    } else if (showNoUpdateToast) {
                        Toast.makeText(this, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { error ->
                    if (showNoUpdateToast) {
                        Toast.makeText(this, "检查更新失败: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        currentReleaseInfo = release
        updateDialog?.dialog?.dismiss()
        updateDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_update.html",
            bridgeName = "UpdateDialogHost",
            bridge = UpdateDialogBridge(),
            cancelable = true,
            heightRatio = 0.68f,
            onDismiss = {
                updateDialog = null
                currentReleaseInfo = null
            }
        )
    }

    private fun syncUpdateDialogState() {
        val handle = updateDialog ?: return
        val release = currentReleaseInfo ?: return
        val payload = JSONObject().apply {
            put("currentVersion", "v${UpdateChecker.getCurrentVersionName(this@MainActivity)}")
            put("latestVersion", release.tagName.ifBlank { "v${release.versionName}" })
            put("body", release.body.take(1000))
        }
        handle.webView.post {
            val script = "window.UpdateDialog && window.UpdateDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun showChangelogDialog() {
        changelogDialog?.dialog?.dismiss()
        changelogDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_changelog.html",
            bridgeName = "DialogHost",
            bridge = SimpleDialogBridge { changelogDialog?.dialog?.dismiss() },
            cancelable = true,
            heightRatio = 0.84f,
            onDismiss = { changelogDialog = null }
        )
    }

    private fun showAcknowledgementsDialog() {
        acknowledgementsDialog?.dialog?.dismiss()
        acknowledgementsDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_acknowledgements.html",
            bridgeName = "AcknowledgementsHost",
            bridge = AcknowledgementsDialogBridge(),
            cancelable = true,
            heightRatio = 0.84f,
            onDismiss = { acknowledgementsDialog = null }
        )
    }

    private fun showDeviceInfoDialog() {
        deviceInfoDialog?.dialog?.dismiss()
        deviceInfoDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_device_info.html",
            bridgeName = "DeviceInfoHost",
            bridge = DeviceInfoDialogBridge(),
            cancelable = true,
            heightRatio = 0.58f,
            onDismiss = { deviceInfoDialog = null }
        )
    }

    private fun exitApplication() {
        if (appState != YoloTouchHelpState.STANDBY) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ProjectionHolder.clearViews(wm)
            stopService(Intent(this, FloatService::class.java))
            ProjectionHolder.updateState(YoloTouchHelpState.STANDBY.ordinal, ProjectionHolder.currentModelName.ifEmpty { "---" })
        }
        finishAffinity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        }
    }

    private fun openExternalUrl(
        url: String,
        chooserTitle: String = "选择浏览器打开链接",
        notFoundMessage: String = "未找到可打开链接的应用",
        failureMessage: String = "打开链接失败"
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, notFoundMessage, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showHtmlDialog(
        assetPath: String,
        bridgeName: String,
        bridge: Any,
        cancelable: Boolean,
        heightRatio: Float = 0.72f,
        onDismiss: (() -> Unit)? = null
    ): HtmlDialogHandle {
        val dialogHeight = (resources.displayMetrics.heightPixels * heightRatio).toInt()
        val dialogWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            addJavascriptInterface(bridge, bridgeName)
            webViewClient = object : WebViewClient() {}
            loadUrl(assetPath)
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                dialogWebView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dialogHeight
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(cancelable)
            .create()

        dialog.setOnDismissListener {
            dialogWebView.removeJavascriptInterface(bridgeName)
            dialogWebView.destroy()
            onDismiss?.invoke()
        }

        dialog.show()
        dialogWebView.requestFocus()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            dialogHeight + dp(16)
        )
        return HtmlDialogHandle(dialog, dialogWebView)
    }

    fun setAppState(state: YoloTouchHelpState, modelName: String = ProjectionHolder.currentModelName.ifEmpty { "---" }) {
        appState = state
        ProjectionHolder.currentModelName = modelName
        syncPageState()
    }

    private fun syncStateFromHolder() {
        val stateOrdinal = ProjectionHolder.currentState.coerceIn(0, YoloTouchHelpState.entries.lastIndex)
        appState = YoloTouchHelpState.entries[stateOrdinal]
        syncPageState()
    }

    private fun syncPageState() {
        if (!pageReady || !::webView.isInitialized) return
        val payload = buildPagePayload()
        webView.post {
            if (!pageReady) return@post
            val script = "window.YoloTouchHelpUi && window.YoloTouchHelpUi.render(${JSONObject.quote(payload.toString())});"
            webView.evaluateJavascript(script, null)
        }
    }

    private fun buildPagePayload(): JSONObject {
        val model = modelList.getOrNull(selectedModelIndex)
        return JSONObject().apply {
            put("statusText", statusLabel(appState))
            put("running", appState != YoloTouchHelpState.STANDBY)
            put("versionName", getAppVersionName())
            put("backend", ProjectionHolder.currentModelName.ifEmpty { "---" })
            put("privilegeValue", getPrivilegeStatus())
            put("overlayValue", getOverlayStatus())
            put("touchValue", getTouchStatus())
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("deviceName", buildDeviceName())
            put("projectIntro", "本项目是个公益开源项目 会持续长久更新")
            put("model", buildModelJson(model))
            put("models", JSONArray().apply { modelList.forEach { put(it.displayName) } })
        }
    }

    private fun syncModelPickerDialogState() {
        val handle = modelPickerDialog ?: return
        val payload = JSONObject().apply {
            put("selectedIndex", selectedModelIndex)
            put(
                "models",
                JSONArray().apply {
                    modelList.forEachIndexed { index, model ->
                        put(
                            JSONObject().apply {
                                put("index", index)
                                put("displayName", model.displayName)
                                put("precision", model.precision)
                                put("inputSize", model.inputSize)
                                put("description", model.description)
                                put("imported", model.sourcePath != null)
                            }
                        )
                    }
                }
            )
        }
        handle.webView.post {
            val script = "window.ModelPickerDialog && window.ModelPickerDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun syncDeviceInfoDialogState() {
        val handle = deviceInfoDialog ?: return
        val payload = JSONObject().apply {
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("deviceName", buildDeviceName())
            put("deviceExtra", "${Build.MANUFACTURER.orEmpty()} / ${Build.DEVICE.orEmpty()} / ${Build.PRODUCT.orEmpty()}")
            put("appVersion", getAppVersionName())
        }
        handle.webView.post {
            val script = "window.DeviceInfoDialog && window.DeviceInfoDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun buildModelJson(model: ModelInfo?): JSONObject {
        return JSONObject().apply {
            put("displayName", model?.displayName ?: "暂无模型")
            put("precision", model?.precision ?: "-")
            put("inputSize", model?.inputSize ?: JSONObject.NULL)
            put("outputSize", model?.outputSize ?: JSONObject.NULL)
            put("description", model?.description ?: "-")
            put(
                "classesSummary",
                if (model == null || model.classes.isEmpty()) {
                    "-"
                } else {
                    model.classes.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}:${it.value}" }
                }
            )
        }
    }

    private fun statusLabel(state: YoloTouchHelpState): String {
        return when (state) {
            YoloTouchHelpState.STANDBY -> "待机中"
            YoloTouchHelpState.RUNNING -> "运行中"
            YoloTouchHelpState.INFERENCING -> "推理中"
        }
    }

    private fun getAppVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun buildDeviceName(): String {
        val brand = Build.BRAND.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL.orEmpty()
        return listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown Device" }
    }

    private fun loadModelsFromJson() {
        val builtInModels = try {
            val jsonString = assets.open("models.json").bufferedReader().use { it.readText() }
            parseModelArray(JSONObject(jsonString).getJSONArray("models"))
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "Failed to load models from JSON: ${e.message}", e)
            emptyList()
        }
        modelList = builtInModels + loadUserModels()
        ProjectionHolder.modelList = modelList.map { it.toProjectionEntry() }
        Log.d("YoloTouchHelp_AI", "Loaded ${modelList.size} models")
    }

    private fun parseModelArray(modelsArray: JSONArray): List<ModelInfo> {
        return (0 until modelsArray.length()).mapNotNull { index ->
            try {
                parseModelInfo(modelsArray.getJSONObject(index))
            } catch (e: Exception) {
                Log.e("YoloTouchHelp_AI", "模型配置解析失败: ${e.message}", e)
                null
            }
        }
    }

    private fun parseModelInfo(model: JSONObject): ModelInfo {
        val classesMap = mutableMapOf<Int, String>()
        if (model.has("classes")) {
            val classesObj = model.getJSONObject("classes")
            classesObj.keys().forEach { key -> classesMap[key.toInt()] = classesObj.getString(key) }
        }
        return ModelInfo(
            filename = model.getString("filename"),
            displayName = model.getString("displayName"),
            precision = model.optString("precision", "CUSTOM"),
            inputSize = model.optInt("inputSize", 0),
            outputSize = model.optInt("outputSize", 0),
            description = model.optString("description", "用户导入模型"),
            classes = classesMap,
            sourcePath = model.optString("sourcePath").ifBlank { null }
        )
    }

    private fun loadUserModels(): List<ModelInfo> {
        val file = File(filesDir, USER_MODELS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            parseModelArray(JSONObject(file.readText()).getJSONArray("models"))
                .filter { it.sourcePath?.let { path -> File(path).exists() } == true }
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "用户模型读取失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveUserModels() {
        val userModels = modelList.filter { it.sourcePath != null }
        val root = JSONObject().apply {
            put("models", JSONArray().apply { userModels.forEach { put(it.toJson()) } })
        }
        File(filesDir, USER_MODELS_FILE).writeText(root.toString())
    }

    private fun ModelInfo.toJson(): JSONObject {
        return JSONObject().apply {
            put("filename", filename)
            put("displayName", displayName)
            put("precision", precision)
            put("inputSize", inputSize)
            put("outputSize", outputSize)
            put("description", description)
            put("sourcePath", sourcePath)
            put("classes", JSONObject().apply {
                classes.entries.sortedBy { it.key }.forEach { put(it.key.toString(), it.value) }
            })
        }
    }

    private fun ModelInfo.toProjectionEntry(): ProjectionHolder.ModelEntry {
        return ProjectionHolder.ModelEntry(
            filename,
            displayName,
            precision,
            inputSize,
            outputSize,
            description,
            classes,
            sourcePath
        )
    }

    private fun loadModel(filename: String) {
        val model = modelList.find { it.filename == filename }
        val modelFile = resolveModelFile(model ?: ModelInfo(filename, filename, "CUSTOM", 0, 0, filename))
        try {
            val qnnCache = File(cacheDir, "qnn")
            if (qnnCache.exists()) {
                qnnCache.deleteRecursively()
            }
            qnnCache.mkdirs()
            prepareModelFile(model ?: ModelInfo(filename, filename, "CUSTOM", 0, 0, filename), modelFile)

            val config = ConfigManager.getConfig()
            JniCallBack.setForceCpu(config.useCpuInference)
            JniCallBack.setCpuThreads(config.cpuThreadCount)
            val success = JniCallBack.init(modelFile.absolutePath)
            if (success) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                syncPageState()
                Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "模型加载异常: ${e.message}", e)
        }
    }

    private fun resolveModelFile(model: ModelInfo): File {
        return model.sourcePath?.let { File(it) } ?: File(filesDir, model.filename)
    }

    private fun prepareModelFile(model: ModelInfo, modelFile: File) {
        if (model.sourcePath != null) return
        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            assets.open(model.filename).use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
        }
        if (model.filename.endsWith(".param")) {
            val binFilename = model.filename.replace(".param", ".bin")
            val binFile = File(filesDir, binFilename)
            if (!binFile.exists()) {
                assets.open(binFilename).use { input ->
                    FileOutputStream(binFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun syncModelToFloatService() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "SYNC_MODEL"
            })
        }
    }

    private fun loadDefaultModel() {
        if (modelList.isEmpty()) return
        loadModel(modelList[selectedModelIndex.coerceIn(0, modelList.lastIndex)].filename)
    }

    private fun showImportModelDialog(uri: Uri) {
        val modelNameInput = createImportInput("模型名称", queryFileName(uri).substringBeforeLast('.'))
        val inputSizeInput = createImportInput("输入尺寸，例如 256", "256").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val classesInput = createImportInput("0:head,1:body", "0:head")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(18))
            addView(createImportLabel("模型名称"))
            addView(modelNameInput)
            addView(createImportLabel("输入尺寸"))
            addView(inputSizeInput)
            addView(createImportLabel("类别"))
            addView(classesInput)
            addView(TextView(this@MainActivity).apply {
                text = "格式：类别ID:名称，多个用英文逗号分隔，例如 0:body,1:head"
                setTextColor(Color.parseColor("#6b7280"))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        }
        classesInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scrollView.postDelayed({ scrollView.smoothScrollTo(0, classesInput.bottom) }, 180)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("导入模型")
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                importModelFromUri(uri, modelNameInput.text.toString(), inputSizeInput.text.toString(), classesInput.text.toString())
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            modelNameInput.requestFocus()
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createImportLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#6b7280"))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun createImportInput(hintText: String, value: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setText(value)
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.parseColor("#1f2937"))
            setHintTextColor(Color.parseColor("#9ca3af"))
            background = null
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    private fun importModelFromUri(uri: Uri, displayName: String, inputSizeText: String, classesText: String) {
        val originalName = queryFileName(uri)
        if (!originalName.endsWith(".tflite", ignoreCase = true)) {
            Toast.makeText(this, "目前仅支持导入 .tflite 模型", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val importDir = File(filesDir, IMPORTED_MODELS_DIR).apply { mkdirs() }
            val safeName = buildImportedModelFilename(originalName)
            val targetFile = File(importDir, safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取模型文件")

            val model = ModelInfo(
                filename = safeName,
                displayName = displayName.ifBlank { originalName.substringBeforeLast('.') },
                precision = "CUSTOM",
                inputSize = inputSizeText.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                outputSize = 0,
                description = "用户导入模型",
                classes = parseClassesText(classesText),
                sourcePath = targetFile.absolutePath
            )
            modelList = modelList + model
            selectedModelIndex = modelList.lastIndex
            ProjectionHolder.modelList = modelList.map { it.toProjectionEntry() }
            ProjectionHolder.selectedModelIndex = selectedModelIndex
            ConfigManager.updateConfig { modelIndex = selectedModelIndex }
            saveUserModels()
            loadModel(model.filename)
            syncModelToFloatService()
            syncPageState()
            syncModelPickerDialogState()
            Toast.makeText(this, "模型导入成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "模型导入失败: ${e.message}", e)
            Toast.makeText(this, "模型导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteImportedModel(index: Int) {
        val model = modelList.getOrNull(index) ?: return
        val sourcePath = model.sourcePath ?: return
        val wasSelected = index == selectedModelIndex
        val file = File(sourcePath)
        if (file.exists()) file.delete()
        modelList = modelList.filterIndexed { itemIndex, _ -> itemIndex != index }
        selectedModelIndex = when {
            modelList.isEmpty() -> 0
            index < selectedModelIndex -> selectedModelIndex - 1
            wasSelected -> selectedModelIndex.coerceAtMost(modelList.lastIndex)
            else -> selectedModelIndex
        }
        ProjectionHolder.modelList = modelList.map { it.toProjectionEntry() }
        ProjectionHolder.selectedModelIndex = selectedModelIndex
        ConfigManager.updateConfig { modelIndex = selectedModelIndex }
        saveUserModels()
        if (modelList.isNotEmpty() && wasSelected) {
            loadModel(modelList[selectedModelIndex].filename)
            syncModelToFloatService()
        }
        syncPageState()
        syncModelPickerDialogState()
        Toast.makeText(this, "模型已删除", Toast.LENGTH_SHORT).show()
    }

    private fun queryFileName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "custom_model.tflite"
    }

    private fun buildImportedModelFilename(originalName: String): String {
        val base = originalName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "custom_model" }
        return "${base}_${System.currentTimeMillis()}.tflite"
    }

    private fun parseClassesText(text: String): Map<Int, String> {
        return text.split(',', '\n')
            .mapNotNull { item ->
                val parts = item.trim().split(':', limit = 2)
                val id = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val name = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "class$id" }
                id to name
            }
            .toMap()
            .ifEmpty { mapOf(0 to "head") }
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread {
                pageReady = true
                syncPageState()
            }
        }

        @JavascriptInterface
        fun toggleAimbot() {
            runOnUiThread { onFabClick() }
        }

        @JavascriptInterface
        fun showModelPicker() {
            runOnUiThread { showModelPickerDialog() }
        }

        @JavascriptInterface
        fun openPermissionHelp() {
            runOnUiThread { showPermissionHelpDialog() }
        }

        @JavascriptInterface
        fun exportConfig() {
            runOnUiThread { exportLauncher.launch("aimbot_config.json") }
        }

        @JavascriptInterface
        fun importConfig() {
            runOnUiThread { importLauncher.launch(arrayOf("application/json")) }
        }

        @JavascriptInterface
        fun importModel() {
            runOnUiThread { modelImportLauncher.launch(arrayOf("application/octet-stream", "application/x-tflite", "*/*")) }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { this@MainActivity.openSettings() }
        }

        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread { checkForUpdates(showNoUpdateToast = true) }
        }

        @JavascriptInterface
        fun openChangelog() {
            runOnUiThread { showChangelogDialog() }
        }

        @JavascriptInterface
        fun openAcknowledgements() {
            runOnUiThread { showAcknowledgementsDialog() }
        }

        @JavascriptInterface
        fun openDeviceInfo() {
            runOnUiThread { showDeviceInfoDialog() }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { exitApplication() }
        }
    }

    inner class DisclaimerDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
        }

        @JavascriptInterface
        fun accept() {
            runOnUiThread {
                setDisclaimerAccepted()
                disclaimerDialog?.dialog?.dismiss()
                initAfterDisclaimer()
            }
        }

        @JavascriptInterface
        fun reject() {
            runOnUiThread {
                disclaimerDialog?.dialog?.dismiss()
                finish()
            }
        }
    }

    inner class SimpleDialogBridge(
        private val onClose: () -> Unit
    ) {
        @JavascriptInterface
        fun close() {
            runOnUiThread { onClose() }
        }
    }

    inner class PermissionDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { refreshPermissionDialog() }
        }

        @JavascriptInterface
        fun grantPrivilege() {
            runOnUiThread {
                try {
                    if (!rootAvailable) {
                        Shizuku.requestPermission(REQ_SHIZUKU)
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Shizuku 不可用", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun grantOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { permissionDialog?.dialog?.dismiss() }
        }
    }

    inner class ModelPickerDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { syncModelPickerDialogState() }
        }

        @JavascriptInterface
        fun selectModel(index: Int) {
            runOnUiThread {
                if (index !in modelList.indices) return@runOnUiThread
                selectedModelIndex = index
                ProjectionHolder.selectedModelIndex = index
                ConfigManager.updateConfig { modelIndex = index }
                loadModel(modelList[index].filename)
                syncModelToFloatService()
                syncPageState()
                modelPickerDialog?.dialog?.dismiss()
            }
        }

        @JavascriptInterface
        fun importModel() {
            runOnUiThread { modelImportLauncher.launch(arrayOf("application/octet-stream", "application/x-tflite", "*/*")) }
        }

        @JavascriptInterface
        fun deleteModel(index: Int) {
            runOnUiThread { deleteImportedModel(index) }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { modelPickerDialog?.dialog?.dismiss() }
        }
    }

    inner class UpdateDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { syncUpdateDialogState() }
        }

        @JavascriptInterface
        fun openRelease() {
            runOnUiThread {
                val release = currentReleaseInfo ?: return@runOnUiThread
                updateDialog?.dialog?.dismiss()
                openExternalUrl(
                    url = release.releaseUrl,
                    chooserTitle = "选择浏览器打开下载页面",
                    notFoundMessage = "未找到可打开下载页面的应用",
                    failureMessage = "打开下载页面失败"
                )
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { updateDialog?.dialog?.dismiss() }
        }
    }

    inner class DeviceInfoDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { syncDeviceInfoDialogState() }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { deviceInfoDialog?.dialog?.dismiss() }
        }
    }

    inner class AcknowledgementsDialogBridge {
        @JavascriptInterface
        fun close() {
            runOnUiThread { acknowledgementsDialog?.dialog?.dismiss() }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                openExternalUrl(
                    url = url,
                    chooserTitle = "选择浏览器打开链接",
                    notFoundMessage = "未找到可打开链接的应用",
                    failureMessage = "打开链接失败"
                )
            }
        }
    }
}

