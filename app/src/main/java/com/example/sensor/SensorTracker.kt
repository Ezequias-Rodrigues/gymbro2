package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin

data class ImuData(
    val timestamp: Long = System.currentTimeMillis(),
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 9.8f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val isSimulated: Boolean = false
) {
    fun toJsonString(): String {
        return """{
  "timestamp": $timestamp,
  "device": "Android IMU Sensor",
  "accelerometer": {
    "x": ${"%.4f".format(accelX)},
    "y": ${"%.4f".format(accelY)},
    "z": ${"%.4f".format(accelZ)}
  },
  "gyroscope": {
    "x": ${"%.4f".format(gyroX)},
    "y": ${"%.4f".format(gyroY)},
    "z": ${"%.4f".format(gyroZ)}
  },
  "sensor_source": "${if (isSimulated) "SIMULATED_SENSOR" else "PHYSICAL_SENSOR"}"
}"""
    }
}

class SensorTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _imuState = MutableStateFlow(ImuData())
    val imuState: StateFlow<ImuData> = _imuState.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Current buffers
    private var curAccelX = 0f
    private var curAccelY = 0f
    private var curAccelZ = 9.8f
    private var curGyroX = 0f
    private var curGyroY = 0f
    private var curGyroZ = 0f

    private var lastEventTime = 0L

    fun start() {
        val hasAccel = accelerometer != null && sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        ) == true

        val hasGyro = gyroscope != null && sensorManager?.registerListener(
            this,
            gyroscope,
            SensorManager.SENSOR_DELAY_UI
        ) == true

        // If a physical sensor registration fails or has no sensors, we spin up simulated fallback.
        if (!hasAccel || !hasGyro) {
            startSimulation()
        } else {
            // Watchdog: If we don't get any events after 2 seconds, trigger simulation automatically
            activeJob = scope.launch {
                delay(2000)
                if (lastEventTime == 0L) {
                    startSimulation()
                }
            }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        activeJob?.cancel()
        _isSimulating.value = false
    }

    fun forceSimulation(enable: Boolean) {
        stop()
        if (enable) {
            startSimulation()
        } else {
            start()
        }
    }

    private fun startSimulation() {
        activeJob?.cancel()
        _isSimulating.value = true
        activeJob = scope.launch {
            var tick = 0f
            while (true) {
                tick += 0.15f
                val accelX = sin(tick) * 2.5f
                val accelY = sin(tick + 1.5f) * 1.8f
                val accelZ = 9.8f + sin(tick + 3f) * 0.9f
                val gyroX = sin(tick * 0.8f) * 0.5f
                val gyroY = sin(tick * 1.2f + 0.5f) * 0.6f
                val gyroZ = sin(tick * 0.5f + 1.2f) * 0.4f

                _imuState.value = ImuData(
                    timestamp = System.currentTimeMillis(),
                    accelX = accelX,
                    accelY = accelY,
                    accelZ = accelZ,
                    gyroX = gyroX,
                    gyroY = gyroY,
                    gyroZ = gyroZ,
                    isSimulated = true
                )
                delay(120) // update similar to UI delay
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        lastEventTime = System.currentTimeMillis()
        if (_isSimulating.value) return // Ignore physical sensor events if simulation is forced

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                curAccelX = event.values[0]
                curAccelY = event.values[1]
                curAccelZ = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                curGyroX = event.values[0]
                curGyroY = event.values[1]
                curGyroZ = event.values[2]
            }
        }

        _imuState.value = ImuData(
            timestamp = System.currentTimeMillis(),
            accelX = curAccelX,
            accelY = curAccelY,
            accelZ = curAccelZ,
            gyroX = curGyroX,
            gyroY = curGyroY,
            gyroZ = curGyroZ,
            isSimulated = false
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
