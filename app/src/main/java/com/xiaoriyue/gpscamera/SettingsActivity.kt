package com.xiaoriyue.gpscamera

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val customTextInput = findViewById<EditText>(R.id.customTextInput)
        val showLatLonSwitch = findViewById<Switch>(R.id.showLatLonSwitch)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val historyText = findViewById<TextView>(R.id.historyText)
        val clearHistoryButton = findViewById<Button>(R.id.clearHistoryButton)
        val debugLogText = findViewById<TextView>(R.id.debugLogText)
        val copyLogButton = findViewById<Button>(R.id.copyLogButton)
        val clearLogButton = findViewById<Button>(R.id.clearLogButton)
        val backButton = findViewById<Button>(R.id.backButton)

        customTextInput.setText(Prefs.getCustomText(this))
        showLatLonSwitch.isChecked = Prefs.getShowLatLon(this)
        historyText.text = LocationLogger.readAll(this)
        debugLogText.text = AppLog.readAll(this)

        saveButton.setOnClickListener {
            Prefs.setCustomText(this, customTextInput.text.toString().trim())
            Prefs.setShowLatLon(this, showLatLonSwitch.isChecked)
            Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
            finish()
        }

        clearHistoryButton.setOnClickListener {
            LocationLogger.clear(this)
            historyText.text = LocationLogger.readAll(this)
            Toast.makeText(this, "已清除拍照紀錄", Toast.LENGTH_SHORT).show()
        }

        copyLogButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("診斷紀錄", AppLog.readAll(this))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "已複製診斷紀錄", Toast.LENGTH_SHORT).show()
        }

        clearLogButton.setOnClickListener {
            AppLog.clear(this)
            debugLogText.text = AppLog.readAll(this)
            Toast.makeText(this, "已清除診斷紀錄", Toast.LENGTH_SHORT).show()
        }

        backButton.setOnClickListener { finish() }
    }
}
