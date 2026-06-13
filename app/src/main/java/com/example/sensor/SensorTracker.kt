package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
  "sensor_source": "PHYSICAL_SENSOR"
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

    // Current buffers
    private var curAccelX = 0f
    private var curAccelY = 0f
    private var curAccelZ = 9.8f
    private var curGyroX = 0f
    private var curGyroY = 0f
    private var curGyroZ = 0f

    fun start() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun forceSimulation(enable: Boolean) {
        // No-op - Simulations disabled per user request
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

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
