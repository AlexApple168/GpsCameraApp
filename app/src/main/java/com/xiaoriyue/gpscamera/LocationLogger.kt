package com.xiaoriyue.gpscamera

import android.content.Context
import java.io.File

/** 每次拍照後，把時間、經緯度、地址記錄成一行純文字，存在 App 內部儲存空間 */
object LocationLogger {
    private const val FILE_NAME = "location_log.txt"
    private const val MAX_ENTRIES = 200

    fun append(context: Context, time: String, lat: Double, lon: Double, address: String) {
        val line = "$time | ${"%.6f".format(lat)}, ${"%.6f".format(lon)} | $address"
        val file = File(context.filesDir, FILE_NAME)

        val existingLines = if (file.exists()) file.readLines() else emptyList()
        val newLines = (existingLines + line).takeLast(MAX_ENTRIES)

        file.writeText(newLines.joinToString("\n") + "\n")
    }

    fun readAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "尚無拍照紀錄"
        val content = file.readText().trim()
        return content.ifBlank { "尚無拍照紀錄" }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
