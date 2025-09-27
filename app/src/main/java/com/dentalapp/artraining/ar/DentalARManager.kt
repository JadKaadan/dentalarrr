package com.dentalapp.artraining.ar

import android.content.Context
import android.util.Log
import com.dentalapp.artraining.data.Pose3D
import com.dentalapp.artraining.data.PoseOffset
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.Tolerance
import com.dentalapp.artraining.data.ToothPlacement
import com.dentalapp.artraining.data.ToothStatus
import com.dentalapp.artraining.data.TrainingSession
import com.google.ar.core.*
import com.dentalapp.artraining.ml.ToothDetector
import kotlinx.coroutines.*

class DentalARManager(
    private val context: Context,
    private val arSession: Session
) {
    companion object {
        private const val TAG = "DentalARManager"
        private const val APRIL_TAG_SIZE = 0.05f // 5cm AprilTag
    }

    // Core components
    private var currentProject: Project? = null
    private var isCalibrated = false
    private var calibrationAnchor: Anchor? = null
    private val toothNodes = mutableMapOf<String, SimpleToothNode>()

    // ML components
    private lateinit var toothDetector: ToothDetector

    // Session tracking
    private var currentSession: TrainingSession? = null
    private val placementTimes = mutableMapOf<String, Long>()

    // Callbacks
    var onToothStatusChanged: ((toothId: String, status: ToothStatus, guidance: String?) -> Unit)? = null
    var onProgressUpdated: ((completed: Int, total: Int) -> Unit)? = null
    var onCalibrationStatusChanged: ((isCalibrated: Boolean) -> Unit)? = null

    init {
        initializeComponents()
    }

    private fun initializeComponents() {
        // Initialize ML detector
        toothDetector = ToothDetector(context)

        Log.d(TAG, "DentalARManager initialized")
    }

    fun loadProject(project: Project) {
        currentProject = project
        setupTeethForProject(project)

        // Start new session
        currentSession = TrainingSession(
            projectId = project.id,
            deviceInfo = getDeviceInfo(),
            startTime = System.currentTimeMillis()
        )

        Log.d(TAG, "Project loaded: ${project.name}")
    }

    private fun setupTeethForProject(project: Project) {
        toothNodes.clear()

        // Create tooth nodes for each enabled tooth in the project
        project.enabledTeeth.forEach { toothId ->
            val idealPose = project.idealPoses[toothId]
            if (idealPose != null) {
                val toothNode = SimpleToothNode(
                    toothId = toothId,
                    idealPose = idealPose,
                    tolerance = project.tolerance
                )
                toothNodes[toothId] = toothNode
            }
        }

        updateProgress()
    }

    fun startCalibration() {
        Log.d(TAG, "Starting calibration - looking for AprilTag")
        onCalibrationStatusChanged?.invoke(false)

        // For MVP, we'll simulate calibration
        // In production, this would use OpenCV for AprilTag detection
        simulateCalibration()
    }

    private fun simulateCalibration() {
        // Simulate calibration success after 2 seconds
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)

            // Create a dummy anchor at the center
            val pose = Pose.makeTranslation(0f, 0f, -0.5f)
            calibrationAnchor = arSession.createAnchor(pose)
            isCalibrated = true

            onCalibrationStatusChanged?.invoke(true)
            startToothDetection()

            Log.d(TAG, "Calibration completed (simulated)")
        }
    }

    private fun startToothDetection() {
        // For MVP, we'll simulate tooth detection
        // In production, this would process camera frames with ML
        simulateToothDetection()
    }

    private fun simulateToothDetection() {
        CoroutineScope(Dispatchers.Default).launch {
            while (isCalibrated) {
                delay(100) // 10 FPS simulation

                withContext(Dispatchers.Main) {
                    // Simulate detecting the first pending tooth
                    val pendingTooth = toothNodes.values.find { it.status == ToothStatus.PENDING }

                    pendingTooth?.let { toothNode ->
                        // Simulate pose detection with some randomness
                        val randomOffset = PoseOffset(
                            translation = SimpleVector3D(
                                (Math.random() * 4 - 2).toFloat(), // ±2mm
                                (Math.random() * 4 - 2).toFloat(),
                                (Math.random() * 4 - 2).toFloat()
                            ),
                            rotation = SimpleVector3D(
                                (Math.random() * 6 - 3).toFloat(), // ±3 degrees
                                (Math.random() * 6 - 3).toFloat(),
                                (Math.random() * 6 - 3).toFloat()
                            ),
                            translationMagnitude = (Math.random() * 3).toFloat(),
                            rotationMagnitude = (Math.random() * 4).toFloat()
                        )

                        updateToothPose(toothNode, randomOffset)
                        checkTolerances(toothNode)
                    }
                }
            }
        }
    }

    private fun updateToothPose(toothNode: SimpleToothNode, offset: PoseOffset) {
        toothNode.currentOffset = offset

        // Generate guidance text
        val guidance = generateGuidanceText(offset, toothNode.tolerance)
        onToothStatusChanged?.invoke(toothNode.toothId, toothNode.status, guidance)
    }

    private fun checkTolerances(toothNode: SimpleToothNode) {
        val offset = toothNode.currentOffset ?: return
        val tolerance = toothNode.tolerance

        val withinTolerance =
            offset.translationMagnitude <= tolerance.positionMm &&
                    offset.rotationMagnitude <= tolerance.rotationDeg

        if (withinTolerance && toothNode.status != ToothStatus.PLACED) {
            markToothAsPlaced(toothNode)
        } else if (!withinTolerance && toothNode.status == ToothStatus.PLACED) {
            toothNode.status = ToothStatus.PENDING
        }
    }

    private fun markToothAsPlaced(toothNode: SimpleToothNode) {
        toothNode.status = ToothStatus.PLACED
        placementTimes[toothNode.toothId] = System.currentTimeMillis()

        updateProgress()

        Log.d(TAG, "Tooth ${toothNode.toothId} placed successfully")
    }

    private fun generateGuidanceText(offset: PoseOffset, tolerance: Tolerance): String {
        val suggestions = mutableListOf<String>()

        // Translation guidance
        if (Math.abs(offset.translation.x) > tolerance.positionMm / 2) {
            val direction = if (offset.translation.x > 0) "mesial" else "distal"
            suggestions.add("Move ${String.format("%.1f", Math.abs(offset.translation.x))}mm $direction")
        }

        if (Math.abs(offset.translation.y) > tolerance.positionMm / 2) {
            val direction = if (offset.translation.y > 0) "up" else "down"
            suggestions.add("Move ${String.format("%.1f", Math.abs(offset.translation.y))}mm $direction")
        }

        if (Math.abs(offset.translation.z) > tolerance.positionMm / 2) {
            val direction = if (offset.translation.z > 0) "forward" else "back"
            suggestions.add("Move ${String.format("%.1f", Math.abs(offset.translation.z))}mm $direction")
        }

        // Rotation guidance
        if (Math.abs(offset.rotation.y) > tolerance.rotationDeg / 2) {
            val direction = if (offset.rotation.y > 0) "clockwise" else "counterclockwise"
            suggestions.add("Rotate ${String.format("%.1f", Math.abs(offset.rotation.y))}° $direction")
        }

        return when {
            suggestions.isEmpty() -> "Perfect! Within tolerance"
            suggestions.size == 1 -> suggestions[0]
            else -> suggestions.take(2).joinToString(", ")
        }
    }

    private fun updateProgress() {
        val completed = toothNodes.values.count { it.status == ToothStatus.PLACED }
        val total = toothNodes.size
        onProgressUpdated?.invoke(completed, total)

        if (completed == total) {
            completeSession()
        }
    }

    private fun completeSession() {
        currentSession?.let { session ->
            session.endTime = System.currentTimeMillis()
            session.completedTeeth = toothNodes.values.map { toothNode ->
                ToothPlacement(
                    toothId = toothNode.toothId,
                    finalOffset = toothNode.currentOffset ?: PoseOffset(),
                    placementTime = placementTimes[toothNode.toothId] ?: 0L,
                    status = toothNode.status
                )
            }

            session.calculateMetrics()

            Log.d(TAG, "Session completed successfully")
        }
    }

    fun exportSession(): TrainingSession? {
        completeSession()
        return currentSession
    }

    fun resetSession() {
        toothNodes.values.forEach { toothNode ->
            toothNode.status = ToothStatus.PENDING
            toothNode.currentOffset = null
        }

        placementTimes.clear()
        updateProgress()

        Log.d(TAG, "Session reset")
    }

    fun cleanup() {
        calibrationAnchor?.detach()
        toothNodes.clear()
        toothDetector.cleanup()
        isCalibrated = false

        Log.d(TAG, "DentalARManager cleaned up")
    }

    private fun getDeviceInfo(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }
}

// Simplified tooth node without 3D rendering dependencies
data class SimpleToothNode(
    val toothId: String,
    val idealPose: Pose3D,
    val tolerance: Tolerance,
    var currentOffset: PoseOffset? = null,
    var status: ToothStatus = ToothStatus.PENDING
)

// Simple Vector3D for pose offsets
data class SimpleVector3D(
    val x: Float,
    val y: Float,
    val z: Float
)