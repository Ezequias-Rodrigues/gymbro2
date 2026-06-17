package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import com.example.client.StreamClient
import com.example.ml.DetectionMode
import com.example.ml.JackClassifier
import com.example.ml.JackResult
import com.example.ml.PoseDetector
import com.example.ml.PoseResult
import com.example.sensor.SensorTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

data class UserStatus(
    val text: String,
    val color: Color
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Logic Engines
    private val sensorTracker = SensorTracker(application)
    private val streamClient = StreamClient()
    val jackClassifier = JackClassifier()
    val poseDetector = PoseDetector(application) { result ->
        _poseResult.value = result
    }

    // Coroutine Scopes
    private val mlScope = CoroutineScope(Dispatchers.Default)
    private var jackJob: Job? = null

    // --- State Flows ---

    // Sensor & Detection State
    val imuState = sensorTracker.imuState
    val isSimulating = sensorTracker.isSimulating

    private val _selectedMode = MutableStateFlow(DetectionMode.IMU)
    val selectedMode = _selectedMode.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive = _isCameraActive.asStateFlow()

    private val _poseResult = MutableStateFlow<PoseResult?>(null)
    val poseResult = _poseResult.asStateFlow()

    private val _jackResult = MutableStateFlow<JackResult?>(null)
    val jackResult = _jackResult.asStateFlow()

    // Workout Progress
    private val _repCount = MutableStateFlow(0)
    val repCount = _repCount.asStateFlow()

    private val _isWorkoutActive = MutableStateFlow(false)
    val isWorkoutActive = _isWorkoutActive.asStateFlow()

    // UI Configuration
    private val _targetUrl = MutableStateFlow("https://reasonable-unity-production.up.railway.app/api/jackresult")
    val targetUrl = _targetUrl.asStateFlow()

    private val _imuThreshold = MutableStateFlow(15.0f)
    val imuThreshold = _imuThreshold.asStateFlow()

    private val _userStatus = MutableStateFlow(UserStatus("REPOUSO ABSOLUTO 😴", Color(0xFF475569)))
    val userStatus = _userStatus.asStateFlow()

    // Events
    private val _onRepCounted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onRepCounted = _onRepCounted.asSharedFlow()

    // Streaming Stats
    val isStreaming = streamClient.isStreaming
    val successCount = streamClient.successCount
    val errorCount = streamClient.errorCount
    val streamLogs = streamClient.streamLogs

    // Internal Detection State
    private var lastJumpTime = 0L
    private var lastCameraRepTime = 0L
    private var cameraJackState = 0 // 0: Down, 1: Up

    init {
        sensorTracker.start()
        startJackLoop()
    }

    // --- Actions ---

    fun startWorkout() {
        _repCount.value = 0
        _isWorkoutActive.value = true
    }

    fun endWorkout() {
        _isWorkoutActive.value = false
    }

    fun updateTargetUrl(url: String) {
        _targetUrl.value = url
    }

    fun updateImuThreshold(threshold: Float) {
        _imuThreshold.value = threshold
    }

    fun setDetectionMode(mode: DetectionMode) {
        val prev = _selectedMode.value
        _selectedMode.value = mode

        if (mode == DetectionMode.IMU) {
            stopCamera()
        } else if (mode == DetectionMode.CAMERA && prev == DetectionMode.IMU) {
            startCamera()
        }
    }

    fun startCamera() {
        if (poseDetector.load()) {
            _isCameraActive.value = true
        }
    }

    fun stopCamera() {
        _isCameraActive.value = false
        _poseResult.value = null
        poseDetector.close()
    }

    private fun startJackLoop() {
        if (jackJob?.isActive == true) return
        jackJob = mlScope.launch {
            while (true) {
                processDetectionFrame()
                delay(50)
            }
        }
    }

    private fun processDetectionFrame() {
        val imu = sensorTracker.imuState.value
        val pose = _poseResult.value
        val mode = _selectedMode.value

        val classifierResult = jackClassifier.classify(imu, pose, mode)
        val curTime = System.currentTimeMillis()
        
        var isJacking = false

        if (mode == DetectionMode.IMU) {
            val motionMag = kotlin.math.sqrt(imu.accelX * imu.accelX + imu.accelY * imu.accelY + imu.accelZ * imu.accelZ)
            if (_isWorkoutActive.value && motionMag > _imuThreshold.value && curTime - lastJumpTime > 1000) {
                lastJumpTime = curTime
                _repCount.value += 1
                _onRepCounted.tryEmit(Unit)
            }
            isJacking = (curTime - lastJumpTime < 1000)
        } else if (mode == DetectionMode.CAMERA && pose != null) {
            val keypoints = pose.keypoints
            if (keypoints.size > 10) {
                val avgWristY = (keypoints[9].y + keypoints[10].y) / 2f
                val avgShoulderY = (keypoints[5].y + keypoints[6].y) / 2f
                
                if (cameraJackState == 0 && avgWristY < avgShoulderY - 0.05f) {
                    cameraJackState = 1
                } else if (cameraJackState == 1 && avgWristY > avgShoulderY + 0.05f) {
                    cameraJackState = 0
                    if (_isWorkoutActive.value && curTime - lastCameraRepTime > 800) {
                        _repCount.value += 1
                        _onRepCounted.tryEmit(Unit)
                        lastCameraRepTime = curTime
                    }
                }
            }
            isJacking = (cameraJackState == 1) || classifierResult.isJacking
        }

        updateUserStatus(imu, isJacking)

        _jackResult.value = classifierResult.copy(
            repCount = _repCount.value,
            isJacking = isJacking,
            imuConfidence = if (mode == DetectionMode.IMU && isJacking) 0.98f else classifierResult.imuConfidence,
            poseConfidence = if (mode == DetectionMode.CAMERA && isJacking) 0.98f else classifierResult.poseConfidence
        )
    }

    private fun updateUserStatus(imu: com.example.sensor.ImuData, isJacking: Boolean) {
        val motionMagnitude = kotlin.math.sqrt(imu.accelX * imu.accelX + imu.accelY * imu.accelY + imu.accelZ * imu.accelZ)
        val angularSpeed = kotlin.math.abs(imu.gyroX) + kotlin.math.abs(imu.gyroY) + kotlin.math.abs(imu.gyroZ)

        _userStatus.value = when {
            isJacking -> UserStatus("SALTANDO: POLICHINELO 🤸", Color(0xFFFBBF24))
            motionMagnitude > 4.5f || angularSpeed > 2.2f -> UserStatus("CORRENDO / ACELERADO ⚡", Color(0xFF34D399))
            motionMagnitude > 1.2f || angularSpeed > 1.0f -> UserStatus("ANDANDO / MOVIMENTO 🚶", Color(0xFF3B82F6))
            motionMagnitude > 0.3f || angularSpeed > 0.3f -> UserStatus("INCLINANDO / SUAVE 🍃", Color(0xFF94A3B8))
            else -> UserStatus("REPOUSO ABSOLUTO 😴", Color(0xFF475569))
        }
    }

    fun toggleStreaming() {
        if (isStreaming.value) {
            streamClient.stopStreaming()
        } else {
            streamClient.startStreaming(
                targetUrl = _targetUrl.value,
                intervalMs = 500L,
                getPayload = { generateStreamPayload() }
            )
        }
    }

    private fun generateStreamPayload(): String {
        return try {
            val jr = _jackResult.value
            val imu = sensorTracker.imuState.value
            
            if (jr == null) "{}"
            else JSONObject().apply {
                put("isJacking", jr.isJacking)
                put("repCount", jr.repCount)
                put("formQuality", jr.formQuality.name)
                put("confidence", maxOf(jr.imuConfidence, jr.poseConfidence))
                put("timestamp", System.currentTimeMillis())
                put("imuData", imu.toJsonString())
            }.toString()
        } catch (e: Exception) {
            "{ \"error\": \"${e.message}\" }"
        }
    }

    fun toggleSensorSimulation() {
        sensorTracker.forceSimulation(!isSimulating.value)
    }

    fun clearClientStats() {
        streamClient.clearStats()
        _repCount.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        stopCamera()
        sensorTracker.stop()
        streamClient.stopStreaming()
        jackJob?.cancel()
    }
}
