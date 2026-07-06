package com.xunlei.ai.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 微验 llua.cn API V2 加密解密工具
 *
 * 签名: MD5(参数拼接 + APPKEY)
 * 加密: RC4(key=APPKEY) → Base64
 */
class LluaCrypto(private val appKey: String) {

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun calculateSign(vararg parts: String): String {
        val sb = StringBuilder()
        parts.forEach { sb.append(it) }
        if (sb.isNotEmpty() && sb.last() != '&') sb.append('&')
        sb.append(appKey)
        return md5(sb.toString())
    }

    fun encrypt(plaintext: String, keyStr: String): String {
        val key = (keyStr + appKey).toByteArray()
        val data = plaintext.toByteArray(Charsets.UTF_8)
        val encrypted = rc4(key, data)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(ciphertext: String, keyStr: String): String {
        val key = (keyStr + appKey).toByteArray()
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        val decrypted = rc4(key, decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    fun randomValue(): String = SecureRandom().nextInt(100000000).toString()

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0; j = 0
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
}