package com.xunlei.ai.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.xunlei.ai.util.LluaCrypto
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 微验 llua.cn 网络验证管理器 V2
 *
 * 接口文档: https://app.llua.cn/setapi/v2/help/
 *
 * 参数说明:
 *   API_ID     = 调用ID (后台"应用ID"列)
 *   API_KEY    = 程序秘钥 (后台"应用秘钥"列)
 *   API_TOKEN  = 请求令牌 (后台"请求令牌"列，用于URL路径)
 *   ENC_KEY    = 加密密钥 (后台"安全配置"→"加密密钥")
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LicenseManager"

        // ========== llua.cn 后台参数 ==========
        private const val API_ID = "57549"                            // 调用ID
        private const val API_KEY = "EFzFiRY7O3fazBRs"                // 程序秘钥
        private const val API_TOKEN = "339731977c4d1901e05cc03e9a65566f" // 请求令牌
        private const val ENC_KEY = "CRaM54xWs2DxDPC"                  // 加密密钥
        private const val BASE_URL = "https://wy.llua.cn/v2/"

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

    private val crypto = LluaCrypto(API_KEY)
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

    // ==================== 卡密登录 ====================
    // 文档: sign = md5("kami=" + 卡密 + "&markcode=" + 设备码 + "&t=" + 时间戳 + "&" + APPKEY)
    suspend fun activate(kami: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = crypto.randomValue()
            val sign = crypto.calculateSign("kami=$kami", "&markcode=$mark", "&t=$ts")
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&value=$valStr"

            val result = tryAllFormats(raw)
            if (result.isSuccess) {
                val json = result.getOrThrow()
                val msg = json.getJSONObject("msg")
                prefs.edit().apply {
                    putString(K_KAMI, kami)
                    putString(K_TOKEN, msg.getString("token"))
                    putLong(K_EXPIRE, msg.optLong("vip", 0))
                    putBoolean(K_ACTIVE, true)
                    putString(K_KM_TYPE, msg.optString("kmtype", "day"))
                }.apply()
                Log.i(TAG, "激活成功")
                startHeartbeat()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "激活异常", e)
            Result.failure(e)
        }
    }

    // ==================== 心跳验证 ====================
    // 文档: sign = md5("kami=" + 卡密 + "&markcode=" + 设备码 + "&t=" + 时间戳 + "&kamitoken=" + token + "&" + APPKEY)
    suspend fun heartbeat(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val kami = prefs.getString(K_KAMI, "") ?: ""
            val token = prefs.getString(K_TOKEN, "") ?: ""
            if (kami.isEmpty() || token.isEmpty()) return@withContext Result.failure(IOException("未激活"))

            val mark = deviceId()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val valStr = crypto.randomValue()
            val sign = crypto.calculateSign("kami=$kami", "&markcode=$mark", "&t=$ts", "&kamitoken=$token")
            val raw = "id=$API_ID&kami=$kami&markcode=$mark&t=$ts&sign=$sign&kamitoken=$token&value=$valStr"

            val result = tryAllFormats(raw)
            result.onSuccess { json ->
                val et = json.getJSONObject("msg").optLong("endtime", prefs.getLong(K_EXPIRE, 0))
                prefs.edit().putLong(K_EXPIRE, et).apply()
            }
            result.onFailure { e ->
                val ex = e as? LicenseException ?: return@onFailure
                if (ex.code == 152 || ex.code == 153) {
                    prefs.edit().putBoolean(K_ACTIVE, false).apply()
                    stopHeartbeat()
                }
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 请求/响应处理 ====================

    /**
     * 尝试 3 种格式发送请求: 明文 → RC4加密 → data=加密值
     * 直到 code=200 或遇到非(100/106)的错误
     */
    private suspend fun tryAllFormats(raw: String): Result<JSONObject> {
        val formats = listOf(
            raw,                                    // 明文
            crypto.encrypt(raw, ENC_KEY),            // RC4 hex
            "data=" + crypto.encrypt(raw, ENC_KEY)   // data=加密值 (V1 DATA变量)
        )
        var lastError: JSONObject? = null
        for ((i, body) in formats.withIndex()) {
            val resp = post(body)
            if (resp.isFailure) continue
            val json = parseResponse(resp.getOrThrow().trim())
            val code = json.optInt("code", -1)
            if (code == 200) {
                Log.d(TAG, "请求成功，格式索引: $i")
                return Result.success(json)
            }
            lastError = json
            if (code != 100 && code != 106) break
        }
        val err = lastError ?: JSONObject("""{"code":-1,"msg":"所有格式均失败"}""")
        return Result.failure(LicenseException(err.optInt("code", -1), errMsg(err)))
    }

    /**
     * POST 请求，URL = BASE_URL + 请求令牌
     */
    private fun post(data: String): Result<String> {
        val body = RequestBody.create("application/x-www-form-urlencoded".toMediaType(), data)
        val req = Request.Builder().url(BASE_URL + API_TOKEN).post(body).build()
        return try {
            val r = http.newCall(req).execute()
            if (r.isSuccessful) Result.success(r.body?.string() ?: "")
            else Result.failure(IOException("HTTP ${r.code}"))
        } catch (e: IOException) { Result.failure(e) }
    }

    /**
     * 解析响应: 先尝试明文JSON，失败则 hex→RC4 解密
     */
    private fun parseResponse(body: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            Log.d(TAG, "响应非明文 JSON，尝试 hex 解密")
            JSONObject(crypto.decrypt(body, ENC_KEY))
        }
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