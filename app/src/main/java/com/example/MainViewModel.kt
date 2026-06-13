package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.client.StreamClient
import com.example.sensor.SensorTracker
import com.example.server.EmbeddedWebserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 1. Core Modules
    private val sensorTracker = SensorTracker(application)
    private val streamClient = StreamClient()
    
    // Create embedded webserver, pointing getSensorJson to our current tracking value
    private val embeddedWebserver = EmbeddedWebserver(port = 8080) {
        sensorTracker.imuState.value.toJsonString()
    }

    // 2. States exposed to UI
    val imuState = sensorTracker.imuState
    val isSimulating = sensorTracker.isSimulating
    
    val isServerRunning = embeddedWebserver.isServerRunning
    val serverLogs = embeddedWebserver.serverLogs
    val lastServedJson = embeddedWebserver.lastServedJson
    val lastReceivedJson = embeddedWebserver.lastReceivedJson
    
    val isStreaming = streamClient.isStreaming
    val successCount = streamClient.successCount
    val errorCount = streamClient.errorCount
    val streamLogs = streamClient.streamLogs
    val lastPostPayload = streamClient.lastPostPayload

    // 3. UI Settings States (survives screen rotation in ViewModel)
    private val _targetUrl = MutableStateFlow("http://192.168.1.100:8000/api/imu")
    val targetUrl = _targetUrl.asStateFlow()

    private val _streamIntervalMs = MutableStateFlow(1000L)
    val streamIntervalMs = _streamIntervalMs.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Sensor Stream, 1: Embedded Server / Data View
    val selectedTab = _selectedTab.asStateFlow()

    init {
        // Automatically start reading sensors (or falling back to simulation)
        sensorTracker.start()
    }

    fun updateTargetUrl(url: String) {
        _targetUrl.value = url
    }

    fun updateStreamInterval(interval: Long) {
        _streamIntervalMs.value = interval
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun toggleStreaming() {
        if (isStreaming.value) {
            streamClient.stopStreaming()
        } else {
            streamClient.startStreaming(
                targetUrl = _targetUrl.value,
                intervalMs = _streamIntervalMs.value,
                getPayload = { sensorTracker.imuState.value.toJsonString() }
            )
        }
    }

    fun toggleWebserver(): String {
        return if (isServerRunning.value) {
            embeddedWebserver.stop()
            "Server stopped"
        } else {
            embeddedWebserver.start()
        }
    }

    fun toggleSensorSimulation() {
        // Toggle simulation overrides
        sensorTracker.forceSimulation(!isSimulating.value)
    }

    fun getLocalIpAddresses(): List<String> {
        return embeddedWebserver.getLocalIpAddresses()
    }

    fun clearClientStats() {
        streamClient.clearStats()
    }

    override fun onCleared() {
        super.onCleared()
        sensorTracker.stop()
        embeddedWebserver.stop()
        streamClient.stopStreaming()
    }
}
