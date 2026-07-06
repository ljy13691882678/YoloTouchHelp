package com.xunlei.ai.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
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
 * 加密: BASE64加密 (标准)
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LicenseManager"

        private const val API_ID = "2zzEzZET"                                // 调用ID
        private const val API_KEY = "EFzFiRY7O3fazBRs"                     // 程序秘钥
        private const val API_TOKEN = "a5b495fad4ac8a85ba6c5304cc428523"   // 请求令牌
        private const val BASE_URL = "http://wy.llua.cn/v2/"

        private const val PREFS = "llua_license"
        private const val K_ACTIVE = "active"
        private const val K_KAMI = "kami"
        private const val K_TOKEN = "token"
        private const val K_EXPIRE = "expire"
        private const val K_KM_TYPE = "km_type"

        private const val HB_MS = 30_000L

        @Volatile private var inst: LicenseManager? = null

        fun init(ctx: Context): LicenseManager = inst ?: synchronized(this) {
            inst ?: LicenseManager(ctx.applicationContext).also { inst = it }
        }
        fun get(): LicenseManager = inst!!
    }

    enum class State { UNACTIVATED, ACTIVE, EXPIRED }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hbJob: Job? = null

    private fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: (Build.MODEL + "_" + Build.SERIAL)

    private fun md5(s: String) = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun base64Encode(data: String) = android.util.Base64.encodeToString(
        data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun base64Decode(data: String) = String(
        android.util.Base64.decode(data, android.util.Base64.NO_WRAP), Charsets.UTF_8)

    // ==================== 卡密登录 ====================
    suspend fun activate(kami: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = (10000000..99999999).random().toString()
            val sign = md5("kami=$kami&markcode=$mark&t=$ts&$API_KEY")
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
            Log.e(TAG, "激活异常", e)
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
                val et = json.getJSONObject("msg").optLong("endtime", prefs.getLong(K_EXPIRE, 0))
                prefs.edit().putLong(K_EXPIRE, et).apply()
                Result.success(json)
            } else {
                val ex = LicenseException(code, errMsg(json))
                if (code == 152 || code == 153) {
                    prefs.edit().putBoolean(K_ACTIVE, false).apply()
                    stopHeartbeat()
                }
                Result.failure(ex)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 请求 ====================

    private fun doRequest(raw: String, label: String): Pair<JSONObject, Int> {
        // Base64加密
        val encrypted = base64Encode(raw)

        // 格式A: data=Base64 (DATA变量V1开启)
        // 格式B: 直接Base64 (文档标准)
        // 全部使用 text/plain 避免 URL 编码破坏 Base64
        val formats = listOf("data=$encrypted", encrypted)

        for (body in formats) {
            val resp = tryPost(body, "text/plain")
            if (resp == null) continue

            val (json, code) = tryParse(resp)
            if (code == 200) {
                Log.d(TAG, "$label success: code=$code")
                return json to code
            }
            // 100/106/107 可能是格式不对，继续尝试下一种
            if (code == 100 || code == 106 || code == 107) continue
            // 其他错误直接返回（卡密不存在等）
            return json to code
        }
        return JSONObject("""{"code":-1,"msg":"所有格式均失败"}""") to -1
    }

    private fun tryPost(body: String, contentType: String): String? {
        val reqBody = RequestBody.create(contentType.toMediaType(), body)
        val req = Request.Builder().url(BASE_URL + API_TOKEN).post(reqBody).build()
        return try {
            val r = http.newCall(req).execute()
            val resp = r.body?.string()?.trim() ?: ""
            Log.d(TAG, "HTTP ${r.code}, resp[0:200]=${resp.take(200)}")
            if (resp.isEmpty()) null else resp
        } catch (e: IOException) {
            Log.e(TAG, "网络错误", e)
            null
        }
    }

    /**
     * 解析响应: 先试明文JSON → 再试Base64解密
     */
    private fun tryParse(respBody: String): Pair<JSONObject, Int> {
        // 先试明文 JSON
        try {
            val json = JSONObject(respBody)
            return json to json.optInt("code", -1)
        } catch (_: Exception) { }

        // 再试 Base64 解密
        try {
            val plaintext = base64Decode(respBody)
            val json = JSONObject(plaintext)
            return json to json.optInt("code", -1)
        } catch (_: Exception) { }

        return JSONObject("""{"code":-1,"msg":"${respBody.take(60)}"}""") to -1
    }

    private fun errMsg(json: JSONObject): String = when (json.optInt("code", -1)) {
        100 -> "未绑定应用ID";  102 -> "应用已关闭";  104 -> "签名为空"
        105 -> "数据过期";      106 -> "签名错误";    107 -> "数据为空"
        108 -> "未提交时间";    112 -> "未提交设备码"; 148 -> "卡密为空"
        149 -> "卡密不存在";    150 -> "卡密已使用";  152 -> "卡密已到期"
        153 -> "卡密被禁用";    else -> json.optString("msg", "未知错误")
    }

    // ---------- 状态查询 ----------
    fun checkState(): State {
        if (!prefs.getBoolean(K_ACTIVE, false)) return State.UNACTIVATED
        val exp = prefs.getLong(K_EXPIRE, 0)
        if (exp == 0L) return State.UNACTIVATED
        if (System.currentTimeMillis() / 1000 >= exp) {
            prefs.edit().putBoolean(K_ACTIVE, false).apply()
            return State.EXPIRED
        }
        return State.ACTIVE
    }

    fun remainingSeconds(): Long = (prefs.getLong(K_EXPIRE, 0) - System.currentTimeMillis() / 1000).coerceAtLeast(0)

    fun remainingFormatted(): String {
        val s = remainingSeconds()
        if (s <= 0) return "已到期"
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return buildString {
            if (d > 0) append("${d}天")
            if (h > 0) append("${h}小时")
            append("${m}分钟")
        }
    }

    fun getKmType() = prefs.getString(K_KM_TYPE, "day") ?: "day"
    fun getKami() = prefs.getString(K_KAMI, "") ?: ""

    fun startHeartbeat() {
        stopHeartbeat()
        hbJob = scope.launch {
            while (isActive) { delay(HB_MS); heartbeat() }
        }
    }

    fun stopHeartbeat() { hbJob?.cancel(); hbJob = null }
    fun clear() { stopHeartbeat(); prefs.edit().clear().apply() }
    fun destroy() { stopHeartbeat(); scope.cancel() }
}

class LicenseException(val code: Int, message: String) : Exception(message)