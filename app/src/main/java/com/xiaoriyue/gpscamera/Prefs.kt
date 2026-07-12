package com.xiaoriyue.gpscamera

import android.content.Context

/** 集中管理設定值（自訂文字、是否顯示經緯度數值） */
object Prefs {
    private const val PREFS_NAME = "gps_camera_prefs"
    private const val KEY_CUSTOM_TEXT = "custom_text"
    private const val KEY_SHOW_LATLON = "show_latlon"

    fun getCustomText(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_TEXT, "") ?: ""

    fun setCustomText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_CUSTOM_TEXT, text).apply()
    }

    fun getShowLatLon(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LATLON, true)

    fun setShowLatLon(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LATLON, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
