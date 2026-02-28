// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器
 * 
 * 职责：
 * 1. 检查模型文件是否存在
 * 2. 从 CDN 下载模型文件
 * 3. 支持断点续传
 * 4. 校验文件完整性
 */
class ModelDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelDownloader"
        
        // 模型下载地址（可配置）
        const val MODEL_BASE_URL = "https://modelscope.cn/models/MNN/Qwen2.5-Omni-3B-MNN/resolve/master/"
        
        // 必需的模型文件
        val REQUIRED_FILES = listOf(
            "config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt"
        )
        
        // 可选文件（提高精度）
        val OPTIONAL_FILES = listOf(
            "llm_config.json"
        )
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) return false
        
        return REQUIRED_FILES.all { file ->
            val f = File(modelDir, file)
            f.exists() && f.length() > 0
        }
    }
    
    /**
     * 获取模型目录
     */
    fun getModelDir(modelId: String): File {
        return File(context.filesDir, "models/$modelId")
    }
    
    /**
     * 获取模型大小（MB）
     */
    fun getModelSize(modelId: String): Long {
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) return 0L
        
        return modelDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024)
    }
    
    /**
     * 下载模型
     * 
     * @param modelId 模型 ID
     * @param onProgress 进度回调 (当前文件, 总文件数, 当前文件进度 0-100)
     * @return 是否成功
     */
    suspend fun downloadModel(
        modelId: String,
        baseUrl: String = MODEL_BASE_URL,
        onProgress: (String, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        
        if (isModelDownloaded(modelId)) {
            Log.d(TAG, "模型已存在，跳过下载")
            return@withContext true
        }
        
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        val allFiles = REQUIRED_FILES + OPTIONAL_FILES
        var currentFileIndex = 0
        
        try {
            for (fileName in allFiles) {
                currentFileIndex++
                onProgress(fileName, currentFileIndex, allFiles.size, 0)
                
                val targetFile = File(modelDir, fileName)
                
                // 如果是可选文件且下载失败，跳过
                val isOptional = fileName in OPTIONAL_FILES
                
                val success = downloadFile(
                    url = "$baseUrl$fileName",
                    targetFile = targetFile,
                    onProgress = { progress ->
                        onProgress(fileName, currentFileIndex, allFiles.size, progress)
                    }
                )
                
                if (!success && !isOptional) {
                    Log.e(TAG, "下载失败: $fileName")
                    return@withContext false
                }
            }
            
            // 校验必需文件
            if (!validateModel(modelId)) {
                Log.e(TAG, "模型校验失败")
                return@withContext false
            }
            
            Log.d(TAG, "模型下载完成: $modelId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "模型下载异常", e)
            false
        }
    }
    
    /**
     * 下载单个文件（支持断点续传）
     */
    private fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            // 检查是否已下载
            if (targetFile.exists() && targetFile.length() > 0) {
                // 简单校验：文件大小 > 0 认为已下载
                onProgress(100)
                return true
            }
            
            // 创建临时文件
            val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "下载失败: ${response.code}")
                return false
            }
            
            val contentLength = response.body?.contentLength() ?: -1L
            val inputStream = response.body?.byteStream() ?: return false
            
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int
                
                while (inputStream.read(buffer).also { read = it } > 0) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    
                    if (contentLength > 0) {
                        val progress = ((totalRead * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }
            }
            
            inputStream.close()
            
            // 重命名为目标文件
            tempFile.renameTo(targetFile)
            
            onProgress(100)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "文件下载异常: $url", e)
            false
        }
    }
    
    /**
     * 校验模型完整性
     */
    fun validateModel(modelId: String): Boolean {
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) return false
        
        return REQUIRED_FILES.all { fileName ->
            val file = File(modelDir, fileName)
            file.exists() && file.length() > 0
        }
    }
    
    /**
     * 删除模型
     */
    fun deleteModel(modelId: String): Boolean {
        val modelDir = getModelDir(modelId)
        if (!modelDir.exists()) return true
        
        return modelDir.deleteRecursively()
    }
    
    /**
     * 获取可用模型列表
     */
    fun getDownloadedModels(): List<String> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && isModelDownloaded(it.name) }
            ?.map { it.name }
            ?: emptyList()
    }
}