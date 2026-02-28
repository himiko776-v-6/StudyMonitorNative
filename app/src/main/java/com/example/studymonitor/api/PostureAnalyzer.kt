package com.example.studymonitor.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class PostureResult(
    val present: Boolean? = null,
    val posture: String? = null,
    val attention: String? = null,
    val head: String? = null,
    val back: String? = null,
    val eyes: String? = null,
    val issues: List<String>? = null,
    val suggestions: List<String>? = null,
    val error: String? = null
)

class PostureAnalyzer private constructor(
    private val mode: Int,
    private val qianwenApiKey: String? = null,
    private val localApiUrl: String? = null
) {
    // 增加超时时间，本地推理可能很慢
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)  // 3分钟读取超时
        .writeTimeout(120, TimeUnit.SECONDS) // 2分钟写入超时
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    suspend fun analyze(bitmap: Bitmap): PostureResult = withContext(Dispatchers.IO) {
        when (mode) {
            MODE_QIANWEN -> analyzeWithQianwen(bitmap)
            MODE_LOCAL -> analyzeWithLocalApi(bitmap)
            else -> PostureResult(error = "未配置 API 模式")
        }
    }

    private suspend fun analyzeWithQianwen(bitmap: Bitmap): PostureResult {
        return try {
            val base64Image = bitmapToBase64(bitmap, quality = 70)
            
            val prompt = """请分析这个人的坐姿，返回JSON格式：
{
  "present": true/false,
  "head": "forward/straight/back",
  "back": "curved/straight", 
  "eyes": "screen/book/away",
  "posture": "good/needs_improvement/unhealthy",
  "attention": "focused/distracted/unknown",
  "issues": ["问题列表"],
  "suggestions": ["建议列表"]
}"""

            val requestBody = QianwenRequest(
                model = "qwen-vl-max",
                input = QianwenInput(
                    messages = listOf(
                        QianwenMessage(
                            role = "user",
                            content = listOf(
                                QianwenContent(image = "data:image/jpeg;base64,$base64Image"),
                                QianwenContent(text = prompt)
                            )
                        )
                    )
                )
            )

            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .addHeader("Authorization", "Bearer $qianwenApiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d(TAG, "Qianwen response: $responseBody")
                parseQianwenResponse(responseBody)
            } else {
                PostureResult(error = "API 请求失败: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Qianwen analysis failed: ${e.message}")
            PostureResult(error = "分析失败: ${e.message}")
        }
    }

    private suspend fun analyzeWithLocalApi(bitmap: Bitmap): PostureResult {
        return try {
            // 压缩图片以减少传输时间
            val base64Image = bitmapToBase64(bitmap, quality = 60)
            Log.d(TAG, "Image base64 size: ${base64Image.length} chars")
            
            val prompt = """分析这张图片中的人的坐姿。如果没有人，返回 {"present":false}。
如果有人，分析坐姿并返回JSON：
{"present":true,"posture":"good或needs_improvement或unhealthy","attention":"focused或distracted","issues":["问题"],"suggestions":["建议"]}
只返回JSON。"""

            val requestBody = OpenAIRequest(
                model = "default",
                messages = listOf(
                    OpenAIMessage(
                        role = "user",
                        content = listOf(
                            OpenAIContent(type = "text", text = prompt),
                            OpenAIContent(
                                type = "image_url",
                                image_url = OpenAIImageUrl(url = "data:image/jpeg;base64,$base64Image")
                            )
                        )
                    )
                ),
                max_tokens = 300
            )

            val json = gson.toJson(requestBody)
            Log.d(TAG, "Request size: ${json.length} chars")
            val body = json.toRequestBody("application/json".toMediaType())

            val endpoint = if (localApiUrl!!.contains("/v1/")) {
                localApiUrl
            } else {
                "$localApiUrl/v1/chat/completions"
            }

            Log.d(TAG, "Connecting to: $endpoint")

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d(TAG, "Local API response: ${responseBody?.take(300)}")
                parseOpenAIResponse(responseBody)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Local API error: ${response.code} - $errorBody")
                PostureResult(error = "API错误: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local API failed: ${e.message}")
            PostureResult(error = "连接失败: ${e.message}")
        }
    }

    /**
     * 将 Bitmap 转换为 Base64，支持指定质量
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 70): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun parseQianwenResponse(responseBody: String?): PostureResult {
        if (responseBody == null) return PostureResult(error = "响应为空")
        
        return try {
            val response = gson.fromJson(responseBody, QianwenResponse::class.java)
            val content = response.output?.choices?.firstOrNull()?.message?.content?.firstOrNull()?.text
            
            if (content != null) {
                val jsonStart = content.indexOf('{')
                val jsonEnd = content.lastIndexOf('}') + 1
                
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonStr = content.substring(jsonStart, jsonEnd)
                    gson.fromJson(jsonStr, PostureResult::class.java)
                } else {
                    PostureResult(error = "无法解析响应")
                }
            } else {
                PostureResult(error = "响应内容为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Qianwen response: ${e.message}")
            PostureResult(error = "解析响应失败")
        }
    }

    private fun parseOpenAIResponse(responseBody: String?): PostureResult {
        if (responseBody == null) return PostureResult(error = "响应为空")
        
        return try {
            val response = gson.fromJson(responseBody, OpenAIResponse::class.java)
            val content = response.choices?.firstOrNull()?.message?.content
            
            if (content != null) {
                val jsonStart = content.indexOf('{')
                val jsonEnd = content.lastIndexOf('}') + 1
                
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonStr = content.substring(jsonStart, jsonEnd)
                    gson.fromJson(jsonStr, PostureResult::class.java)
                } else {
                    PostureResult(
                        present = true,
                        posture = "unknown",
                        error = content.take(100)
                    )
                }
            } else {
                PostureResult(error = "响应内容为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse OpenAI response: ${e.message}")
            PostureResult(error = "解析失败: ${e.message}")
        }
    }

    // Data classes for Qianwen API
    data class QianwenRequest(val model: String, val input: QianwenInput)
    data class QianwenInput(val messages: List<QianwenMessage>)
    data class QianwenMessage(val role: String, val content: List<QianwenContent>)
    data class QianwenContent(val image: String? = null, val text: String? = null)
    data class QianwenResponse(val output: QianwenOutput?)
    data class QianwenOutput(val choices: List<QianwenChoice>?)
    data class QianwenChoice(val message: QianwenResponseMessage?)
    data class QianwenResponseMessage(val content: List<QianwenResponseContent>?)
    data class QianwenResponseContent(val text: String?)

    // Data classes for OpenAI compatible API
    data class OpenAIRequest(val model: String, val messages: List<OpenAIMessage>, val max_tokens: Int = 300)
    data class OpenAIMessage(val role: String, val content: List<OpenAIContent>)
    data class OpenAIContent(val type: String, val text: String? = null, val image_url: OpenAIImageUrl? = null)
    data class OpenAIImageUrl(val url: String)
    data class OpenAIResponse(val choices: List<OpenAIResponseChoice>?)
    data class OpenAIResponseChoice(val message: OpenAIResponseMessage?)
    data class OpenAIResponseMessage(val content: String?)

    companion object {
        private const val TAG = "PostureAnalyzer"
        const val MODE_QIANWEN = 1
        const val MODE_LOCAL = 2

        fun createQianwenAnalyzer(apiKey: String): PostureAnalyzer {
            return PostureAnalyzer(MODE_QIANWEN, qianwenApiKey = apiKey)
        }

        fun createLocalAnalyzer(apiUrl: String): PostureAnalyzer {
            return PostureAnalyzer(MODE_LOCAL, localApiUrl = apiUrl)
        }
    }
}