package com.example.studymonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.studymonitor.data.SettingsManager
import com.example.studymonitor.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        // API Mode
        when (settingsManager.apiMode) {
            SettingsManager.API_MODE_QIANWEN -> binding.rbQianwen.isChecked = true
            SettingsManager.API_MODE_LOCAL -> binding.rbLocal.isChecked = true
        }

        // API Key
        binding.etApiKey.setText(settingsManager.qianwenApiKey)

        // Cloud API URL
        binding.etCloudApiUrl.setText(settingsManager.cloudApiUrl)

        // Local API URL
        binding.etLocalApiUrl.setText(settingsManager.localApiUrl)

        // Custom interval
        binding.etCustomInterval.setText(settingsManager.customInterval.toString())

        // Update visibility based on API mode
        updateCardVisibility()
    }

    private fun setupListeners() {
        binding.rgApiMode.setOnCheckedChangeListener { _, checkedId ->
            updateCardVisibility()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun updateCardVisibility() {
        when (binding.rgApiMode.checkedRadioButtonId) {
            R.id.rbQianwen -> {
                // 千问模式：显示千问设置，隐藏本地设置
                binding.cardQianwen.visibility = android.view.View.VISIBLE
                binding.cardLocalApi.visibility = android.view.View.GONE
            }
            R.id.rbLocal -> {
                // 本地模式：隐藏千问设置，显示本地设置
                binding.cardQianwen.visibility = android.view.View.GONE
                binding.cardLocalApi.visibility = android.view.View.VISIBLE
            }
            else -> {
                // 未选择：都显示
                binding.cardQianwen.visibility = android.view.View.VISIBLE
                binding.cardLocalApi.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun saveSettings() {
        // API Mode
        val apiMode = when (binding.rgApiMode.checkedRadioButtonId) {
            R.id.rbQianwen -> SettingsManager.API_MODE_QIANWEN
            R.id.rbLocal -> SettingsManager.API_MODE_LOCAL
            else -> SettingsManager.API_MODE_NONE
        }
        settingsManager.apiMode = apiMode

        // Qianwen API Key
        settingsManager.qianwenApiKey = binding.etApiKey.text.toString()

        // Cloud API URL
        settingsManager.cloudApiUrl = binding.etCloudApiUrl.text.toString()

        // Local API URL
        settingsManager.localApiUrl = binding.etLocalApiUrl.text.toString()

        // Custom interval
        val intervalStr = binding.etCustomInterval.text.toString()
        val interval = intervalStr.toIntOrNull() ?: 180
        settingsManager.customInterval = interval.coerceIn(30, 3600) // 30秒 ~ 1小时

        lifecycleScope.launch {
            android.widget.Toast.makeText(
                this@SettingsActivity,
                "设置已保存",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        finish()
    }
}