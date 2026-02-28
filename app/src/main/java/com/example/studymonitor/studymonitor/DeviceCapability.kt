// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * 设备能力检测工具
 * 
 * 用于判断设备是否能运行本地模型，以及推荐最佳配置
 */
object DeviceCapability {
    
    private const val TAG = "DeviceCapability"
    
    // 内存阈值（GB）
    const val MIN_MEMORY_3B = 5    // 3B 模型最低要求
    const val MIN_MEMORY_7B = 8    // 7B 模型最低要求
    const val RECOMMENDED_MEMORY_3B = 6
    const val RECOMMENDED_MEMORY_7B = 12
    
    // 存储空间阈值（GB）
    const val MIN_STORAGE_3B = 4   // 3B 模型约 3GB
    const val MIN_STORAGE_7B = 9   // 7B 模型约 7GB
    
    /**
     * 获取设备总内存（GB）
     */
    fun getTotalMemoryGB(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }
    
    /**
     * 获取可用内存（GB）
     */
    fun getAvailableMemoryGB(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
    }
    
    /**
     * 获取可用存储空间（GB）
     */
    fun getAvailableStorageGB(): Double {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val availableBlocks = stat.availableBlocksLong
        return (availableBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
    }
    
    /**
     * 检测设备是否支持本地模型
     */
    fun canRunLocalModel(context: Context): Boolean {
        val memGB = getTotalMemoryGB(context)
        val storageGB = getAvailableStorageGB()
        
        Log.d(TAG, "设备内存: ${String.format("%.1f", memGB)}GB, 存储: ${String.format("%.1f", storageGB)}GB")
        
        return memGB >= MIN_MEMORY_3B && storageGB >= MIN_STORAGE_3B
    }
    
    /**
     * 获取推荐的模型配置
     */
    fun getRecommendedConfig(context: Context): ModelConfig {
        val memGB = getTotalMemoryGB(context)
        val storageGB = getAvailableStorageGB()
        
        return when {
            memGB >= RECOMMENDED_MEMORY_7B && storageGB >= MIN_STORAGE_7B -> {
                ModelConfig(
                    modelType = ModelType.OMNI_7B,
                    modelId = "Qwen2.5-Omni-7B-MNN",
                    estimatedSizeGB = 7.5,
                    recommended = true,
                    reason = "高端设备，推荐使用 7B 模型获得更好效果"
                )
            }
            memGB >= RECOMMENDED_MEMORY_3B && storageGB >= MIN_STORAGE_3B -> {
                ModelConfig(
                    modelType = ModelType.OMNI_3B,
                    modelId = "Qwen2.5-Omni-3B-MNN",
                    estimatedSizeGB = 3.5,
                    recommended = true,
                    reason = "中端设备，推荐使用 3B 模型"
                )
            }
            memGB >= MIN_MEMORY_3B && storageGB >= MIN_STORAGE_3B -> {
                ModelConfig(
                    modelType = ModelType.OMNI_3B,
                    modelId = "Qwen2.5-Omni-3B-MNN",
                    estimatedSizeGB = 3.5,
                    recommended = false,
                    reason = "设备配置较低，可能运行缓慢，建议使用云端服务"
                )
            }
            else -> {
                ModelConfig(
                    modelType = ModelType.CLOUD,
                    modelId = "qwen3-omni-flash",
                    estimatedSizeGB = 0.0,
                    recommended = true,
                    reason = "设备配置不足，推荐使用云端服务"
                )
            }
        }
    }
    
    /**
     * 检测 CPU 架构
     */
    fun get_cpu_arch(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }
    
    /**
     * 是否支持 ARM v8.2（FP16 加速）
     */
    fun supportsFp16Acceleration(): Boolean {
        val arch = get_cpu_arch()
        return arch.startsWith("arm64") && Build.VERSION.SDK_INT >= 26
    }
}

/**
 * 模型类型
 */
enum class ModelType {
    OMNI_3B,    // 本地 3B 模型
    OMNI_7B,    // 本地 7B 模型
    CLOUD       // 云端模型
}

/**
 * 模型配置
 */
data class ModelConfig(
    val modelType: ModelType,
    val modelId: String,
    val estimatedSizeGB: Double,
    val recommended: Boolean,
    val reason: String
) {
    val isLocal: Boolean
        get() = modelType != ModelType.CLOUD
    
    val downloadUrl: String
        get() = when (modelType) {
            ModelType.OMNI_3B -> "https://modelscope.cn/models/MNN/Qwen2.5-Omni-3B-MNN/resolve/master/"
            ModelType.OMNI_7B -> "https://modelscope.cn/models/MNN/Qwen2.5-Omni-7B-MNN/resolve/master/"
            ModelType.CLOUD -> ""
        }
}