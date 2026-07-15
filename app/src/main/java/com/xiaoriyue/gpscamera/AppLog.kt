package com.xiaoriyue.gpscamera

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 內部診斷 log。
 * 讓使用者不需要接 adb 就能在設定頁看到拍照／錄影／影片處理的過程與錯誤。
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_ENTRIES = 300
    private val timeFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN)

    @Synchronized
    fun log(context: Context, message: String) {
        try {
            val line = "${timeFormat.format(Date())} | $message"
            val file = File(context.filesDir, FILE_NAME)
            val existing = if (file.exists()) file.readLines() else emptyList()
            val newLines = (existing + line).takeLast(MAX_ENTRIES)
            file.writeText(newLines.joinToString("\n") + "\n")
        } catch (_: Exception) {
            // log 失敗不影響主功能
        }
    }

    fun readAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "尚無紀錄"
        // 最新的顯示在最上面，方便閱讀
        val content = file.readLines().reversed().joinToString("\n").trim()
        return content.ifBlank { "尚無紀錄" }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
