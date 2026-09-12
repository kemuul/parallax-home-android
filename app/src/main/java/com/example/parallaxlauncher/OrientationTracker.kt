package com.example.parallaxlauncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Produces device rotation relative to a user-selected neutral position.
 *
 * The preferred GAME_ROTATION_VECTOR is ideal for a visual effect: it fuses the
 * gyroscope and accelerometer but ignores magnetic north, so nearby metal is
 * less likely to make the home screen jump. ROTATION_VECTOR is the next choice.
 * An accelerometer-only mode remains useful on low-end devices, but it cannot
 * observe rotation around gravity and therefore cannot provide full 3D motion.
 */
class OrientationTracker(
    context: Context,
    private val onOrientation: (pitch: Float, roll: Float, yaw: Float) -> Unit,
    private val onStatusChanged: (message: String, full3d: Boolean) -> Unit
) : SensorEventListener {

    companion object {
        private const val STABLE_ANGLE_DEGREES = 0.45f
        private const val REQUIRED_STABLE_SAMPLES = 12
        private const val MIN_CALIBRATION_NANOS = 450_000_000L
        private const val AUTO_RECENTER_NANOS = 5_000_000_000L
        private const val STILLNESS_RADIUS_DEGREES = 1.0f
        private const val AUTO_RECENTER_NEUTRAL_DEGREES = 2.0f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val deviceMatrix = FloatArray(9)
    private val screenMatrix = FloatArray(9)
    private val relativeMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val previousScreenMatrix = FloatArray(9)
    private val stillAnchorMatrix = FloatArray(9)
    private var referenceMatrix: FloatArray? = null
    private var latestMatrix: FloatArray? = null
    private var lastDisplayRotation = -1
    private var hasPreviousMatrix = false
    private var stableSamples = 0
    private var calibrationStartedNanos = 0L
    private var hasStillAnchorMatrix = false
    private var matrixStillSinceNanos = 0L
    private var matrixAutoCentered = false

    private val filteredGravity = FloatArray(3)
    private var gravityReady = false
    private var latestScreenGravity: FloatArray? = null
    private var referenceGravity: FloatArray? = null
    private var accelWarmupSamples = 0
    private val stillAnchorGravity = FloatArray(3)
    private var hasStillAnchorGravity = false
    private var gravityStillSinceNanos = 0L
    private var gravityAutoCentered = false
    private var latestTimestampNanos = 0L

    private var activeSensor: Sensor? = null
    private var running = false
    private var activeSensorLabel = "Motion sensor"
    private var activeSensorIsFull3d = false

    fun start() {
        if (running) return

        activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val sensor = activeSensor
        if (sensor == null) {
            onStatusChanged("No motion sensor", false)
            onOrientation(0f, 0f, 0f)
            return
        }

        running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        resetStillnessTracking()
        activeSensorIsFull3d = sensor.type != Sensor.TYPE_ACCELEROMETER
        activeSensorLabel = when (sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
            else -> "Accelerometer fallback"
        }
        onStatusChanged(
            if (!running) "Sensor unavailable"
            else if (
                (sensor.type == Sensor.TYPE_ACCELEROMETER && referenceGravity == null) ||
                (sensor.type != Sensor.TYPE_ACCELEROMETER && referenceMatrix == null)
            ) "Hold steady — calibrating"
            else activeSensorLabel,
            activeSensorIsFull3d && running
        )
    }

    fun stop() {
        if (running) sensorManager.unregisterListener(this)
        running = false
    }

    /** Make the phone's current pose the new straight-ahead position. */
    fun recenter() {
        val hasSample = latestMatrix != null || latestScreenGravity != null
        referenceMatrix = latestMatrix?.clone()
        referenceGravity = latestScreenGravity?.clone()
        stableSamples = 0
        hasPreviousMatrix = false
        resetStillnessTracking()
        onOrientation(0f, 0f, 0f)
        onStatusChanged(
            if (hasSample) "Centered • $activeSensorLabel" else "Hold steady — calibrating",
            activeSensorIsFull3d
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestTimestampNanos = event.timestamp
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            updateFromAccelerometer(event.values, event.timestamp)
        } else {
            updateFromRotationVector(event.values, event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateFromRotationVector(values: FloatArray, timestampNanos: Long) {
        SensorManager.getRotationMatrixFromVector(deviceMatrix, values)
        val displayRotation = currentDisplayRotation()

        if (displayRotation != lastDisplayRotation) {
            // Screen axes changed (for example portrait -> landscape). Recalibrate
            // so the same math remains correct in every display orientation.
            lastDisplayRotation = displayRotation
            referenceMatrix = null
            resetStillnessTracking()
            beginStableCalibration(timestampNanos)
        }

        val remapped = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                deviceMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, screenMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                deviceMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, screenMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                deviceMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, screenMatrix
            )
            else -> {
                deviceMatrix.copyInto(screenMatrix)
                true
            }
        }
        if (!remapped) return

        latestMatrix = screenMatrix.clone()
        if (referenceMatrix != null) updateMatrixAutoRecenter(screenMatrix, timestampNanos)
        val reference = referenceMatrix
        if (reference == null) {
            onOrientation(0f, 0f, 0f)
            updateStableCalibration(screenMatrix, timestampNanos)
            return
        }

        // Relative rotation = transpose(reference) * current.
        multiplyTransposeLeft(reference, screenMatrix, relativeMatrix)
        SensorManager.getOrientation(relativeMatrix, orientation)

        onOrientation(
            Math.toDegrees(orientation[1].toDouble()).toFloat(),
            Math.toDegrees(orientation[2].toDouble()).toFloat(),
            Math.toDegrees(orientation[0].toDouble()).toFloat()
        )
    }

    private fun beginStableCalibration(timestampNanos: Long) {
        calibrationStartedNanos = timestampNanos
        stableSamples = 0
        hasPreviousMatrix = false
        onStatusChanged("Hold steady — calibrating", activeSensorIsFull3d)
    }

    private fun updateStableCalibration(matrix: FloatArray, timestampNanos: Long) {
        if (calibrationStartedNanos == 0L) beginStableCalibration(timestampNanos)

        if (hasPreviousMatrix) {
            // trace(transpose(previous) * current) equals the element-wise dot sum.
            // For rotation matrices: angle = acos((trace - 1) / 2).
            var trace = 0f
            for (i in 0..8) trace += previousScreenMatrix[i] * matrix[i]
            val cosine = ((trace - 1f) * 0.5f).coerceIn(-1f, 1f)
            val movementDegrees = Math.toDegrees(acos(cosine.toDouble())).toFloat()
            stableSamples = if (movementDegrees <= STABLE_ANGLE_DEGREES) stableSamples + 1 else 0
        }

        matrix.copyInto(previousScreenMatrix)
        hasPreviousMatrix = true

        val waitedLongEnough = timestampNanos - calibrationStartedNanos >= MIN_CALIBRATION_NANOS
        if (waitedLongEnough && stableSamples >= REQUIRED_STABLE_SAMPLES) {
            referenceMatrix = matrix.clone()
            stableSamples = 0
            resetStillnessTracking()
            onOrientation(0f, 0f, 0f)
            onStatusChanged(activeSensorLabel, activeSensorIsFull3d)
        }
    }

    private fun updateFromAccelerometer(values: FloatArray, timestampNanos: Long) {
        if (!gravityReady) {
            values.copyInto(filteredGravity, endIndex = 3)
            gravityReady = true
        } else {
            // Remove small hand vibration before deriving a gravity direction.
            for (i in 0..2) filteredGravity[i] = 0.85f * filteredGravity[i] + 0.15f * values[i]
        }

        val screenGravity = remapVectorToScreen(filteredGravity, currentDisplayRotation())
        normalizeInPlace(screenGravity)
        latestScreenGravity = screenGravity.clone()
        if (referenceGravity == null) {
            // Give the low-pass filter a moment to settle on startup.
            accelWarmupSamples++
            if (accelWarmupSamples >= 12) {
                referenceGravity = screenGravity.clone()
                resetStillnessTracking()
                onStatusChanged(activeSensorLabel, activeSensorIsFull3d)
            }
            onOrientation(0f, 0f, 0f)
            return
        }

        updateGravityAutoRecenter(screenGravity, timestampNanos)
        val reference = referenceGravity ?: return
        val axisX = reference[1] * screenGravity[2] - reference[2] * screenGravity[1]
        val axisY = reference[2] * screenGravity[0] - reference[0] * screenGravity[2]
        val axisZ = reference[0] * screenGravity[1] - reference[1] * screenGravity[0]
        val axisLength = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
        val dot = (reference[0] * screenGravity[0] +
            reference[1] * screenGravity[1] +
            reference[2] * screenGravity[2]).coerceIn(-1f, 1f)
        val angleDegrees = Math.toDegrees(acos(dot.toDouble())).toFloat()

        if (axisLength < 0.0001f) {
            onOrientation(0f, 0f, 0f)
        } else {
            onOrientation(
                axisX / axisLength * angleDegrees,
                axisY / axisLength * angleDegrees,
                axisZ / axisLength * angleDegrees
            )
        }
    }

    private fun updateMatrixAutoRecenter(matrix: FloatArray, timestampNanos: Long) {
        val activeReference = referenceMatrix ?: return
        if (matrixAngleDegrees(activeReference, matrix) > AUTO_RECENTER_NEUTRAL_DEGREES) {
            hasStillAnchorMatrix = false
            matrixAutoCentered = false
            return
        }

        if (!hasStillAnchorMatrix) {
            matrix.copyInto(stillAnchorMatrix)
            hasStillAnchorMatrix = true
            matrixStillSinceNanos = timestampNanos
            matrixAutoCentered = false
            return
        }

        if (matrixAngleDegrees(stillAnchorMatrix, matrix) > STILLNESS_RADIUS_DEGREES) {
            matrix.copyInto(stillAnchorMatrix)
            matrixStillSinceNanos = timestampNanos
            matrixAutoCentered = false
            return
        }

        if (!matrixAutoCentered && timestampNanos - matrixStillSinceNanos >= AUTO_RECENTER_NANOS) {
            referenceMatrix = matrix.clone()
            matrixAutoCentered = true
            onOrientation(0f, 0f, 0f)
            onStatusChanged("Auto-centered - $activeSensorLabel", activeSensorIsFull3d)
        }
    }

    private fun updateGravityAutoRecenter(gravity: FloatArray, timestampNanos: Long) {
        val activeReference = referenceGravity ?: return
        val neutralDot = (activeReference[0] * gravity[0] +
            activeReference[1] * gravity[1] +
            activeReference[2] * gravity[2]).coerceIn(-1f, 1f)
        val neutralDegrees = Math.toDegrees(acos(neutralDot.toDouble())).toFloat()
        if (neutralDegrees > AUTO_RECENTER_NEUTRAL_DEGREES) {
            hasStillAnchorGravity = false
            gravityAutoCentered = false
            return
        }

        if (!hasStillAnchorGravity) {
            gravity.copyInto(stillAnchorGravity)
            hasStillAnchorGravity = true
            gravityStillSinceNanos = timestampNanos
            gravityAutoCentered = false
            return
        }

        val dot = (stillAnchorGravity[0] * gravity[0] +
            stillAnchorGravity[1] * gravity[1] +
            stillAnchorGravity[2] * gravity[2]).coerceIn(-1f, 1f)
        val movementDegrees = Math.toDegrees(acos(dot.toDouble())).toFloat()
        if (movementDegrees > STILLNESS_RADIUS_DEGREES) {
            gravity.copyInto(stillAnchorGravity)
            gravityStillSinceNanos = timestampNanos
            gravityAutoCentered = false
            return
        }

        if (!gravityAutoCentered && timestampNanos - gravityStillSinceNanos >= AUTO_RECENTER_NANOS) {
            referenceGravity = gravity.clone()
            gravityAutoCentered = true
            onOrientation(0f, 0f, 0f)
            onStatusChanged("Auto-centered - $activeSensorLabel", activeSensorIsFull3d)
        }
    }

    private fun resetStillnessTracking() {
        hasStillAnchorMatrix = false
        matrixStillSinceNanos = latestTimestampNanos
        matrixAutoCentered = false
        hasStillAnchorGravity = false
        gravityStillSinceNanos = latestTimestampNanos
        gravityAutoCentered = false
    }

    private fun matrixAngleDegrees(first: FloatArray, second: FloatArray): Float {
        var trace = 0f
        for (i in 0..8) trace += first[i] * second[i]
        val cosine = ((trace - 1f) * 0.5f).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }

    private fun multiplyTransposeLeft(left: FloatArray, right: FloatArray, output: FloatArray) {
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0f
                for (i in 0..2) sum += left[i * 3 + row] * right[i * 3 + column]
                output[row * 3 + column] = sum
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        windowManager.defaultDisplay.rotation

    private fun remapVectorToScreen(vector: FloatArray, rotation: Int): FloatArray {
        val x = vector[0]
        val y = vector[1]
        val z = vector[2]
        return when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(y, -x, z)
            Surface.ROTATION_180 -> floatArrayOf(-x, -y, z)
            Surface.ROTATION_270 -> floatArrayOf(-y, x, z)
            else -> floatArrayOf(x, y, z)
        }
    }

    private fun normalizeInPlace(vector: FloatArray) {
        val length = sqrt(max(0.000001f, vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]))
        for (i in 0..2) vector[i] /= length
    }
}
