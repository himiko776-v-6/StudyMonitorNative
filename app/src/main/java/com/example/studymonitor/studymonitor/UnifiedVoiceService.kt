// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.studymonitor.audio.AudioPlayer
import com.example.studymonitor.llm.AudioDataListener
import com.example.studymonitor.llm.LlmSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一语音服务
 * 
 * 自动选择最佳方案：
 * 1. 本地 Omni 模型（优先，免费）
 * 2. 云端 Omni API（降级，收费）
 * 
 * ⚠️ 使用前必须调用 initialize()
 */
class UnifiedVoiceService(private val context: Context) {
    
    companion object {
        private const val TAG = "UnifiedVoiceService"
        
        @Volatile
        private var instance: UnifiedVoiceService? = null
        
        fun getInstance(context: Context): UnifiedVoiceService {
            return instance ?: synchronized(this) {
                instance ?: UnifiedVoiceService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    // ========== 服务模式 ==========
    enum class Mode {
        LOCAL,      // 本地模型
        CLOUD,      // 云端 API
        HYBRID      // 混合模式（本地优先，云端降级）
    }
    
    // ========== 状态 ==========
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val currentMode = AtomicReference(Mode.HYBRID)
    private val isInitialized = AtomicBoolean(false)
    private val isGenerating = AtomicBoolean(false)
    
    // 本地服务
    private var llmSession: LlmSession? = null
    private var audioPlayer: AudioPlayer? = null
    
    // 云端服务
    private val cloudService = CloudOmniService()
    
    // 状态流
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state
    
    private val _currentModeFlow = MutableStateFlow(Mode.HYBRID)
    val currentModeFlow: StateFlow<Mode> = _currentModeFlow
    
    // ========== 配置 API ==========
    
    /**
     * 设置云端 API Key
     */
    fun setCloudApiKey(apiKey: String) {
        cloudService.setApiKey(apiKey)
    }

    /**
     * 设置云端 API URL
     */
    fun setCloudApiUrl(url: String) {
        if (url.isNotBlank()) {
            cloudService.setApiUrl(url)
        }
    }
    
    /**
     * 设置服务模式
     */
    fun setMode(mode: Mode) {
        currentMode.set(mode)
        _currentModeFlow.value = mode
    }
    
    // ========== 初始化 ==========
    
    /**
     * 初始化服务
     * 
     * @param modelPath 本地模型路径（可选）
     * @param forceCloud 强制使用云端
     * @return 是否初始化成功
     */
    suspend fun initialize(
        modelPath: String? = null,
        forceCloud: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        
        if (isInitialized.get()) {
            Log.w(TAG, "服务已初始化")
            return@withContext true
        }
        
        _state.value = ServiceState.Initializing
        
        try {
            // 检测设备能力
            val deviceConfig = DeviceCapability.getRecommendedConfig(context)
            
            // 决定使用哪种模式
            val effectiveMode = when {
                forceCloud -> Mode.CLOUD
                modelPath.isNullOrBlank() && deviceConfig.modelType == ModelType.CLOUD -> Mode.CLOUD
                deviceConfig.modelType == ModelType.CLOUD -> Mode.CLOUD
                else -> Mode.HYBRID
            }
            
            currentMode.set(effectiveMode)
            _currentModeFlow.value = effectiveMode
            
            Log.d(TAG, "服务模式: $effectiveMode, 设备推荐: ${deviceConfig.modelId}")
            
            // 初始化本地模型（如果需要）
            if (effectiveMode != Mode.CLOUD && !modelPath.isNullOrBlank()) {
                val localSuccess = initLocalModel(modelPath)
                if (!localSuccess && effectiveMode == Mode.HYBRID) {
                    Log.w(TAG, "本地模型初始化失败，降级到云端")
                    currentMode.set(Mode.CLOUD)
                    _currentModeFlow.value = Mode.CLOUD
                }
            }
            
            // 检查云端配置
            if (currentMode.get() == Mode.CLOUD && !cloudService.isConfigured()) {
                Log.e(TAG, "云端 API 未配置")
                _state.value = ServiceState.Error("云端 API 未配置，请设置 API Key")
                return@withContext false
            }
            
            // 初始化音频播放器
            audioPlayer = AudioPlayer()
            audioPlayer?.start()
            
            isInitialized.set(true)
            _state.value = ServiceState.Ready(effectiveMode)
            
            Log.d(TAG, "服务初始化成功，模式: ${currentMode.get()}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "服务初始化失败", e)
            _state.value = ServiceState.Error("初始化失败: ${e.message}")
            false
        }
    }
    
    private fun initLocalModel(modelPath: String): Boolean {
        return try {
            val configFile = File(modelPath, "config.json")
            if (!configFile.exists()) {
                Log.e(TAG, "模型配置文件不存在: $modelPath")
                return false
            }
            
            llmSession = LlmSession(
                modelId = "Qwen2.5-Omni-3B",
                configPath = configFile.absolutePath
            )
            
            if (!llmSession!!.load()) {
                Log.e(TAG, "本地模型加载失败")
                return false
            }
            
            llmSession?.setEnableAudioOutput(true)
            
            // 设置音频回调
            llmSession?.setAudioDataListener(object : AudioDataListener {
                override fun onAudioData(data: FloatArray, isEnd: Boolean): Boolean {
                    audioPlayer?.playChunk(data)
                    if (isEnd) {
                        serviceScope.launch(Dispatchers.Main) {
                            audioPlayer?.reset()
                        }
                    }
                    return true
                }
            })
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "本地模型初始化异常", e)
            false
        }
    }
    
    // ========== 核心功能 ==========
    
    /**
     * 分析图片 + 生成语音提醒
     * 
     * @param imageBitmap 拍摄的图片
     * @param onResult 结果回调（分析文本, 提醒文本）
     * @return 是否成功
     */
    suspend fun analyzeAndRemind(
        imageBitmap: Bitmap,
        onResult: (String, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        
        if (!isInitialized.get()) {
            Log.e(TAG, "服务未初始化")
            return@withContext false
        }
        
        if (!isGenerating.compareAndSet(false, true)) {
            Log.w(TAG, "已有生成任务进行中")
            return@withContext false
        }
        
        _state.value = ServiceState.Generating
        
        try {
            val analysisPrompt = """
                分析这个学生的学习状态：
                1. 是否在认真学习？
                2. 姿态是否正确？
                3. 是否在走神？
                
                请以 JSON 格式返回：
                {"is_distracted": boolean, "confidence": number, "posture": string, "reason": string}
            """.trimIndent()
            
            val reminderPrompt = """
                你是一个温暖的学习陪伴助手。
                根据学生的学习状态分析，生成一段简短的提醒或鼓励。
                要求：2-3句话，语气温柔，不要太说教。
            """.trimIndent()
            
            when (currentMode.get()) {
                Mode.LOCAL -> {
                    // 本地模式：图片分析仍需云端，语音本地
                    val cloudResult = cloudService.analyzeImage(imageBitmap, analysisPrompt)
                    if (cloudResult.isFailure) {
                        throw cloudResult.exceptionOrNull()!!
                    }
                    
                    val analysisText = cloudResult.getOrNull() ?: ""
                    
                    // 本地生成语音
                    llmSession?.generate(reminderPrompt + "\n分析结果：$analysisText", withAudio = true)
                    
                    onResult(analysisText, "")
                }
                
                Mode.CLOUD -> {
                    // 云端模式：全流程云端
                    val result = cloudService.analyzeAndRemind(
                        imageBitmap,
                        analysisPrompt,
                        reminderPrompt
                    ) { audioData ->
                        // 播放音频
                        // 注意：云端返回的是完整音频文件，需要解码后播放
                        playAudioData(audioData)
                    }
                    
                    if (result.isFailure) {
                        throw result.exceptionOrNull()!!
                    }
                    
                    val data = result.getOrNull()!!
                    onResult(data.analysisText, data.reminderText)
                }
                
                Mode.HYBRID -> {
                    // 混合模式：优先本地，失败降级云端
                    try {
                        // 图片分析用云端
                        val cloudResult = cloudService.analyzeImage(imageBitmap, analysisPrompt)
                        if (cloudResult.isFailure) {
                            throw cloudResult.exceptionOrNull()!!
                        }
                        
                        val analysisText = cloudResult.getOrNull() ?: ""
                        
                        // 语音生成本地
                        if (llmSession?.isLoaded() == true) {
                            llmSession?.generate(reminderPrompt + "\n分析结果：$analysisText", withAudio = true)
                        } else {
                            // 降级云端语音
                            val voiceResult = cloudService.generateWithVoice(
                                reminderPrompt + "\n分析结果：$analysisText",
                                true
                            ) { audioData ->
                                playAudioData(audioData)
                            }
                        }
                        
                        onResult(analysisText, "")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "混合模式失败", e)
                        // 完全降级云端
                        val result = cloudService.analyzeAndRemind(
                            imageBitmap,
                            analysisPrompt,
                            reminderPrompt
                        ) { audioData ->
                            playAudioData(audioData)
                        }
                        
                        if (result.isSuccess) {
                            val data = result.getOrNull()!!
                            onResult(data.analysisText, data.reminderText)
                        }
                    }
                }
            }
            
            _state.value = ServiceState.Ready(currentMode.get())
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "分析生成失败", e)
            _state.value = ServiceState.Error("生成失败: ${e.message}")
            false
        } finally {
            isGenerating.set(false)
        }
    }
    
    /**
     * 生成语音提醒（仅文本输入）
     */
    suspend fun generateVoiceReminder(
        prompt: String,
        stats: StudyStats? = null
    ): Boolean = withContext(Dispatchers.IO) {
        
        if (!isInitialized.get()) {
            return@withContext false
        }
        
        if (!isGenerating.compareAndSet(false, true)) {
            return@withContext false
        }
        
        _state.value = ServiceState.Generating
        
        try {
            val fullPrompt = if (stats != null) {
                """
                    $prompt
                    
                    当前学习数据：
                    - 已学习 ${stats.studyDurationMinutes} 分钟
                    - 走神 ${stats.distractionCount} 次
                    - 休息 ${stats.breakCount} 次
                """.trimIndent()
            } else {
                prompt
            }
            
            when (currentMode.get()) {
                Mode.LOCAL, Mode.HYBRID -> {
                    if (llmSession?.isLoaded() == true) {
                        llmSession?.generate(fullPrompt, withAudio = true)
                    } else {
                        cloudService.generateWithVoice(fullPrompt, true) { audioData ->
                            playAudioData(audioData)
                        }
                    }
                }
                Mode.CLOUD -> {
                    cloudService.generateWithVoice(fullPrompt, true) { audioData ->
                        playAudioData(audioData)
                    }
                }
            }
            
            _state.value = ServiceState.Ready(currentMode.get())
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "语音生成失败", e)
            _state.value = ServiceState.Error("生成失败: ${e.message}")
            false
        } finally {
            isGenerating.set(false)
        }
    }
    
    /**
     * 播放音频数据
     * ⚠️ 云端返回的是完整 WAV 文件，需要解析 header
     */
    private fun playAudioData(audioData: ByteArray) {
        // 简化实现：直接写入临时文件，用 MediaPlayer 播放
        // 或者解析 WAV header，提取 PCM 数据
        // 这里暂时用 audioPlayer 直接播放（假设是 PCM）
        
        // TODO: 解析 WAV 格式
        serviceScope.launch(Dispatchers.Main) {
            // 临时处理：打印日志
            Log.d(TAG, "收到音频数据: ${audioData.size} bytes")
        }
    }
    
    // ========== 生命周期 ==========
    
    fun release() {
        serviceScope.cancel()
        
        llmSession?.release()
        llmSession = null
        
        audioPlayer?.release()
        audioPlayer = null
        
        isInitialized.set(false)
        _state.value = ServiceState.Idle
        
        instance = null
        
        Log.d(TAG, "服务已释放")
    }
    
    // ========== 状态查询 ==========
    
    fun isReady(): Boolean = isInitialized.get()
    
    fun isGenerating(): Boolean = isGenerating.get()
    
    fun getCurrentMode(): Mode = currentMode.get()
}

/**
 * 服务状态
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Initializing : ServiceState()
    data class Ready(val mode: UnifiedVoiceService.Mode) : ServiceState()
    object Generating : ServiceState()
    data class Error(val message: String) : ServiceState()
}