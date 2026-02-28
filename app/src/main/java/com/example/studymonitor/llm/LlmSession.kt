// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.llm

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock

/**
 * LLM 会话管理类
 * 封装 MNN-LLM 的 JNI 调用，支持文本生成和语音输出
 * 
 * 线程安全：使用 ReentrantLock 保护所有操作，支持多线程调用
 */
class LlmSession(
    private val modelId: String,
    private val configPath: String
) {
    companion object {
        private const val TAG = "LlmSession"
        
        init {
            try {
                System.loadLibrary("mnnllmapp")
                Log.d(TAG, "mnnllmapp 库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "mnnllmapp 库加载失败", e)
            }
        }
    }
    
    // 线程安全锁
    private val lock = ReentrantLock()
    
    // Native 指针
    @Volatile
    private var nativePtr: Long = 0L
    
    // 状态标志
    private val isLoaded = AtomicBoolean(false)
    private val isGenerating = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    
    // 音频监听器
    @Volatile
    private var audioListener: AudioDataListener? = null
    
    // 配置
    @Volatile
    private var enableAudioOutput: Boolean = false
    
    @Volatile
    private var maxNewTokens: Int = 512
    
    // ========== 公共 API ==========
    
    /**
     * 加载模型
     * ⚠️ 此方法较耗时，应在后台线程调用
     */
    fun load(): Boolean {
        if (isReleased.get()) {
            Log.e(TAG, "Session 已释放，无法加载")
            return false
        }
        
        if (isLoaded.get()) {
            Log.w(TAG, "模型已加载")
            return true
        }
        
        return try {
            val configFile = File(configPath)
            if (!configFile.exists()) {
                Log.e(TAG, "配置文件不存在: $configPath")
                return false
            }
            
            val configJson = buildConfigJson()
            nativePtr = initNative(configPath, null, configJson, "{}")
            
            if (nativePtr == 0L) {
                Log.e(TAG, "模型初始化失败")
                return false
            }
            
            isLoaded.set(true)
            Log.d(TAG, "模型加载成功: $modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型加载异常", e)
            false
        }
    }
    
    /**
     * 生成文本（不带语音）
     */
    fun generate(prompt: String, listener: GenerateProgressListener? = null): String {
        return generate(prompt, false, listener)
    }
    
    /**
     * 生成文本并可选生成语音
     * @param prompt 输入提示
     * @param withAudio 是否生成语音
     * @param listener 文本进度监听
     * @return 生成的文本
     */
    fun generate(
        prompt: String, 
        withAudio: Boolean = false,
        listener: GenerateProgressListener? = null
    ): String {
        checkState()
        
        if (!isLoaded.get()) {
            throw IllegalStateException("模型未加载")
        }
        
        if (!isGenerating.compareAndSet(false, true)) {
            Log.w(TAG, "已有生成任务进行中")
            return ""
        }
        
        enableAudioOutput = withAudio
        
        return try {
            val result = StringBuilder()
            
            submitNative(nativePtr, prompt, true) { progress ->
                result.append(progress)
                listener?.onProgress(progress) ?: true
            }
            
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "生成失败", e)
            ""
        } finally {
            isGenerating.set(false)
        }
    }
    
    /**
     * 设置音频输出监听器
     * 必须在 load() 之后、generate() 之前调用
     */
    fun setAudioDataListener(listener: AudioDataListener?) {
        checkState()
        this.audioListener = listener
        
        if (nativePtr != 0L && listener != null) {
            setWavformCallbackNative(nativePtr) { data, size, isEnd ->
                val floatArray = FloatArray(size)
                System.arraycopy(data, 0, floatArray, 0, size)
                audioListener?.onAudioData(floatArray, isEnd) ?: true
            }
        }
    }
    
    /**
     * 启用/禁用音频输出
     */
    fun setEnableAudioOutput(enable: Boolean) {
        checkState()
        enableAudioOutput = enable
        if (nativePtr != 0L) {
            updateEnableAudioOutputNative(nativePtr, enable)
        }
    }
    
    /**
     * 设置最大生成 token 数
     */
    fun setMaxNewTokens(tokens: Int) {
        checkState()
        maxNewTokens = tokens
        if (nativePtr != 0L) {
            updateMaxNewTokensNative(nativePtr, tokens)
        }
    }
    
    /**
     * 重置会话历史
     */
    fun reset() {
        checkState()
        if (nativePtr != 0L) {
            resetNative(nativePtr)
        }
    }
    
    /**
     * 释放资源（线程安全）
     * ⚠️ 调用后此对象不可再用
     */
    fun release() {
        lock.withLock {
            if (isReleased.get()) return
            
            isReleased.set(true)
            
            try {
                if (nativePtr != 0L) {
                    releaseNative(nativePtr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "释放 native 资源异常", e)
            } finally {
                nativePtr = 0L
                audioListener = null
                isLoaded.set(false)
                Log.d(TAG, "Session 已释放")
            }
        }
    }
    
    // ========== 状态检查 ==========
    
    private fun checkState() {
        if (isReleased.get()) {
            throw IllegalStateException("Session 已释放")
        }
    }
    
    fun isLoaded(): Boolean = isLoaded.get() && nativePtr != 0L
    
    fun isGenerating(): Boolean = isGenerating.get()
    
    // ========== 配置构建 ==========
    
    private fun buildConfigJson(): String {
        val config = JSONObject()
        config.put("backend_type", "cpu")
        config.put("thread_num", 4)
        config.put("precision", "low")
        config.put("memory", "low")
        config.put("max_new_tokens", maxNewTokens)
        
        // Omni 语音配置
        if (enableAudioOutput) {
            config.put("talker_max_new_tokens", 2048)
            config.put("talker_speaker", "Chelsie")
            config.put("dit_steps", 5)
            config.put("dit_solver", 1)
        }
        
        return config.toString()
    }
    
    // ========== JNI 方法 ==========
    
    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?
    ): Long
    
    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: (String) -> Boolean
    ): Map<String, Any>
    
    private external fun resetNative(instanceId: Long)
    
    private external fun releaseNative(instanceId: Long)
    
    private external fun setWavformCallbackNative(
        instanceId: Long,
        callback: (FloatArray, Int, Boolean) -> Boolean
    ): Boolean
    
    private external fun updateEnableAudioOutputNative(
        instanceId: Long, 
        enable: Boolean
    )
    
    private external fun updateMaxNewTokensNative(
        instanceId: Long, 
        maxNewTokens: Int
    )
}