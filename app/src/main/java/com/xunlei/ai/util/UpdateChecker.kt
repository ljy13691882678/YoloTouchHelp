package com.xunlei.ai.util

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseUrl: String,
    val body: String
)

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/ljy13691882678/YoloTouchHelp/releases/latest"

    fun checkLatest(context: Context, callback: (Result<ReleaseInfo?>) -> Unit) {
        val currentVersion = getCurrentVersionName(context)
        thread(name = "update-checker") {
            try {
                val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "XunleiAI-Android")
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(response)
                val tagName = json.optString("tag_name")
                val latestVersion = tagName.trimStart('v', 'V')
                val release = ReleaseInfo(
                    tagName = tagName,
                    versionName = latestVersion,
                    releaseUrl = json.optString("html_url"),
                    body = json.optString("body")
                )
                callback(Result.success(if (isNewerVersion(latestVersion, currentVersion)) release else null))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val maxSize = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val latestPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }
}
