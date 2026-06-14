package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.client.StreamClient
import com.example.ml.DetectionMode
import com.example.ml.FormQuality
import com.example.ml.JackClassifier
import com.example.ml.JackResult
import com.example.ml.PoseDetector
import com.example.ml.PoseResult
import com.example.model.Verdict
import com.example.sensor.SensorTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorTracker = SensorTracker(application)
    private val streamClient = StreamClient()
    val jackClassifier = JackClassifier()
    val poseDetector = PoseDetector(application)

    val imuState = sensorTracker.imuState
    val isSimulating = sensorTracker.isSimulating

    val isStreaming = streamClient.isStreaming
    val successCount = streamClient.successCount
    val errorCount = streamClient.errorCount
    val streamLogs = streamClient.streamLogs
    val lastPostPayload = streamClient.lastPostPayload

    // UI Settings
    private val _targetUrl = MutableStateFlow("https://reasonable-unity-production.up.railway.app/api/jackresult")
    val targetUrl = _targetUrl.asStateFlow()

    private val _streamIntervalMs = MutableStateFlow(500L)
    val streamIntervalMs = _streamIntervalMs.asStateFlow()

    // Detection Mode
    private val _selectedMode = MutableStateFlow(DetectionMode.IMU)
    val selectedMode: StateFlow<DetectionMode> = _selectedMode.asStateFlow()

    private val _jackResult = MutableStateFlow<JackResult?>(null)
    val jackResult: StateFlow<JackResult?> = _jackResult.asStateFlow()

    private val _poseResult = MutableStateFlow<PoseResult?>(null)
    val poseResult: StateFlow<PoseResult?> = _poseResult.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    // Verdict populated from local jack result
    private val _verdict = MutableStateFlow<Verdict?>(null)
    val verdict = _verdict.asStateFlow()

    private val mlScope = CoroutineScope(Dispatchers.Default)
    private var jackJob: Job? = null

    private val _totalRepCount = MutableStateFlow(0)
    val repCount: StateFlow<Int> = _totalRepCount.asStateFlow()

    private var lastJumpTime = 0L
    private var lastCameraRepTime = 0L
    private var cameraJackState = 0 // 0: Down, 1: Up

    init {
        sensorTracker.start()
        startJackLoop() 
    }

    fun updateTargetUrl(url: String) {
        _targetUrl.value = url
    }

    fun updateStreamInterval(interval: Long) {
        _streamIntervalMs.value = interval
    }

    fun setDetectionMode(mode: DetectionMode) {
        val prev = _selectedMode.value
        _selectedMode.value = mode

        if (mode == DetectionMode.IMU) {
            stopCamera()
        } else if (mode == DetectionMode.CAMERA) {
            if (prev == DetectionMode.IMU) {
                startCamera()
            }
        }
    }

    fun startCamera() {
        if (!poseDetector.load()) return
        _isCameraActive.value = true
    }

    fun stopCamera() {
        _isCameraActive.value = false
        _poseResult.value = null
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
                val classifierResult = jackClassifier.classify(imu, pose, mode)
                val curTime = System.currentTimeMillis()
                
                var isJacking = false

                if (mode == DetectionMode.IMU) {
                    val motionMag = kotlin.math.sqrt(imu.accelX * imu.accelX + imu.accelY * imu.accelY + imu.accelZ * imu.accelZ)
                    if (motionMag > 13.2f && curTime - lastJumpTime > 1000) {
                        lastJumpTime = curTime
                        _totalRepCount.value += 1
                    }
                    isJacking = (curTime - lastJumpTime < 1000)
                } else if (mode == DetectionMode.CAMERA && pose != null) {
                    // Simple Camera Rep Detection
                    val keypoints = pose.keypoints
                    if (keypoints.size > 10) {
                        val lWrist = keypoints[9]
                        val rWrist = keypoints[10]
                        val lShoulder = keypoints[5]
                        val rShoulder = keypoints[6]
                        
                        if (lWrist.score > 0.5f && rWrist.score > 0.5f) {
                            val avgWristY = (lWrist.y + rWrist.y) / 2f
                            val avgShoulderY = (lShoulder.y + rShoulder.y) / 2f
                            
                            if (cameraJackState == 0 && avgWristY < avgShoulderY - 0.05f) {
                                cameraJackState = 1
                            } else if (cameraJackState == 1 && avgWristY > avgShoulderY + 0.05f) {
                                cameraJackState = 0
                                if (curTime - lastCameraRepTime > 800) {
                                    _totalRepCount.value += 1
                                    lastCameraRepTime = curTime
                                }
                            }
                        }
                    }
                    isJacking = (cameraJackState == 1) || (classifierResult.isJacking)
                }

                val updatedResult = classifierResult.copy(
                    repCount = _totalRepCount.value,
                    isJacking = isJacking,
                    imuConfidence = if (mode == DetectionMode.IMU && isJacking) 0.98f else classifierResult.imuConfidence,
                    poseConfidence = if (mode == DetectionMode.CAMERA && isJacking) 0.98f else classifierResult.poseConfidence
                )
                
                _jackResult.value = updatedResult

                _verdict.value = Verdict(
                    repCount = updatedResult.repCount,
                    formQuality = updatedResult.formQuality.name,
                    isJacking = updatedResult.isJacking,
                    confidence = maxOf(updatedResult.imuConfidence, updatedResult.poseConfidence),
                    distanceCm = 0,
                    manualReps = 0
                )

                delay(50)
            }
        }
    }

    fun toggleStreaming() {
        if (isStreaming.value) {
            streamClient.stopStreaming()
        } else {
            streamClient.startStreaming(
                targetUrl = _targetUrl.value,
                intervalMs = _streamIntervalMs.value,
                getPayload = {
                    try {
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
            )
        }
    }

    fun toggleSensorSimulation() {
        sensorTracker.forceSimulation(!isSimulating.value)
    }

    fun clearClientStats() {
        streamClient.clearStats()
        _totalRepCount.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        stopCamera()
        sensorTracker.stop()
        streamClient.stopStreaming()
        jackJob?.cancel()
        poseDetector.close()
    }
}
