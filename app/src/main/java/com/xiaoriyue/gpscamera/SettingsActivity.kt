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
        val backButton = findViewById<Button>(R.id.backButton)

        customTextInput.setText(Prefs.getCustomText(this))
        showLatLonSwitch.isChecked = Prefs.getShowLatLon(this)
        historyText.text = LocationLogger.readAll(this)

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

        backButton.setOnClickListener { finish() }
    }
}
