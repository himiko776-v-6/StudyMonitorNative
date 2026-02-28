// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频播放器
 * 用于播放 LLM 生成的 PCM 音频数据
 * 
 * 支持：
 * - 流式播放（边生成边播放）
 * - 采样率 24000Hz，单声道，16bit
 * 
 * ⚠️ 注意：调用 release() 后不可再用
 */
class AudioPlayer {
    
    companion object {
        private const val TAG = "AudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000
    }
    
    // 音频配置
    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        set(value) {
            if (isPlaying.get()) {
                Log.w(TAG, "播放中无法修改采样率")
                return
            }
            field = value
        }
    
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // 播放器状态
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    
    // 播放队列（用于异步播放）
    private val playQueue = LinkedBlockingQueue<ShortArray>()
    private var playThread: Thread? = null
    
    // 回调
    private var onCompletionListener: (() -> Unit)? = null
    
    // ========== 公共 API ==========
    
    /**
     * 初始化并开始播放（线程安全）
     */
    fun start(): Boolean {
        if (isReleased.get()) {
            Log.e(TAG, "Player 已释放")
            return false
        }
        
        // 防止重复初始化导致线程泄漏
        if (isInitialized.get()) {
            Log.w(TAG, "Player 已初始化，跳过")
            return true
        }
        
        // 确保清理旧的播放状态
        cleanupOldState()
        
        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "无法获取缓冲区大小")
                return false
            }
            
            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化失败")
                return false
            }
            
            audioTrack?.play()
            isInitialized.set(true)
            isPlaying.set(true)
            
            // 启动播放线程
            startPlayThread()
            
            Log.d(TAG, "播放器启动成功，采样率: $sampleRate")
            true
        } catch (e: Exception) {
            Log.e(TAG, "播放器启动失败", e)
            false
        }
    }
    
    /**
     * 播放音频数据块（Float 数组）
     * ⚠️ 此方法不阻塞，数据放入队列后立即返回
     */
    fun playChunk(data: FloatArray) {
        if (!isInitialized.get() || isReleased.get()) {
            return
        }
        
        // Float → Short 转换
        val shortData = ShortArray(data.size) { i ->
            val sample = data[i].coerceIn(-1.0f, 1.0f)
            (sample * 32767.0f).toInt().toShort()
        }
        
        playQueue.offer(shortData)
    }
    
    /**
     * 播放音频数据块（Short 数组）
     */
    fun playChunk(data: ShortArray) {
        if (!isInitialized.get() || isReleased.get()) {
            return
        }
        playQueue.offer(data)
    }
    
    /**
     * 同步播放（阻塞直到播放完成）
     */
    fun playChunkSync(data: FloatArray) {
        if (!isInitialized.get() || isReleased.get()) {
            return
        }
        
        val shortData = ShortArray(data.size) { i ->
            val sample = data[i].coerceIn(-1.0f, 1.0f)
            (sample * 32767.0f).toInt().toShort()
        }
        
        audioTrack?.write(shortData, 0, shortData.size)
    }
    
    /**
     * 标记音频结束
     * 发送一个空数组作为结束信号
     */
    fun endChunk() {
        playQueue.offer(ShortArray(0))
    }
    
    /**
     * 停止播放并清空队列
     */
    fun stop() {
        playQueue.clear()
        audioTrack?.stop()
        isPlaying.set(false)
    }
    
    /**
     * 暂停播放
     */
    fun pause() {
        audioTrack?.pause()
        isPlaying.set(false)
    }
    
    /**
     * 恢复播放
     */
    fun resume() {
        audioTrack?.play()
        isPlaying.set(true)
    }
    
    /**
     * 重置播放器（清空队列，保持初始化状态）
     */
    fun reset() {
        playQueue.clear()
        if (isInitialized.get() && audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.play()
            isPlaying.set(true)
        }
    }
    
    /**
     * 设置播放完成回调
     */
    fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }
    
    /**
     * 释放资源
     * ⚠️ 调用后不可再用
     */
    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            playQueue.offer(ShortArray(0)) // 发送结束信号
            
            playThread?.join(1000)
            playThread = null
            
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            
            playQueue.clear()
            isInitialized.set(false)
            isPlaying.set(false)
            
            Log.d(TAG, "播放器已释放")
        }
    }
    
    // ========== 状态查询 ==========
    
    fun isPlaying(): Boolean = isPlaying.get() && !isReleased.get()
    
    fun isInitialized(): Boolean = isInitialized.get() && !isReleased.get()
    
    // ========== 内部实现 ==========
    
    /**
     * 清理旧的播放状态（防止线程泄漏）
     */
    private fun cleanupOldState() {
        playQueue.clear()
        playThread?.let {
            it.interrupt()
            it.join(500)
        }
        playThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
    
    private fun startPlayThread() {
        playThread = Thread {
            while (!isReleased.get()) {
                try {
                    val data = playQueue.take()
                    
                    // 空数组表示结束
                    if (data.isEmpty()) {
                        if (!isReleased.get()) {
                            onCompletionListener?.invoke()
                        }
                        break
                    }
                    
                    audioTrack?.write(data, 0, data.size)
                    
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "播放出错", e)
                }
            }
        }.apply {
            name = "AudioPlayerThread"
            start()
        }
    }
}