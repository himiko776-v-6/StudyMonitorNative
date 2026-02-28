// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor

import android.content.Context
import android.util.Log
import com.example.studymonitor.audio.AudioPlayer
import com.example.studymonitor.llm.AudioDataListener
import com.example.studymonitor.llm.LlmSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 学习监督服务
 * 
 * 核心功能：
 * 1. 管理学习会话（开始/停止）
 * 2. 统计数据收集（学习时长、走神次数等）
 * 3. 语音提醒生成（休息提醒、走神鼓励、每日总结）
 * 4. MNN-LLM 生命周期管理
 * 
 * ⚠️ 使用注意：
 * - initialize() 必须在后台线程调用
 * - 所有公开方法都是线程安全的
 * - 使用完毕必须调用 release()
 */
class StudyMonitorService(private val context: Context) {
    
    companion object {
        private const val TAG = "StudyMonitorService"
        
        // 默认配置
        const val DEFAULT_BREAK_INTERVAL_MINUTES = 20
        const val DEFAULT_DISTRACTION_THRESHOLD = 3
        
        // 单例
        @Volatile
        private var instance: StudyMonitorService? = null
        
        fun getInstance(context: Context): StudyMonitorService {
            return instance ?: synchronized(this) {
                instance ?: StudyMonitorService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    // ========== 协程作用域 ==========
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ========== LLM 相关 ==========
    private var llmSession: LlmSession? = null
    private var audioPlayer: AudioPlayer? = null
    private val isLlmReady = AtomicBoolean(false)
    
    // ========== 学习会话状态 ==========
    private var currentSessionId: String = ""
    private var sessionStartTime: Long = 0L
    
    // 统计数据（原子操作保证线程安全）
    private val distractionCount = AtomicInteger(0)
    private val breakCount = AtomicInteger(0)
    private val studyDurationMinutes = AtomicInteger(0)
    
    // 状态流（供 UI 观察）
    private val _state = MutableStateFlow<StudyMonitorState>(StudyMonitorState.Idle)
    val state: StateFlow<StudyMonitorState> = _state
    
    private val _currentStats = MutableStateFlow(StudyStats())
    val currentStats: StateFlow<StudyStats> = _currentStats
    
    // ========== 配置 ==========
    var breakIntervalMinutes: Int = DEFAULT_BREAK_INTERVAL_MINUTES
    var distractionThreshold: Int = DEFAULT_DISTRACTION_THRESHOLD
    
    // ========== 公共 API ==========
    
    /**
     * 初始化服务
     * 
     * ⚠️ 此方法较耗时（加载模型约 10-30 秒），必须在后台线程调用
     * 
     * @param modelPath 模型目录路径，应包含 config.json, llm.mnn, llm.mnn.weight, tokenizer.txt
     * @return 是否初始化成功
     */
    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (isLlmReady.get()) {
            Log.w(TAG, "服务已初始化")
            return@withContext true
        }
        
        _state.value = StudyMonitorState.Initializing
        
        try {
            // 1. 检查模型文件
            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                Log.e(TAG, "模型目录不存在: $modelPath")
                _state.value = StudyMonitorState.Error("模型目录不存在")
                return@withContext false
            }
            
            val configFile = File(modelDir, "config.json")
            if (!configFile.exists()) {
                Log.e(TAG, "配置文件不存在")
                _state.value = StudyMonitorState.Error("配置文件不存在")
                return@withContext false
            }
            
            // 2. 初始化 LLM 会话
            Log.d(TAG, "开始加载 LLM 模型...")
            llmSession = LlmSession(
                modelId = "Qwen2.5-Omni-3B",
                configPath = configFile.absolutePath
            )
            
            if (!llmSession!!.load()) {
                Log.e(TAG, "LLM 模型加载失败")
                _state.value = StudyMonitorState.Error("模型加载失败")
                return@withContext false
            }
            
            // 3. 启用音频输出
            llmSession?.setEnableAudioOutput(true)
            
            // 4. 初始化音频播放器
            audioPlayer = AudioPlayer()
            if (!audioPlayer!!.start()) {
                Log.w(TAG, "音频播放器初始化失败，语音提醒将不可用")
            }
            
            // 5. 设置音频回调
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
            
            isLlmReady.set(true)
            _state.value = StudyMonitorState.Ready
            Log.d(TAG, "服务初始化成功")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "服务初始化异常", e)
            _state.value = StudyMonitorState.Error("初始化异常: ${e.message}")
            false
        }
    }
    
    /**
     * 开始学习会话
     */
    fun startStudySession(): Boolean {
        if (!isLlmReady.get()) {
            Log.e(TAG, "服务未初始化")
            return false
        }
        
        if (_state.value == StudyMonitorState.Studying) {
            Log.w(TAG, "已有学习会话进行中")
            return false
        }
        
        currentSessionId = System.currentTimeMillis().toString()
        sessionStartTime = System.currentTimeMillis()
        distractionCount.set(0)
        breakCount.set(0)
        studyDurationMinutes.set(0)
        
        _state.value = StudyMonitorState.Studying
        _currentStats.value = StudyStats()
        
        Log.d(TAG, "学习会话开始: $currentSessionId")
        return true
    }
    
    /**
     * 停止学习会话
     */
    fun stopStudySession(): StudyStats {
        if (_state.value != StudyMonitorState.Studying) {
            return _currentStats.value
        }
        
        val duration = ((System.currentTimeMillis() - sessionStartTime) / 60000).toInt()
        
        val finalStats = StudyStats(
            studyDurationMinutes = duration,
            distractionCount = distractionCount.get(),
            breakCount = breakCount.get(),
            totalStudyMinutes = duration
        )
        
        _state.value = StudyMonitorState.Ready
        _currentStats.value = finalStats
        
        Log.d(TAG, "学习会话结束: $currentSessionId, 统计: $finalStats")
        return finalStats
    }
    
    /**
     * 记录走神事件
     * 
     * @param confidence 检测置信度 (0-1)
     * @param autoEncourage 是否自动生成鼓励语音（达到阈值时）
     */
    fun onDistractionDetected(confidence: Float = 1.0f, autoEncourage: Boolean = true) {
        if (_state.value != StudyMonitorState.Studying) {
            return
        }
        
        val count = distractionCount.incrementAndGet()
        Log.d(TAG, "走神检测: 第${count}次, 置信度: $confidence")
        
        // 更新统计
        _currentStats.value = _currentStats.value.copy(
            distractionCount = count
        )
        
        // 达到阈值时自动鼓励
        if (autoEncourage && count > 0 && count % distractionThreshold == 0) {
            generateEncouragement()
        }
    }
    
    /**
     * 更新学习时长（每分钟调用）
     */
    fun updateStudyDuration(minutes: Int) {
        if (_state.value != StudyMonitorState.Studying) {
            return
        }
        
        studyDurationMinutes.set(minutes)
        _currentStats.value = _currentStats.value.copy(
            studyDurationMinutes = minutes
        )
        
        // 每 N 分钟提醒休息
        if (minutes > 0 && minutes % breakIntervalMinutes == 0) {
            generateBreakReminder()
        }
    }
    
    /**
     * 生成休息提醒语音
     */
    fun generateBreakReminder() {
        if (!isLlmReady.get() || llmSession?.isGenerating() == true) {
            return
        }
        
        val stats = _currentStats.value
        val prompt = """
            你是一个温暖的学习陪伴助手。小朋友已经学习了${stats.studyDurationMinutes}分钟，
            走神了${stats.distractionCount}次，休息了${stats.breakCount}次。
            
            请用温柔简短的话语提醒休息眼睛，控制在2-3句话，可以加一句关怀的话。
        """.trimIndent()
        
        serviceScope.launch {
            _state.value = StudyMonitorState.GeneratingVoice
            try {
                llmSession?.generate(prompt, withAudio = true)
                breakCount.incrementAndGet()
                _currentStats.value = _currentStats.value.copy(
                    breakCount = breakCount.get()
                )
            } catch (e: Exception) {
                Log.e(TAG, "生成休息提醒失败", e)
            } finally {
                _state.value = StudyMonitorState.Studying
            }
        }
    }
    
    /**
     * 生成走神鼓励语音
     */
    fun generateEncouragement() {
        if (!isLlmReady.get() || llmSession?.isGenerating() == true) {
            return
        }
        
        val prompt = """
            你是一个温暖的学习陪伴助手。小朋友刚才走神了，
            这是今天第${distractionCount.get()}次走神。
            
            请用温柔简短的话语鼓励他/她继续专注，控制在1-2句话，不要太说教。
        """.trimIndent()
        
        serviceScope.launch {
            _state.value = StudyMonitorState.GeneratingVoice
            try {
                llmSession?.generate(prompt, withAudio = true)
            } catch (e: Exception) {
                Log.e(TAG, "生成鼓励失败", e)
            } finally {
                _state.value = StudyMonitorState.Studying
            }
        }
    }
    
    /**
     * 生成每日学习总结
     * 
     * @return 总结文本
     */
    suspend fun generateDailySummary(): String = withContext(Dispatchers.IO) {
        if (!isLlmReady.get()) {
            return@withContext "服务未初始化"
        }
        
        val stats = _currentStats.value
        val prompt = """
            你是一个温暖的学习陪伴助手。请根据今天的学习数据给小朋友写一段简短的总结。
            
            今日学习数据：
            - 学习总时长：${stats.totalStudyMinutes}分钟
            - 走神次数：${stats.distractionCount}次
            - 休息次数：${stats.breakCount}次
            
            请用3-4句话总结，先肯定努力，再温和指出改进空间，最后给一句鼓励。
        """.trimIndent()
        
        _state.value = StudyMonitorState.GeneratingVoice
        
        val result = try {
            llmSession?.generate(prompt, withAudio = true) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "生成总结失败", e)
            "生成总结失败"
        } finally {
            _state.value = StudyMonitorState.Ready
        }
        
        result
    }
    
    /**
     * 释放资源
     */
    fun release() {
        serviceScope.cancel()
        
        llmSession?.release()
        llmSession = null
        
        audioPlayer?.release()
        audioPlayer = null
        
        isLlmReady.set(false)
        _state.value = StudyMonitorState.Idle
        
        instance = null
        
        Log.d(TAG, "服务已释放")
    }
    
    // ========== 状态检查 ==========
    
    fun isReady(): Boolean = isLlmReady.get()
    
    fun isStudying(): Boolean = _state.value == StudyMonitorState.Studying
}

/**
 * 学习监督状态
 */
sealed class StudyMonitorState {
    object Idle : StudyMonitorState()
    object Initializing : StudyMonitorState()
    object Ready : StudyMonitorState()
    object Studying : StudyMonitorState()
    object GeneratingVoice : StudyMonitorState()
    data class Error(val message: String) : StudyMonitorState()
}

/**
 * 学习统计数据
 */
data class StudyStats(
    val studyDurationMinutes: Int = 0,
    val distractionCount: Int = 0,
    val breakCount: Int = 0,
    val totalStudyMinutes: Int = 0,
    val progressPercent: Int = 0
)