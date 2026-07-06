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
 * 逐行翻译官方示例伪代码:
 *   host = "http://wy.llua.cn/v2/"
 *   sign = md5("kami=" + 卡密 + "&markcode=" + 设备码 + "&t=" + 时间戳 + "&" + appkey)
 *   post_data = Base64加密("id=" + id + "&kami=" + ... + "&sign=" + sign + "&value=" + 随机数)
 *   response_data = POST请求(host + apitoken, post_data)
 *   result = Base64解密(response_data)
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LicenseManager"

        // ========== 后台参数 ==========
        private const val API_ID = "2zzEzZET"                                // 调用ID
        private const val API_KEY = "SpJRY4QXptZSa2Q"                       // 程序秘钥
        private const val API_TOKEN = "a5b495fad4ac8a85ba6c5304cc428523"    // 请求令牌
        private const val BASE_URL = "http://wy.llua.cn/v2/"

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

    // ==================== 步骤1: 计算签名 ====================
    private fun signLogin(kami: String, mark: String, ts: String) =
        md5("kami=$kami&markcode=$mark&t=$ts&$API_KEY")

    private fun signHeartbeat(kami: String, mark: String, ts: String, token: String) =
        md5("kami=$kami&markcode=$mark&t=$ts&kamitoken=$token&$API_KEY")

    // ==================== 单码登录 ====================
    suspend fun activate(kami: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = (10000000..99999999).random().toString()
            val sign = signLogin(kami, mark, ts)
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&value=$valStr"

            val (json, code) = apiCall(raw)
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
            val sign = signHeartbeat(kami, mark, ts, token)
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&kamitoken=$token&value=$valStr"

            val (json, code) = apiCall(raw)
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

    // ==================== 步骤2-5: 加密 → 发送 → 解密 → 解析 ====================

    private fun apiCall(raw: String): Pair<JSONObject, Int> {
        // 步骤2: post_data = Base64加密("id=...&kami=...&sign=...&value=...")
        val postData = b64e(raw)

        // 尝试两种格式: data=Base64 (DATA变量V1开启) / 直接Base64
        val formats = listOf(
            "data=" + postData.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D"),
            postData
        )

        for (body in formats) {
            val req = Request.Builder()
                .url(BASE_URL + API_TOKEN)
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), body))
                .build()

            val respBody = try {
                val r = http.newCall(req).execute()
                val b = r.body?.string()?.trim() ?: ""
                Log.d(TAG, "HTTP ${r.code}, resp=${b.take(300)}")
                if (b.isEmpty()) continue
                b
            } catch (e: IOException) {
                Log.e(TAG, "网络错误", e)
                continue
            }

            // 步骤4-5: 解密 → 解析
            val (json, code) = parseResponse(respBody)
            if (code == 200) return json to code
            // 100/106/107 可能是格式不对，继续尝试下一种
            if (code == 100 || code == 106 || code == 107) continue
            return json to code
        }
        return JSONObject("""{"code":-1,"msg":"API不存在"}""") to -1
    }

    private fun parseResponse(respBody: String): Pair<JSONObject, Int> {
        // 先试明文JSON
        try {
            val json = JSONObject(respBody)
            return json to json.optInt("code", -1)
        } catch (_: Exception) { }

        // 再试Base64解密
        try {
            val plaintext = b64d(respBody)
            val json = JSONObject(plaintext)
            return json to json.optInt("code", -1)
        } catch (_: Exception) { }

        // 无法解析，返回错误
        return JSONObject() to -1
    }

    private fun errMsg(json: JSONObject) = when (json.optInt("code", -1)) {
        100 -> "API不存在";  102 -> "应用已关闭";  104 -> "签名为空"
        105 -> "数据过期";   106 -> "签名错误";    107 -> "数据为空"
        108 -> "未提交时间"; 112 -> "未提交设备码"; 148 -> "卡密为空"
        149 -> "卡密不存在"; 150 -> "卡密已使用";  152 -> "卡密已到期"
        153 -> "卡密被禁用"; else -> json.optString("msg", "未知错误")
    }

    // ==================== 状态 ====================
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