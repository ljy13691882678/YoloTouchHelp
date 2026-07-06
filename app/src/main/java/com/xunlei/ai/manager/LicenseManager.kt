package com.xunlei.ai.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 微验 llua.cn 网络验证管理器 V2
 *
 * 严格逐行翻译官方文档伪代码:
 *   host = "https://wy.llua.cn/v2/"
 *   sign = md5("kami=" + 卡密 + "&markcode=" + 设备码 + "&t=" + 时间戳 + "&" + appkey)
 *   post_data = 自定义算法加密("id=" + id + "&kami=" + ... + "&sign=" + sign + "&value=" + 随机数)
 *   response_data = POST请求(host + apitoken, post_data)
 *   result = 自定义算法解密(response_data)
 *   jsonData = JSON解析(result)
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LicenseManager"

        private const val API_ID = "2zzEzZET"
        private const val API_KEY = "EFzFiRY7O3fazBRs"
        private const val API_TOKEN = "a5b495fad4ac8a85ba6c5304cc428523"
        private const val BASE_URL = "http://lvy.liua.cn/v2/"

        private const val PREFS = "llua_license"
        private const val K_ACTIVE = "active"
        private const val K_KAMI = "kami"
        private const val K_TOKEN = "token"
        private const val K_EXPIRE = "expire"
        private const val K_KM_TYPE = "km_type"

        private const val HB_MS = 30_000L

        @Volatile private var inst: LicenseManager? = null
        fun init(ctx: Context) = synchronized(this) {
            inst ?: LicenseManager(ctx.applicationContext).also { inst = it }
        }
        fun get() = inst!!
    }

    enum class State { UNACTIVATED, ACTIVE, EXPIRED }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hbJob: Job? = null

    private fun deviceId() =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: (Build.MODEL + "_" + Build.SERIAL)

    private fun md5(s: String) = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun b64e(s: String) = Base64.encodeToString(
        s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun b64d(s: String) = String(
        Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)

    // ==================== 单码登录 ====================
    suspend fun activate(kami: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = (10000000..99999999).random().toString()
            // 步骤1: sign = md5("kami=" + 卡密 + "&markcode=" + 设备码 + "&t=" + 时间戳 + "&" + appkey)
            val sign = md5("kami=$kami&markcode=$mark&t=$ts&$API_KEY")
            // 步骤2: post_data = Base64加密("id=" + id + "&kami=" + ... + "&sign=" + sign + "&value=" + 随机数)
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&value=$valStr"

            val (json, code) = doRequest(raw, "激活")
            if (code == 200) {
                val msg = json.getJSONObject("msg")
                prefs.edit().apply {
                    putString(K_KAMI, kami)
                    putString(K_TOKEN, msg.getString("token"))
                    putLong(K_EXPIRE, msg.optLong("vip", 0))
                    putBoolean(K_ACTIVE, true)
                    putString(K_KM_TYPE, msg.optString("kmtype", "day"))
                }.apply()
                startHeartbeat()
                Result.success(json)
            } else {
                Result.failure(LicenseException(code, errMsg(json)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 心跳验证 ====================
    suspend fun heartbeat(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val kami = prefs.getString(K_KAMI, "") ?: ""
            val token = prefs.getString(K_TOKEN, "") ?: ""
            if (kami.isEmpty() || token.isEmpty()) return@withContext Result.failure(IOException("未激活"))

            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = (10000000..99999999).random().toString()
            val sign = md5("kami=$kami&markcode=$mark&t=$ts&kamitoken=$token&$API_KEY")
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&kamitoken=$token&value=$valStr"

            val (json, code) = doRequest(raw, "心跳")
            if (code == 200) {
                prefs.edit().putLong(K_EXPIRE, json.getJSONObject("msg").optLong("endtime", 0)).apply()
                Result.success(json)
            } else {
                val ex = LicenseException(code, errMsg(json))
                if (code == 152 || code == 153) { prefs.edit().putBoolean(K_ACTIVE, false).apply(); stopHeartbeat() }
                Result.failure(ex)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 请求（文档步骤 3+4+5） ====================

    private fun doRequest(raw: String, label: String): Pair<JSONObject, Int> {
        // 步骤2: post_data = Base64加密(...)
        val postData = b64e(raw)

        // 步骤3: response_data = POST请求(host + apitoken, post_data)
        // 文档直接发送加密字符串，不包 data= 前缀
        val req = Request.Builder()
            .url(BASE_URL + API_TOKEN)
            .post(RequestBody.create("text/plain".toMediaType(), postData))
            .build()

        val respBody = try {
            val r = http.newCall(req).execute()
            val body = r.body?.string()?.trim() ?: ""
            Log.d(TAG, "HTTP ${r.code}, resp[0:200]=${body.take(200)}")
            if (body.isEmpty()) return JSONObject("""{"code":-1,"msg":"服务器返回空"}""") to -1
            body
        } catch (e: IOException) {
            return JSONObject("""{"code":-1,"msg":"网络错误: ${e.message}"}""") to -1
        }

        // 步骤4+5: result = Base64解密 → JSON解析
        // 先试明文JSON（服务器可能不加密响应），再试Base64解密
        try {
            val json = JSONObject(respBody)
            val code = json.optInt("code", -1)
            if (code != -1) return json to code
        } catch (_: Exception) { }

        try {
            val plaintext = b64d(respBody)
            val json = JSONObject(plaintext)
            return json to json.optInt("code", -1)
        } catch (_: Exception) { }

        return JSONObject("""{"code":-1,"msg":"${respBody.take(60)}"}""") to -1
    }

    private fun errMsg(json: JSONObject): String = when (json.optInt("code", -1)) {
        100 -> "API不存在";  102 -> "应用已关闭";  104 -> "签名为空"
        105 -> "数据过期";   106 -> "签名错误";    107 -> "数据为空"
        108 -> "未提交时间"; 112 -> "未提交设备码"; 148 -> "卡密为空"
        149 -> "卡密不存在"; 150 -> "卡密已使用";  152 -> "卡密已到期"
        153 -> "卡密被禁用"; else -> json.optString("msg", "未知错误")
    }

    fun checkState(): State {
        if (!prefs.getBoolean(K_ACTIVE, false)) return State.UNACTIVATED
        val exp = prefs.getLong(K_EXPIRE, 0)
        if (exp == 0L) return State.UNACTIVATED
        if (System.currentTimeMillis() / 1000 >= exp) { prefs.edit().putBoolean(K_ACTIVE, false).apply(); return State.EXPIRED }
        return State.ACTIVE
    }

    fun remainingSeconds() = (prefs.getLong(K_EXPIRE, 0) - System.currentTimeMillis() / 1000).coerceAtLeast(0)
    fun remainingFormatted(): String {
        val s = remainingSeconds()
        if (s <= 0) return "已到期"
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return buildString { if (d > 0) append("${d}天"); if (h > 0) append("${h}小时"); append("${m}分钟") }
    }
    fun getKmType() = prefs.getString(K_KM_TYPE, "day") ?: "day"
    fun getKami() = prefs.getString(K_KAMI, "") ?: ""

    fun startHeartbeat() { stopHeartbeat(); hbJob = scope.launch { while (isActive) { delay(HB_MS); heartbeat() } } }
    fun stopHeartbeat() { hbJob?.cancel(); hbJob = null }
    fun clear() { stopHeartbeat(); prefs.edit().clear().apply() }
    fun destroy() { stopHeartbeat(); scope.cancel() }
}

class LicenseException(val code: Int, message: String) : Exception(message)