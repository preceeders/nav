package com.hu.nav.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 融合罗盘朝向。优先旋转矢量（陀螺+地磁），按屏幕方向重映射，再做圆周低通。
 */
class CompassRepository(context: Context) {
    private val app = context.applicationContext
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun headings(): Flow<Float> = callbackFlow {
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (rotationVector == null && (accelerometer == null || magnetometer == null)) {
            close()
            return@callbackFlow
        }

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var hasGravity = false
        var hasMag = false
        var filtered: Float? = null
        var lastEmitAt = 0L

        fun emitHeading(matrix: FloatArray) {
            remapForDisplay(matrix, remapped)
            SensorManager.getOrientation(remapped, orientation)
            var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (heading < 0f) heading += 360f
            val previous = filtered
            filtered = if (previous == null) heading else lowPassHeading(previous, heading, ALPHA)
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitAt < EMIT_INTERVAL_MS) return
            lastEmitAt = now
            trySend(filtered ?: heading)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                try {
                    when (event.sensor.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            SensorManager.getRotationMatrixFromVector(rotation, event.values)
                            emitHeading(rotation)
                        }
                        Sensor.TYPE_ACCELEROMETER -> {
                            System.arraycopy(event.values, 0, gravity, 0, 3)
                            hasGravity = true
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                            hasMag = true
                        }
                    }
                    if (rotationVector != null) return
                    if (!hasGravity || !hasMag) return
                    if (!SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) return
                    emitHeading(rotation)
                } catch (_: Throwable) {
                    // Application Context 没有 Display，不能让传感器回调把导航打崩
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val delay = SensorManager.SENSOR_DELAY_GAME
        if (rotationVector != null) {
            sensorManager.registerListener(listener, rotationVector, delay)
        } else {
            sensorManager.registerListener(listener, accelerometer, delay)
            sensorManager.registerListener(listener, magnetometer, delay)
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private fun remapForDisplay(inR: FloatArray, outR: FloatArray) {
        val displayRotation = currentDisplayRotation()
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR)) {
            System.arraycopy(inR, 0, outR, 0, 9)
        }
    }

    private fun currentDisplayRotation(): Int {
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    companion object {
        private const val ALPHA = 0.28f
        private const val EMIT_INTERVAL_MS = 80L

        internal fun lowPassHeading(previous: Float, current: Float, alpha: Float): Float {
            var delta = current - previous
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            var out = previous + alpha * delta
            if (out < 0f) out += 360f
            if (out >= 360f) out -= 360f
            return out
        }
    }
}
