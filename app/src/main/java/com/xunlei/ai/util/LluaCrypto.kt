package com.xunlei.ai.util

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 微验 llua.cn API V2 加密解密工具
 *
 * 加密: RC4(key=加密密钥) → hex 小写输出
 * 签名: MD5(参数拼接 + APPKEY)
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

    /**
     * RC4 加密，hex 小写输出
     * @param plaintext 明文
     * @param encryptKey 加密密钥 (来自后台"加密密钥"，非APPKEY)
     */
    fun encrypt(plaintext: String, encryptKey: String): String {
        val key = encryptKey.toByteArray()
        val data = plaintext.toByteArray(Charsets.UTF_8)
        val encrypted = rc4(key, data)
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    /**
     * RC4 解密，hex 输入
     */
    fun decrypt(hexStr: String, encryptKey: String): String {
        val key = encryptKey.toByteArray()
        val bytes = ByteArray(hexStr.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(rc4(key, bytes), Charsets.UTF_8)
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