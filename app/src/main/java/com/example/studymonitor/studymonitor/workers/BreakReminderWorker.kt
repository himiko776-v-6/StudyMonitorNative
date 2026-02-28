// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.studymonitor.studymonitor.StudyMonitorService

/**
 * 休息提醒 Worker
 * 
 * 定时触发休息提醒语音
 */
class BreakReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        const val TAG = "BreakReminderWorker"
    }
    
    override suspend fun doWork(): Result {
        return try {
            val service = StudyMonitorService.getInstance(applicationContext)
            
            // 检查服务是否就绪且正在学习
            if (service?.isReady() == true && service.isStudying()) {
                service.generateBreakReminder()
            }
            
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "休息提醒失败", e)
            Result.retry()
        }
    }
}