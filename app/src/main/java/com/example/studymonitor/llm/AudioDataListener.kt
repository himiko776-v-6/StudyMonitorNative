// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.llm

/**
 * 音频数据回调接口
 * 用于接收 LLM 生成的语音 PCM 数据
 */
interface AudioDataListener {
    /**
     * @param data PCM 浮点数据，采样率 24000，单声道
     * @param isEnd 是否为最后一帧
     * @return true 继续接收，false 停止
     */
    fun onAudioData(data: FloatArray, isEnd: Boolean): Boolean
}