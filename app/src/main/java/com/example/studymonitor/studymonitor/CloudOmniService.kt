// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 云端 Omni API 服务
 * 
 * 用于调用阿里云百炼的全模态模型：
 * - qwen3-omni-flash（推荐）
 * - qwen3-omni-flash-2025-09-15
 * - qwen3-omni-flash-2025-12-01
 * 
 * 功能：
 * - 图片分析（视觉理解）
 * - 文本生成
 * - 语音生成（TTS）
 * 
 * ⚠️ 使用前必须设置 API Key
 */
class CloudOmniService {
    
    companion object {
        private const val TAG = "CloudOmniService"
        
        // API 配置
        const val DEFAULT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        
        // 可用模型
        const val MODEL_QWEN3_OMNI_FLASH = "qwen3-omni-flash"
        const val MODEL_QWEN3_OMNI_FLASH_SEP = "qwen3-omni-flash-2025-09-15"
        const val MODEL_QWEN3_OMNI_FLASH_DEC = "qwen3-omni-flash-2025-12-01"
        
        // 语音配置
        const val VOICE_CHELSIE = "Chelsie"  // 女声
        const val VOICE_ETHAN = "Ethan"       // 男声
    }
    
    // HTTP 客户端
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    // 配置（原子操作保证线程安全）
    private val apiKey = AtomicReference<String>("")
    private val apiUrl = AtomicReference(DEFAULT_API_URL)
    private val modelName = AtomicReference(MODEL_QWEN3_OMNI_FLASH)
    private val voiceName = AtomicReference(VOICE_CHELSIE)
    
    // 状态
    private val isGenerating = AtomicBoolean(false)
    
    // ========== 配置 API ==========
    
    /**
     * 设置 API Key
     */
    fun setApiKey(key: String) {
        apiKey.set(key)
    }
    
    /**
     * 设置 API URL（可选，默认使用阿里云）
     */
    fun setApiUrl(url: String) {
        apiUrl.set(url)
    }
    
    /**
     * 设置模型名称
     */
    fun setModel(model: String) {
        modelName.set(model)
    }
    
    /**
     * 设置语音音色
     */
    fun setVoice(voice: String) {
        voiceName.set(voice)
    }
    
    // ========== 核心功能 ==========
    
    /**
     * 分析图片（视觉理解）
     * 
     * @param imageBitmap 图片
     * @param prompt 提示词
     * @param maxSize 图片最大尺寸（字节）
     * @param quality 压缩质量（0-100）
     * @return 分析结果文本
     */
    suspend fun analyzeImage(
        imageBitmap: Bitmap,
        prompt: String,
        maxSize: Int = 200 * 1024,
        quality: Int = 50
    ): Result<String> = withContext(Dispatchers.IO) {
        
        if (apiKey.get().isBlank()) {
            return@withContext Result.failure(Exception("API Key 未设置"))
        }
        
        if (!isGenerating.compareAndSet(false, true)) {
            return@withContext Result.failure(Exception("已有请求进行中"))
        }
        
        try {
            // 1. 压缩图片
            val compressedImage = compressImage(imageBitmap, quality, maxSize)
            val base64Image = Base64.encodeToString(compressedImage, Base64.DEFAULT)
            
            // 2. 构建请求
            val requestBody = buildVisionRequest(prompt, base64Image)
            
            // 3. 发送请求
            val response = sendRequest(requestBody)
            
            // 4. 解析响应
            val result = parseTextResponse(response)
            
            Result.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "图片分析失败", e)
            Result.failure(e)
        } finally {
            isGenerating.set(false)
        }
    }
    
    /**
     * 生成语音（文本 + 语音输出）
     * 
     * @param text 输入文本
     * @param withAudio 是否生成语音
     * @param onAudioData 音频数据回调
     * @return 生成结果（文本 + 音频 URL）
     */
    suspend fun generateWithVoice(
        text: String,
        withAudio: Boolean = true,
        onAudioData: ((ByteArray) -> Unit)? = null
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        
        if (apiKey.get().isBlank()) {
            return@withContext Result.failure(Exception("API Key 未设置"))
        }
        
        if (!isGenerating.compareAndSet(false, true)) {
            return@withContext Result.failure(Exception("已有请求进行中"))
        }
        
        try {
            // 构建请求
            val requestBody = buildAudioRequest(text, withAudio)
            
            // 发送请求
            val response = sendRequest(requestBody)
            
            // 解析响应
            val result = parseAudioResponse(response)
            
            // 下载音频数据
            if (result.audioUrl != null && onAudioData != null) {
                val audioData = downloadAudio(result.audioUrl)
                onAudioData(audioData)
            }
            
            Result.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "语音生成失败", e)
            Result.failure(e)
        } finally {
            isGenerating.set(false)
        }
    }
    
    /**
     * 综合功能：分析图片 + 生成语音提醒
     * 
     * @param imageBitmap 图片
     * @param analysisPrompt 分析提示词
     * @param reminderPrompt 提醒生成提示词
     * @param onAudioData 音频回调
     * @return 分析结果 + 生成的提醒文本
     */
    suspend fun analyzeAndRemind(
        imageBitmap: Bitmap,
        analysisPrompt: String,
        reminderPrompt: String,
        onAudioData: ((ByteArray) -> Unit)? = null
    ): Result<AnalyzeAndRemindResult> = withContext(Dispatchers.IO) {
        
        // 1. 分析图片
        val analysisResult = analyzeImage(imageBitmap, analysisPrompt)
        if (analysisResult.isFailure) {
            return@withContext Result.failure(analysisResult.exceptionOrNull()!!)
        }
        
        val analysisText = analysisResult.getOrNull() ?: ""
        
        // 2. 生成提醒
        val fullPrompt = "$reminderPrompt\n\n分析结果：$analysisText"
        val reminderResult = generateWithVoice(fullPrompt, true, onAudioData)
        
        if (reminderResult.isFailure) {
            return@withContext Result.failure(reminderResult.exceptionOrNull()!!)
        }
        
        val reminder = reminderResult.getOrNull()!!
        
        Result.success(AnalyzeAndRemindResult(
            analysisText = analysisText,
            reminderText = reminder.text,
            audioUrl = reminder.audioUrl
        ))
    }
    
    // ========== 内部实现 ==========
    
    private fun compressImage(bitmap: Bitmap, quality: Int, maxSize: Int): ByteArray {
        var compressedQuality = quality
        var outputStream = ByteArrayOutputStream()
        
        do {
            outputStream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, compressedQuality, outputStream)
            compressedQuality -= 10
        } while (outputStream.size() > maxSize && compressedQuality > 10)
        
        return outputStream.toByteArray()
    }
    
    private fun buildVisionRequest(prompt: String, base64Image: String): String {
        return JSONObject().apply {
            put("model", modelName.get())
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }.toString()
    }
    
    private fun buildAudioRequest(text: String, withAudio: Boolean): String {
        return JSONObject().apply {
            put("model", modelName.get())
            put("modalities", JSONArray().apply {
                put("text")
                if (withAudio) put("audio")
            })
            put("audio", JSONObject().apply {
                put("voice", voiceName.get())
                put("format", "wav")
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }.toString()
    }
    
    private fun sendRequest(requestBody: String): String {
        val request = Request.Builder()
            .url(apiUrl.get())
            .addHeader("Authorization", "Bearer ${apiKey.get()}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API 请求失败: ${response.code} ${response.message}")
        }
        
        return response.body?.string() ?: throw Exception("响应体为空")
    }
    
    private fun parseTextResponse(response: String): String {
        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) {
            throw Exception("响应无结果")
        }
        
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content")
    }
    
    private fun parseAudioResponse(response: String): GenerateResult {
        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) {
            throw Exception("响应无结果")
        }
        
        val message = choices.getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", "")
        
        // 提取音频 URL（如果存在）
        val audioUrl = message.optJSONObject("audio")?.optString("url")
        
        return GenerateResult(
            text = text,
            audioUrl = audioUrl
        )
    }
    
    private fun downloadAudio(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("音频下载失败: ${response.code}")
        }
        
        return response.body?.bytes() ?: throw Exception("音频数据为空")
    }
    
    // ========== 状态查询 ==========
    
    fun isGenerating(): Boolean = isGenerating.get()
    
    fun isConfigured(): Boolean = apiKey.get().isNotBlank()
}

/**
 * 生成结果
 */
data class GenerateResult(
    val text: String,
    val audioUrl: String? = null,
    val audioData: ByteArray? = null
)

/**
 * 分析 + 提醒结果
 */
data class AnalyzeAndRemindResult(
    val analysisText: String,
    val reminderText: String,
    val audioUrl: String? = null
)