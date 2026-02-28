# 学习监督 APP - 潜在 Bug 场景分析

## 一、模型加载相关

### 1.1 模型加载耗时导致 ANR
**场景**：在主线程调用 `StudyMonitorService.initialize()`
**表现**：APP 无响应，系统弹出 ANR 对话框
**修复**：强制在 IO 线程调用，并显示加载进度

```kotlin
// ❌ 错误
lifecycleScope.launch {
    service.initialize(modelPath) // 在主线程执行
}

// ✅ 正确
lifecycleScope.launch(Dispatchers.IO) {
    service.initialize(modelPath)
}
```

### 1.2 模型文件不完整
**场景**：下载中断或存储空间不足，模型文件损坏
**表现**：加载失败，报错信息不明确
**修复**：加载前校验文件完整性

```kotlin
fun validateModelFiles(modelDir: File): Boolean {
    val requiredFiles = listOf("config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
    return requiredFiles.all { File(modelDir, it).exists() && File(modelDir, it).length() > 0 }
}
```

### 1.3 重复初始化
**场景**：用户快速点击开始按钮，触发多次初始化
**表现**：内存泄漏或崩溃
**修复**：使用 AtomicBoolean 防重入

```kotlin
private val isInitializing = AtomicBoolean(false)

suspend fun initialize(): Boolean {
    if (!isInitializing.compareAndSet(false, true)) {
        return isLlmReady.get() // 已在初始化中
    }
    try {
        // ... 初始化逻辑
    } finally {
        isInitializing.set(false)
    }
}
```

---

## 二、语音生成相关

### 2.1 生成过程中断
**场景**：语音生成过程中，用户关闭 APP 或切换到后台
**表现**：内存泄漏，native 指针未释放
**修复**：在 Activity onDestroy 中主动释放

```kotlin
override fun onDestroy() {
    super.onDestroy()
    studyMonitorService.release() // 确保释放资源
}
```

### 2.2 并发生成冲突
**场景**：休息提醒和走神鼓励同时触发
**表现**：语音重叠或崩溃
**修复**：使用生成锁，一次只允许一个生成任务

```kotlin
private val generateLock = ReentrantLock()

fun generateVoice(prompt: String) {
    if (generateLock.isLocked) {
        Log.w(TAG, "已有生成任务，跳过")
        return
    }
    generateLock.lock()
    try {
        llmSession?.generate(prompt, withAudio = true)
    } finally {
        generateLock.unlock()
    }
}
```

### 2.3 音频播放器未初始化
**场景**：AudioPlayer 初始化失败后仍尝试播放
**表现**：NullPointerException
**修复**：播放前检查状态

```kotlin
fun playChunk(data: FloatArray) {
    audioPlayer?.takeIf { it.isInitialized() }?.playChunk(data)
}
```

### 2.4 采样率不匹配
**场景**：模型输出 24000Hz，但播放器配置了其他采样率
**表现**：播放速度异常（过快或过慢）
**修复**：固定采样率为 24000

```kotlin
// Qwen2.5-Omni 固定输出 24000Hz
const val MODEL_SAMPLE_RATE = 24000
audioPlayer.sampleRate = MODEL_SAMPLE_RATE
```

---

## 三、内存相关

### 3.1 模型内存不足
**场景**：设备内存 < 4GB，无法加载 7B 模型
**表现**：OOM 崩溃或加载失败
**修复**：
1. 检测设备内存，自动选择模型大小
2. 使用 mmap 模式减少内存占用

```kotlin
fun getRecommendedModelSize(context: Context): String {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val totalMemGB = memInfo.totalMem / (1024 * 1024 * 1024)
    
    return when {
        totalMemGB >= 8 -> "7B"
        totalMemGB >= 5 -> "3B"
        else -> "cloud" // 使用云端 TTS
    }
}
```

### 3.2 内存泄漏（单例未释放）
**场景**：多次创建 Service 单例但未释放
**表现**：内存持续增长
**修复**：确保单例正确释放

```kotlin
companion object {
    @Volatile
    private var instance: StudyMonitorService? = null
    
    fun releaseInstance() {
        instance?.release()
        instance = null
    }
}
```

### 3.3 音频队列无限增长
**场景**：音频生成速度 > 播放速度，队列堆积
**表现**：内存增长，延迟增大
**修复**：限制队列大小

```kotlin
class BoundedAudioQueue(maxSize: Int = 100) {
    private val queue = LinkedBlockingQueue<ShortArray>()
    
    fun offer(data: ShortArray): Boolean {
        if (queue.size >= maxSize) {
            queue.poll() // 丢弃最旧的数据
        }
        return queue.offer(data)
    }
}
```

---

## 四、线程安全相关

### 4.1 统计数据竞态
**场景**：走神检测和 UI 更新同时修改统计数据
**表现**：数据不一致
**修复**：使用 AtomicInteger 或 StateFlow

```kotlin
// ✅ 使用 AtomicInteger
private val distractionCount = AtomicInteger(0)

fun onDistractionDetected() {
    val newCount = distractionCount.incrementAndGet()
    _currentStats.update { it.copy(distractionCount = newCount) }
}
```

### 4.2 JNI 回调线程问题
**场景**：音频回调从 native 线程调用，直接操作 UI
**表现**：CalledFromWrongThreadException
**修复**：切换到主线程

```kotlin
llmSession?.setAudioDataListener(object : AudioDataListener {
    override fun onAudioData(data: FloatArray, isEnd: Boolean): Boolean {
        // ⚠️ 此回调在 native 线程，不能直接操作 UI
        withContext(Dispatchers.Main) {
            // UI 操作
        }
        return true
    }
})
```

---

## 五、后台服务相关

### 5.1 后台被系统杀死
**场景**：APP 切到后台，系统回收资源
**表现**：学习会话中断，统计数据丢失
**修复**：使用 Foreground Service

```kotlin
class StudyMonitorService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }
}
```

### 5.2 定时任务不准确
**场景**：使用 Handler.postDelayed 做定时提醒，后台时不准确
**表现**：休息提醒延迟或丢失
**修复**：使用 WorkManager

```kotlin
val breakWork = PeriodicWorkRequestBuilder<BreakReminderWorker>(
    breakIntervalMinutes, TimeUnit.MINUTES
).build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "break_reminder",
    ExistingPeriodicWorkPolicy.KEEP,
    breakWork
)
```

---

## 六、网络相关

### 6.1 视觉模型调用失败
**场景**：网络不稳定，图片上传超时
**表现**：走神检测失败
**修复**：
1. 添加重试机制
2. 本地缓存图片，延迟上传

```kotlin
suspend fun analyzeWithRetry(image: ByteArray, maxRetries: Int = 3): Result {
    repeat(maxRetries) { attempt ->
        try {
            return api.analyze(image)
        } catch (e: Exception) {
            if (attempt == maxRetries - 1) throw e
            delay(1000L * (attempt + 1))
        }
    }
}
```

### 6.2 图片压缩质量过低
**场景**：压缩比例过大，模型无法识别
**表现**：误报率上升
**修复**：根据网络状况动态调整压缩质量

```kotlin
fun getCompressQuality(networkType: Int): Int {
    return when (networkType) {
        ConnectivityManager.TYPE_WIFI -> 70
        ConnectivityManager.TYPE_4G -> 50
        else -> 30
    }
}
```

---

## 七、权限相关

### 7.1 相机权限被撤销
**场景**：用户在设置中关闭了相机权限
**表现**：拍照崩溃
**修复**：每次使用前检查权限

```kotlin
fun captureImage() {
    if (!hasCameraPermission()) {
        requestCameraPermission()
        return
    }
    // 执行拍照
}
```

### 7.2 录音权限未获取（如需语音交互）
**场景**：ASR 功能需要录音权限
**表现**：语音识别失败
**修复**：初始化时检查权限

---

## 八、边界情况

### 8.1 学习时长为 0 时的总结
**场景**：刚打开 APP 就查看总结
**表现**：除零错误或无意义数据
**修复**：显示提示信息

```kotlin
if (totalMinutes == 0) {
    return "还没有开始学习哦，加油！"
}
```

### 8.2 长时间学习（超过 4 小时）
**场景**：忘记停止学习会话
**表现**：统计数据异常大
**修复**：设置最大学习时长，自动暂停

```kotlin
companion object {
    const val MAX_STUDY_HOURS = 4
}

fun checkMaxDuration() {
    if (studyDurationMinutes.get() >= MAX_STUDY_HOURS * 60) {
        pauseStudySession()
        showNotification("学习时间过长，已自动暂停")
    }
}
```

### 8.3 模型被删除
**场景**：用户清理存储空间删除了模型文件
**表现**：加载失败
**修复**：检测并提示重新下载

---

## 九、测试建议

1. **内存测试**：长时间运行，监控内存是否持续增长
2. **并发测试**：快速点击按钮，测试防重入
3. **后台测试**：切后台 30 分钟后恢复，检查状态
4. **网络测试**：弱网环境下测试视觉分析
5. **机型测试**：低端机（< 4GB 内存）测试模型加载
6. **断点续传**：下载过程中断网，恢复后继续