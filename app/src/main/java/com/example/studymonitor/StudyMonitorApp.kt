// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.example.studymonitor.studymonitor.UnifiedVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 类
 * 
 * 职责：
 * 1. 初始化通知渠道
 * 2. 预加载服务（可选）
 * 3. 全局异常处理
 */
class StudyMonitorApp : Application() {
    
    companion object {
        private const val TAG = "StudyMonitorApp"
        
        const val NOTIFICATION_CHANNEL_ID = "study_monitor"
        const val NOTIFICATION_CHANNEL_NAME = "学习监督"
        
        @Volatile
        private var instance: StudyMonitorApp? = null
        
        fun getInstance(): StudyMonitorApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
    
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.d(TAG, "Application 启动")
        
        // 初始化通知渠道
        createNotificationChannel()
        
        // 可选：预初始化语音服务（后台加载模型）
        // preInitVoiceService()
    }
    
    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "学习监督提醒通知"
                enableVibration(true)
                enableLights(true)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            
            Log.d(TAG, "通知渠道已创建")
        }
    }
    
    /**
     * 预初始化语音服务（后台加载模型，首次使用更快）
     * ⚠️ 仅在有足够内存时启用
     */
    private fun preInitVoiceService() {
        applicationScope.launch {
            try {
                val service = UnifiedVoiceService.getInstance(this@StudyMonitorApp)
                // 检测设备能力，决定是否预加载
                // service.initialize() // 仅当用户已下载模型时
                Log.d(TAG, "语音服务预初始化完成")
            } catch (e: Exception) {
                Log.e(TAG, "语音服务预初始化失败", e)
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // 释放服务资源
        try {
            UnifiedVoiceService.getInstance(this).release()
        } catch (e: Exception) {
            // 忽略
        }
    }
}