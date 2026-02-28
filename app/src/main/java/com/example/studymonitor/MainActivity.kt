package com.example.studymonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.studymonitor.data.SettingsManager
import com.example.studymonitor.databinding.ActivityMainBinding
import com.example.studymonitor.studymonitor.DeviceCapability
import com.example.studymonitor.studymonitor.ModelDownloader
import com.example.studymonitor.studymonitor.UnifiedVoiceService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MonitorViewModel
    private lateinit var settingsManager: SettingsManager
    private lateinit var voiceService: UnifiedVoiceService

    private val scenes = arrayOf("严格监督", "标准监督", "适度监督", "自定义")
    private val sceneIntervals = mapOf(
        "严格监督" to 60,
        "标准监督" to 120,
        "适度监督" to 300,
        "自定义" to 180
    )
    private val sceneDescriptions = mapOf(
        "严格监督" to "1分钟/次，适合考试前",
        "标准监督" to "2分钟/次，日常作业",
        "适度监督" to "5分钟/次，自主性好",
        "自定义" to "自定义间隔"
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        viewModel = ViewModelProvider(this)[MonitorViewModel::class.java]
        voiceService = UnifiedVoiceService.getInstance(this)

        setupSceneSpinner()
        setupButtons()
        observeViewModel()
        loadSettings()
        checkDeviceCapability()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        if (hasCameraPermission()) {
            initCamera()
        }
    }

    /**
     * 检测设备能力，推荐模型配置
     */
    private fun checkDeviceCapability() {
        val config = DeviceCapability.getRecommendedConfig(this)
        Log.d(TAG, "设备推荐: ${config.modelId}, 原因: ${config.reason}")
        
        // 如果推荐云端，提示用户配置 API Key
        if (!config.isLocal && settingsManager.qianwenApiKey.isBlank()) {
            binding.tvApiMode.text = "⚠️ 设备推荐云端模式，请配置 API Key"
        }
    }

    private fun initCamera() {
        lifecycleScope.launch {
            val success = viewModel.initCamera(this@MainActivity)
            if (!success) {
                runOnUiThread {
                    binding.tvStatus.text = "相机初始化失败"
                }
            }
        }
    }

    private fun setupSceneSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, scenes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerScene.adapter = adapter

        binding.spinnerScene.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val scene = scenes[position]
                binding.tvSceneDesc.text = sceneDescriptions[scene] ?: ""
                updateEstimatedCost(scene)
                
                if (scene == "自定义") {
                    val customInterval = settingsManager.customInterval
                    binding.tvSceneDesc.text = "${customInterval}秒/次，自定义"
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
    }

    private fun updateEstimatedCost(scene: String) {
        val interval = if (scene == "自定义") {
            settingsManager.customInterval
        } else {
            sceneIntervals[scene] ?: 120
        }

        val checksPerHour = 3600.0 / interval
        val costPerCheck = 0.0005
        val costPerDay = checksPerHour * costPerCheck * 4
        val costPerMonth = costPerDay * 30

        // 检查是否使用本地模型
        val modelDownloader = ModelDownloader(this)
        val hasLocalModel = modelDownloader.getDownloadedModels().isNotEmpty()

        when {
            hasLocalModel -> {
                binding.tvEstimatedCost.text = "预估费用: ¥${String.format("%.1f", costPerMonth)}/月 (视觉分析) + 语音免费(本地)"
            }
            settingsManager.apiMode == SettingsManager.API_MODE_QIANWEN -> {
                binding.tvEstimatedCost.text = "预估费用: ¥${String.format("%.1f", costPerMonth * 2)}/月 (视觉+语音云端)"
            }
            else -> {
                binding.tvEstimatedCost.text = "预估费用: 未知 (请配置 API)"
            }
        }
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.isMonitoring) {
                stopMonitoring()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnTestPhoto.setOnClickListener {
            if (!hasCameraPermission()) {
                requestCameraPermission()
            } else {
                viewModel.testPhoto()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.status.collect { status ->
                binding.tvStatus.text = status
            }
        }

        lifecycleScope.launch {
            viewModel.result.collect { result ->
                binding.tvResult.text = result
            }
        }

        lifecycleScope.launch {
            viewModel.checkCount.collect { count ->
                binding.tvCheckCount.text = "检测: $count 次"
            }
        }

        lifecycleScope.launch {
            viewModel.warningCount.collect { count ->
                binding.tvWarningCount.text = "警告: $count 次"
            }
        }

        lifecycleScope.launch {
            viewModel.isMonitoringFlow.collect { isMonitoring ->
                updateMonitoringButton(isMonitoring)
            }
        }

        lifecycleScope.launch {
            viewModel.alertMessage.collect { message ->
                if (message.isNotEmpty()) {
                    showAlertDialog(message)
                }
            }
        }
    }

    private fun loadSettings() {
        val apiMode = settingsManager.apiMode
        binding.tvApiMode.text = when (apiMode) {
            SettingsManager.API_MODE_QIANWEN -> "API模式: 通义千问 VL"
            SettingsManager.API_MODE_LOCAL -> "API模式: 本地 API"
            else -> "API模式: 未设置，请先配置"
        }

        // 设置云端 API Key 和 URL
        if (settingsManager.qianwenApiKey.isNotBlank()) {
            voiceService.setCloudApiKey(settingsManager.qianwenApiKey)
        }
        if (settingsManager.cloudApiUrl.isNotBlank()) {
            voiceService.setCloudApiUrl(settingsManager.cloudApiUrl)
        }

        updateEstimatedCost(binding.spinnerScene.selectedItem?.toString() ?: "标准监督")
    }

    private fun checkPermissionsAndStart() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotificationPermission()
        } else {
            startMonitoring()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestCameraPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun startMonitoring() {
        val apiMode = settingsManager.apiMode
        
        if (apiMode == SettingsManager.API_MODE_NONE) {
            Toast.makeText(this, "请先在设置中配置 API 模式", Toast.LENGTH_LONG).show()
            return
        }

        if (apiMode == SettingsManager.API_MODE_QIANWEN && settingsManager.qianwenApiKey.isBlank()) {
            Toast.makeText(this, "请先在设置中配置千问 API Key", Toast.LENGTH_LONG).show()
            return
        }

        if (apiMode == SettingsManager.API_MODE_LOCAL && settingsManager.localApiUrl.isBlank()) {
            Toast.makeText(this, "请先在设置中配置本地 API 地址", Toast.LENGTH_LONG).show()
            return
        }

        val scene = binding.spinnerScene.selectedItem as String
        val interval = if (scene == "自定义") {
            settingsManager.customInterval
        } else {
            sceneIntervals[scene] ?: 120
        }

        viewModel.startMonitoring(interval)
    }

    private fun stopMonitoring() {
        viewModel.stopMonitoring()
    }

    private fun updateMonitoringButton(isMonitoring: Boolean) {
        if (isMonitoring) {
            binding.btnStartStop.text = getString(R.string.stop_monitor)
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.warning))
        } else {
            binding.btnStartStop.text = getString(R.string.start_monitor)
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun showAlertDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 坐姿提醒")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}