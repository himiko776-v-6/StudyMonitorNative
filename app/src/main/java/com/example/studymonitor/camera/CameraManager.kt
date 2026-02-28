package com.example.studymonitor.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null

    // 目标分辨率
    private val targetWidth = 800
    private val targetHeight = 600
    private val jpegQuality = 70

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun initialize(lifecycleOwner: androidx.lifecycle.LifecycleOwner): Boolean = withContext(Dispatchers.Main) {
        this@CameraManager.lifecycleOwner = lifecycleOwner
        
        if (!hasCameraPermission()) {
            Log.e(TAG, "No camera permission")
            return@withContext false
        }

        suspendCancellableCoroutine { continuation ->
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        cameraProvider = providerFuture.get()
                        val success = bindCameraUseCases()
                        continuation.resume(success)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize camera: ${e.message}")
                        continuation.resume(false)
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization error: ${e.message}")
                continuation.resume(false)
            }
        }
    }

    private fun bindCameraUseCases(): Boolean {
        val provider = cameraProvider ?: return false
        val owner = lifecycleOwner ?: return false

        try {
            provider.unbindAll()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(jpegQuality)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(owner, cameraSelector, imageCapture)

            Log.d(TAG, "Camera bound successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera: ${e.message}")
            return false
        }
    }

    suspend fun capturePhoto(): Bitmap? = withContext(Dispatchers.IO) {
        val capture = imageCapture
        
        if (capture == null) {
            Log.e(TAG, "ImageCapture not initialized")
            return@withContext null
        }

        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val originalBitmap = imageProxyToBitmap(image)
                            image.close()
                            
                            // 压缩图片
                            val compressedBitmap = originalBitmap?.let { resizeBitmap(it) }
                            
                            Log.d(TAG, "Photo captured: original=${originalBitmap?.width}x${originalBitmap?.height}, compressed=${compressedBitmap?.width}x${compressedBitmap?.height}")
                            continuation.resume(compressedBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process image: ${e.message}")
                            image.close()
                            continuation.resume(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Capture failed: ${exception.message}")
                        continuation.resume(null)
                    }
                }
            )
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image: ${e.message}")
            null
        }
    }

    /**
     * 压缩图片到目标分辨率
     */
    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 如果已经很小，直接返回
        if (width <= targetWidth && height <= targetHeight) {
            return bitmap
        }
        
        // 计算缩放比例
        val ratio = minOf(
            targetWidth.toFloat() / width,
            targetHeight.toFloat() / height
        )
        
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        Log.d(TAG, "Resizing from ${width}x${height} to ${newWidth}x${newHeight}")
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 将 Bitmap 转换为压缩后的 JPEG 字节数组
     */
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 70): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}