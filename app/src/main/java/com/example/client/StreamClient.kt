package com.example.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class StreamClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    private val _successCount = MutableStateFlow(0)
    val successCount = _successCount.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount = _errorCount.asStateFlow()

    private val _streamLogs = MutableStateFlow<List<String>>(emptyList())
    val streamLogs = _streamLogs.asStateFlow()

    private val _lastPostPayload = MutableStateFlow("")
    val lastPostPayload = _lastPostPayload.asStateFlow()

    fun startStreaming(targetUrl: String, intervalMs: Long, getPayload: () -> String) {
        if (_isStreaming.value) return
        _isStreaming.value = true
        _successCount.value = 0
        _errorCount.value = 0
        
        addLog("Starting IMU Client Stream to: $targetUrl")

        activeJob = scope.launch {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            while (true) {
                try {
                    val payload = getPayload()
                    _lastPostPayload.value = payload

                    val requestBody = payload.toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(targetUrl)
                        .post(requestBody)
                        .header("User-Agent", "Android-IMU-Sensor-Stream")
                        .build()

                    val startTime = System.currentTimeMillis()
                    client.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            _successCount.value += 1
                            val bodyStr = response.body?.string() ?: ""
                            val cleanBody = if (bodyStr.length > 80) bodyStr.take(80) + "..." else bodyStr
                            addLog("POST Success [${duration}ms]: Code ${response.code} -> $cleanBody")
                        } else {
                            _errorCount.value += 1
                            val bodyErr = response.body?.string() ?: ""
                            addLog("POST Fail [${duration}ms]: Code ${response.code} -> $bodyErr")
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    _errorCount.value += 1
                    addLog("Client URL Error: Invalid URL structure")
                    delay(3000) // Delay more if URL itself is completely broken
                } catch (e: IOException) {
                    _errorCount.value += 1
                    addLog("Network Error: ${e.message ?: "Connection timed out"}")
                } catch (e: Exception) {
                    _errorCount.value += 1
                    addLog("Unexpected Error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        _isStreaming.value = false
        addLog("Stream stopped by user.")
    }

    private fun addLog(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedLog = "[${sdf.format(Date())}] $message"
        val current = _streamLogs.value.toMutableList()
        current.add(0, formattedLog)
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        _streamLogs.value = current
    }

    fun clearStats() {
        _successCount.value = 0
        _errorCount.value = 0
        _streamLogs.value = emptyList()
    }
}
