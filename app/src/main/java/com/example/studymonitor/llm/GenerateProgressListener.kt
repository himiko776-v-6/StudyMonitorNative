// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.llm

/**
 * LLM 生成进度监听器
 */
interface GenerateProgressListener {
    /**
     * @param progress 当前生成的文本片段
     * @return true 继续，false 停止生成
     */
    fun onProgress(progress: String): Boolean
}