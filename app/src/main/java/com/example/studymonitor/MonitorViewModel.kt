package com.example.studymonitor

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.studymonitor.api.PostureAnalyzer
import com.example.studymonitor.api.PostureResult
import com.example.studymonitor.camera.CameraManager
import com.example.studymonitor.data.SettingsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settingsManager = SettingsManager(context)
    private var cameraManager: CameraManager? = null
    private lateinit var postureAnalyzer: PostureAnalyzer

    private var monitoringJob: Job? = null
    private var isCameraInitialized = false

    private val _status = MutableStateFlow("准备就绪")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _result = MutableStateFlow("检测结果将显示在这里")
    val result: StateFlow<String> = _result.asStateFlow()

    private val _checkCount = MutableStateFlow(0)
    val checkCount: StateFlow<Int> = _checkCount.asStateFlow()

    private val _warningCount = MutableStateFlow(0)
    val warningCount: StateFlow<Int> = _warningCount.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _alertMessage = MutableStateFlow("")
    val alertMessage: StateFlow<String> = _alertMessage.asStateFlow()

    val isMonitoring: Boolean
        get() = _isMonitoring.value

    suspend fun initCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner): Boolean {
        if (isCameraInitialized) return true
        
        cameraManager = CameraManager(context)
        val success = cameraManager?.initialize(lifecycleOwner) ?: false
        isCameraInitialized = success
        return success
    }

    fun startMonitoring(intervalSeconds: Int) {
        if (_isMonitoring.value) return

        // Initialize analyzer based on settings
        postureAnalyzer = when (settingsManager.apiMode) {
            SettingsManager.API_MODE_QIANWEN -> {
                PostureAnalyzer.createQianwenAnalyzer(settingsManager.qianwenApiKey)
            }
            SettingsManager.API_MODE_LOCAL -> {
                PostureAnalyzer.createLocalAnalyzer(settingsManager.localApiUrl)
            }
            else -> return
        }

        _isMonitoring.value = true
        _status.value = "正在监督中..."

        monitoringJob = viewModelScope.launch {
            while (isActive) {
                try {
                    _status.value = "正在拍照..."
                    val bitmap = cameraManager?.capturePhoto()

                    if (bitmap != null) {
                        _status.value = "正在分析..."
                        val result = postureAnalyzer.analyze(bitmap)
                        handleResult(result)
                    } else {
                        _result.value = "检测 #${_checkCount.value + 1}\n拍照失败，请检查相机权限"
                        _checkCount.value++
                    }

                    // Wait for next interval
                    var remaining = intervalSeconds
                    while (remaining > 0 && isActive) {
                        _status.value = "等待中... ${remaining}秒"
                        delay(1000)
                        remaining--
                    }
                } catch (e: Exception) {
                    _result.value = "监控错误: ${e.message}"
                    delay(5000)
                }
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        _isMonitoring.value = false
        _status.value = "监督已停止"
    }

    fun testPhoto() {
        _status.value = "正在测试拍照..."
        viewModelScope.launch {
            val bitmap = cameraManager?.capturePhoto()

            if (bitmap != null) {
                _status.value = "测试成功！图片大小: ${bitmap.width}x${bitmap.height}"
                _result.value = "相机功能正常\n图片分辨率: ${bitmap.width}x${bitmap.height}"
            } else {
                _status.value = "测试失败"
                _result.value = "相机功能异常，请检查权限或重启应用"
            }
        }
    }

    private fun handleResult(result: PostureResult) {
        _checkCount.value++

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("检测 #${_checkCount.value} [$timestamp]\n")

        if (result.error != null) {
            sb.append("错误: ${result.error}\n")
        } else {
            if (result.present == true) {
                sb.append("坐姿: ${result.posture ?: "未知"}\n")
                sb.append("注意力: ${result.attention ?: "未知"}\n")

                result.issues?.take(2)?.let { issues ->
                    sb.append("问题: ${issues.joinToString(", ")}\n")
                }

                // Check if posture needs improvement
                if (result.posture in listOf("needs_improvement", "unhealthy")) {
                    _warningCount.value++
                    val alertMsg = result.issues?.joinToString("\n") ?: "请注意坐姿"
                    _alertMessage.value = alertMsg
                }
            } else {
                sb.append("未检测到人\n")
            }
        }

        _result.value = sb.toString()
        _status.value = "上次检测: $timestamp"
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
        cameraManager?.shutdown()
    }
}