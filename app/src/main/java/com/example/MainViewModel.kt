package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.client.Esp32Client
import com.example.client.StreamClient
import com.example.ml.DetectionMode
import com.example.ml.JackClassifier
import com.example.ml.JackResult
import com.example.ml.PoseDetector
import com.example.ml.PoseResult
import com.example.model.Verdict
import com.example.sensor.SensorTracker
import com.example.server.EmbeddedWebserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorTracker = SensorTracker(application)
    private val streamClient = StreamClient()
    private val esp32Client = Esp32Client()
    val jackClassifier = JackClassifier()
    val poseDetector = PoseDetector(application)

    private val embeddedWebserver = EmbeddedWebserver(port = 8080) {
        sensorTracker.imuState.value.toJsonString()
    }

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

    // UI Settings
    private val _targetUrl = MutableStateFlow("http://192.168.1.100:8000/api/imu")
    val targetUrl = _targetUrl.asStateFlow()

    private val _streamIntervalMs = MutableStateFlow(1000L)
    val streamIntervalMs = _streamIntervalMs.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()

    // Detection Mode
    private val _selectedMode = MutableStateFlow(DetectionMode.IMU)
    val selectedMode: StateFlow<DetectionMode> = _selectedMode.asStateFlow()

    private val _jackResult = MutableStateFlow<JackResult?>(null)
    val jackResult: StateFlow<JackResult?> = _jackResult.asStateFlow()

    private val _poseResult = MutableStateFlow<PoseResult?>(null)
    val poseResult: StateFlow<PoseResult?> = _poseResult.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _cameraFps = MutableStateFlow(0f)
    val cameraFps: StateFlow<Float> = _cameraFps.asStateFlow()

    // ESP32 / Verdict
    private val _verdict = MutableStateFlow<Verdict?>(null)
    val verdict = _verdict.asStateFlow()

    private val _esp32Url = MutableStateFlow("http://192.168.1.200")
    val esp32Url = _esp32Url.asStateFlow()

    private val _isEsp32Polling = MutableStateFlow(false)
    val isEsp32Polling = _isEsp32Polling.asStateFlow()

    private var esp32PollingJob: Job? = null
    private val esp32Scope = CoroutineScope(Dispatchers.IO)
    private val mlScope = CoroutineScope(Dispatchers.Default)

    private var jackJob: Job? = null
    private var lastSentJack: JackResult? = null

    init {
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

    fun setDetectionMode(mode: DetectionMode) {
        val prev = _selectedMode.value
        _selectedMode.value = mode

        if (mode == DetectionMode.IMU) {
            stopCamera()
        } else if (mode == DetectionMode.CAMERA || mode == DetectionMode.BOTH) {
            if (prev == DetectionMode.IMU) {
                startCamera()
            }
        }

        if (prev == DetectionMode.CAMERA && mode == DetectionMode.BOTH) {
            // camera already running
        }
    }

    fun startCamera() {
        if (!poseDetector.load()) return
        _isCameraActive.value = true
        startJackLoop()
    }

    fun stopCamera() {
        _isCameraActive.value = false
        _poseResult.value = null
        stopJackLoop()
        poseDetector.close()
    }

    fun onFrameProcessed(poseResult: PoseResult?) {
        _poseResult.value = poseResult
    }

    private fun startJackLoop() {
        if (jackJob?.isActive == true) return
        jackJob = mlScope.launch {
            while (true) {
                val imu = sensorTracker.imuState.value
                val pose = _poseResult.value
                val mode = _selectedMode.value

                val result = jackClassifier.classify(imu, pose, mode)
                _jackResult.value = result

                delay(100)
            }
        }
    }

    private fun stopJackLoop() {
        jackJob?.cancel()
        jackJob = null
        jackClassifier.reset()
        _jackResult.value = null
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
        sensorTracker.forceSimulation(!isSimulating.value)
    }

    fun getLocalIpAddresses(): List<String> {
        return embeddedWebserver.getLocalIpAddresses()
    }

    fun clearClientStats() {
        streamClient.clearStats()
    }

    // ESP32 Functions
    fun updateEsp32Url(url: String) {
        _esp32Url.value = url
    }

    fun toggleEsp32Polling() {
        if (_isEsp32Polling.value) {
            stopEsp32Polling()
        } else {
            startEsp32Polling()
        }
    }

    private fun startEsp32Polling() {
        if (_isEsp32Polling.value) return
        _isEsp32Polling.value = true
        val url = _esp32Url.value

        esp32PollingJob = esp32Scope.launch {
            while (true) {
                val jack = _jackResult.value
                if (jack != null && jack != lastSentJack) {
                    esp32Client.sendJackResult(url, jack)
                    lastSentJack = jack
                }

                val result = esp32Client.pollVerdict(url)
                result.onSuccess { v ->
                    _verdict.value = v
                }
                delay(500)
            }
        }
    }

    private fun stopEsp32Polling() {
        esp32PollingJob?.cancel()
        esp32PollingJob = null
        _isEsp32Polling.value = false
        _verdict.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopEsp32Polling()
        stopCamera()
        sensorTracker.stop()
        embeddedWebserver.stop()
        streamClient.stopStreaming()
        jackJob?.cancel()
        poseDetector.close()
    }
}
