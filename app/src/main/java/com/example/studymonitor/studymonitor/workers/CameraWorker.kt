// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor.workers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.studymonitor.studymonitor.StudyMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 定时拍照 + 视觉分析 Worker
 * 
 * 职责：
 * 1. 调用相机拍照
 * 2. 压缩图片
 * 3. 发送到视觉模型分析
 * 4. 通知 Service 处理结果
 */
class CameraWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        const val TAG = "CameraWorker"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_MAX_SIZE = "max_size"
        const val KEY_API_URL = "api_url"
        const val KEY_API_KEY = "api_key"
        
        const val DEFAULT_QUALITY = 50
        const val DEFAULT_MAX_SIZE = 200 * 1024 // 200KB
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // 1. 获取参数
            val quality = inputData.getInt(KEY_IMAGE_QUALITY, DEFAULT_QUALITY)
            val maxSize = inputData.getInt(KEY_MAX_SIZE, DEFAULT_MAX_SIZE)
            val apiUrl = inputData.getString(KEY_API_URL) ?: return@withContext Result.failure()
            
            // 2. 拍照（实际实现需要调用 CameraX）
            val imageBitmap = captureImage()
                ?: return@withContext Result.retry()
            
            // 3. 压缩图片
            val compressedImage = compressImage(imageBitmap, quality, maxSize)
            
            // 4. 发送到视觉模型
            val result = analyzeWithVisionModel(compressedImage, apiUrl)
            
            // 5. 通知 Service
            val isDistracted = result.optBoolean("is_distracted", false)
            val confidence = result.optDouble("confidence", 0.0).toFloat()
            
            StudyMonitorService.getInstance(applicationContext)?.onDistractionDetected(
                confidence = confidence,
                autoEncourage = true
            )
            
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "拍照分析失败", e)
            Result.retry()
        }
    }
    
    /**
     * 拍照
     * 使用 CameraX 实现拍照功能
     * ⚠️ 此 Worker 不直接操作相机，而是通过 Service 获取
     */
    private suspend fun captureImage(): Bitmap? {
        // CameraWorker 通过 StudyMonitorService 获取相机图片
        // 实际拍照在 Service 中通过 CameraManager 实现
        return StudyMonitorService.getInstance(applicationContext)?.capturePhoto()
    }
    
    /**
     * 压缩图片
     */
    private fun compressImage(bitmap: Bitmap, quality: Int, maxSize: Int): ByteArray {
        var compressedQuality = quality
        var outputStream = ByteArrayOutputStream()
        
        // 循环压缩直到满足大小要求
        do {
            outputStream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, compressedQuality, outputStream)
            compressedQuality -= 10
        } while (outputStream.size() > maxSize && compressedQuality > 10)
        
        return outputStream.toByteArray()
    }
    
    /**
     * 调用视觉模型分析
     */
    private suspend fun analyzeWithVisionModel(
        imageData: ByteArray,
        apiUrl: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(imageData, Base64.DEFAULT)
        
        val requestBody = """
            {
                "model": "qwen-vl-plus",
                "messages": [{
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "分析这个学生的学习状态：1. 是否在认真学习？2. 姿态是否正确？3. 是否在走神？请以JSON格式返回：{\"is_distracted\": boolean, \"confidence\": number, \"reason\": string}"
                        },
                        {
                            "type": "image",
                            "image": "$base64Image"
                        }
                    ]
                }]
            }
        """.trimIndent()
        
        val apiKey = inputData.getString(KEY_API_KEY) ?: ""
        
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API 调用失败: ${response.code}")
        }
        
        val responseBody = response.body?.string() ?: "{}"
        val jsonResponse = JSONObject(responseBody)
        
        // 解析响应，提取结果
        // 这里需要根据实际 API 返回格式解析
        parseVisionResponse(jsonResponse)
    }
    
    /**
     * 解析视觉模型响应
     */
    private fun parseVisionResponse(response: JSONObject): JSONObject {
        // TODO: 根据实际 API 格式解析
        // 临时返回模拟数据
        return JSONObject().apply {
            put("is_distracted", false)
            put("confidence", 0.9)
            put("reason", "学生正在认真学习")
        }
    }
}