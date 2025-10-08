package com.dentalapp.artraining.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.dentalapp.artraining.data.Pose3D
import com.dentalapp.artraining.data.Quaternion3D
import com.dentalapp.artraining.data.Vector3D
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * Advanced Tooth Detector using YOLOv8 TFLite model
 * Supports both placeholder mode and real model detection
 */
class AdvancedToothDetector(private val context: Context) {

    companion object {
        private const val TAG = "AdvancedToothDetector"

        // Model configuration - adjust when you have the real model
        private const val MODEL_FILE = "tooth_detection_yolov8.tflite"
        private const val MODEL_INPUT_SIZE = 640 // YOLOv8 standard input
        private const val NUM_CLASSES = 32 // All permanent teeth (FDI notation)

        // Detection thresholds
        private const val CONFIDENCE_THRESHOLD = 0.60f // Increased for accuracy
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 50

        // Measurement tolerances (in mm)
        private const val PERFECT_TOLERANCE = 0.3f
        private const val GOOD_TOLERANCE = 0.5f
        private const val ACCEPTABLE_TOLERANCE = 1.0f
    }

    // Data classes for detection results
    data class Vector3(val x: Float, val y: Float, val z: Float) {
        fun magnitude() = sqrt(x * x + y * y + z * z)
    }

    data class DetectedTooth(
        val toothId: String,
        val confidence: Float,
        val boundingBox: BoundingBox,
        val pose: Pose3D,
        val surfaceNormal: Vector3,
        val landmarks: List<Vector3>,
        val optimalBracketPosition: Vector3
    )

    data class BoundingBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val centerX: Float = x + width / 2,
        val centerY: Float = y + height / 2
    )

    data class PositionFeedback(
        val distance: Float, // in mm
        val quality: QualityLevel,
        val guidance: String,
        val xOffset: Float,
        val yOffset: Float,
        val zOffset: Float
    )

    enum class QualityLevel {
        PERFECT,    // < 0.3mm
        GOOD,       // 0.3-0.5mm
        ACCEPTABLE, // 0.5-1.0mm
        NEEDS_ADJUSTMENT // > 1.0mm
    }

    // Tooth numbering (FDI notation)
    private val toothLabels = listOf(
        // Upper right quadrant: 11-18
        "11", "12", "13", "14", "15", "16", "17", "18",
        // Upper left quadrant: 21-28
        "21", "22", "23", "24", "25", "26", "27", "28",
        // Lower left quadrant: 31-38
        "31", "32", "33", "34", "35", "36", "37", "38",
        // Lower right quadrant: 41-48
        "41", "42", "43", "44", "45", "46", "47", "48"
    )

    // TensorFlow Lite components
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputBuffer: ByteBuffer
    private var isModelLoaded = false
    private var usePlaceholderMode = false

    // Camera intrinsics (will be set from ARCore)
    private var focalLengthX = 500f
    private var focalLengthY = 500f
    private var principalPointX = 320f
    private var principalPointY = 240f

    init {
        // Initialize input buffer
        val inputSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * 4 // RGBF32
        inputBuffer = ByteBuffer.allocateDirect(inputSize)
        inputBuffer.order(ByteOrder.nativeOrder())

        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            // Try to load the real model
            val modelFile = loadModelFile(MODEL_FILE)

            val compatList = CompatibilityList()
            val options = Interpreter.Options()

            // Use GPU if available
            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(
                    GpuDelegate.Options().apply {
                        setPrecisionLossAllowed(true) // Allow FP16
                        setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    }
                )
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU acceleration enabled")
            } else {
                options.setNumThreads(4)
                options.setUseXNNPACK(true) // Enable XNNPACK for CPU optimization
                Log.d(TAG, "Using optimized CPU inference")
            }

            interpreter = Interpreter(modelFile, options)
            isModelLoaded = true
            usePlaceholderMode = false

            Log.d(TAG, "YOLOv8 model loaded successfully")

        } catch (e: Exception) {
            Log.w(TAG, "Real model not found, using placeholder mode: ${e.message}")
            usePlaceholderMode = true
            isModelLoaded = true
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Set camera intrinsics from ARCore camera
     */
    fun setCameraIntrinsics(fx: Float, fy: Float, cx: Float, cy: Float) {
        focalLengthX = fx
        focalLengthY = fy
        principalPointX = cx
        principalPointY = cy
        Log.d(TAG, "Camera intrinsics updated: fx=$fx, fy=$fy, cx=$cx, cy=$cy")
    }

    /**
     * Main detection method
     */
    suspend fun detectTeeth(cameraImage: Image): List<DetectedTooth> {
        if (!isModelLoaded) {
            Log.w(TAG, "Model not loaded")
            return emptyList()
        }

        return try {
            val bitmap = convertImageToBitmap(cameraImage)

            if (usePlaceholderMode) {
                // Generate simulated detections for testing
                generatePlaceholderDetections(bitmap)
            } else {
                // Real model inference
                performRealDetection(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            emptyList()
        }
    }

    private fun performRealDetection(bitmap: Bitmap): List<DetectedTooth> {
        // Preprocess image
        preprocessImage(bitmap)

        // YOLOv8 output format: [batch, num_detections, 4 + num_classes]
        // where 4 = [x, y, w, h] and remaining are class probabilities
        val outputSize = MAX_DETECTIONS * (4 + NUM_CLASSES)
        val outputArray = Array(1) { FloatArray(outputSize) }

        // Run inference
        interpreter?.run(inputBuffer, outputArray)

        // Post-process results
        return postProcessYOLOv8Results(outputArray[0], bitmap.width, bitmap.height)
    }

    private fun preprocessImage(bitmap: Bitmap) {
        inputBuffer.rewind()

        // Resize to model input size
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            true
        )

        // Normalize to [0, 1] and convert to float buffer
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        for (pixel in pixels) {
            // Normalize RGB channels to [0, 1]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
    }

    private fun postProcessYOLOv8Results(
        output: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedTooth> {
        val detections = mutableListOf<RawDetection>()
        val stride = 4 + NUM_CLASSES

        // Parse output
        for (i in 0 until MAX_DETECTIONS) {
            val startIdx = i * stride

            val x = output[startIdx]
            val y = output[startIdx + 1]
            val w = output[startIdx + 2]
            val h = output[startIdx + 3]

            // Find class with max probability
            var maxClassIdx = 0
            var maxProb = 0f
            for (c in 0 until NUM_CLASSES) {
                val prob = output[startIdx + 4 + c]
                if (prob > maxProb) {
                    maxProb = prob
                    maxClassIdx = c
                }
            }

            // Filter by confidence
            if (maxProb < CONFIDENCE_THRESHOLD) continue
            if (maxClassIdx >= toothLabels.size) continue

            detections.add(
                RawDetection(
                    toothId = toothLabels[maxClassIdx],
                    confidence = maxProb,
                    x = x,
                    y = y,
                    w = w,
                    h = h
                )
            )
        }

        // Apply NMS
        val nmsDetections = applyNMS(detections)

        // Convert to full DetectedTooth objects
        return nmsDetections.map { raw ->
            convertToDetectedTooth(raw, imageWidth, imageHeight)
        }
    }

    private data class RawDetection(
        val toothId: String,
        val confidence: Float,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float
    )

    private fun applyNMS(detections: List<RawDetection>): List<RawDetection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<RawDetection>()

        for (detection in sorted) {
            var shouldKeep = true

            for (kept in selected) {
                val iou = calculateIOU(detection, kept)
                if (iou > IOU_THRESHOLD) {
                    shouldKeep = false
                    break
                }
            }

            if (shouldKeep) {
                selected.add(detection)
            }
        }

        return selected
    }

    private fun calculateIOU(det1: RawDetection, det2: RawDetection): Float {
        val x1 = maxOf(det1.x - det1.w / 2, det2.x - det2.w / 2)
        val y1 = maxOf(det1.y - det1.h / 2, det2.y - det2.h / 2)
        val x2 = minOf(det1.x + det1.w / 2, det2.x + det2.w / 2)
        val y2 = minOf(det1.y + det1.h / 2, det2.y + det2.h / 2)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = det1.w * det1.h
        val area2 = det2.w * det2.h
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }

    private fun convertToDetectedTooth(
        raw: RawDetection,
        imageWidth: Int,
        imageHeight: Int
    ): DetectedTooth {
        // Convert normalized coordinates to pixel coordinates
        val centerX = raw.x * imageWidth
        val centerY = raw.y * imageHeight
        val width = raw.w * imageWidth
        val height = raw.h * imageHeight

        val boundingBox = BoundingBox(
            x = centerX - width / 2,
            y = centerY - height / 2,
            width = width,
            height = height
        )

        // Estimate 3D pose using PnP
        val pose = estimate3DPose(centerX, centerY, width, height)

        // Calculate surface normal
        val surfaceNormal = calculateSurfaceNormal(raw.toothId, pose)

        // Generate landmarks for bracket placement
        val landmarks = generateToothLandmarks(pose, boundingBox)

        // Calculate optimal bracket position
        val optimalPosition = calculateOptimalBracketPosition(pose, surfaceNormal)

        return DetectedTooth(
            toothId = raw.toothId,
            confidence = raw.confidence,
            boundingBox = boundingBox,
            pose = pose,
            surfaceNormal = surfaceNormal,
            landmarks = landmarks,
            optimalBracketPosition = optimalPosition
        )
    }

    private fun estimate3DPose(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float
    ): Pose3D {
        // Average tooth dimensions (in mm)
        val avgToothWidth = 8.0f

        // Estimate depth using similar triangles
        val depthZ = (avgToothWidth * focalLengthX) / width

        // Convert 2D to 3D using camera intrinsics
        val worldX = ((centerX - principalPointX) * depthZ) / focalLengthX
        val worldY = ((centerY - principalPointY) * depthZ) / focalLengthY

        return Pose3D(
            position = Vector3D(worldX / 1000f, worldY / 1000f, depthZ / 1000f), // Convert to meters
            rotation = Quaternion3D(0f, 0f, 0f, 1f) // Neutral rotation
        )
    }

    private fun calculateSurfaceNormal(toothId: String, pose: Pose3D): Vector3 {
        val toothNumber = toothId.toIntOrNull() ?: return Vector3(0f, 0f, 1f)
        val quadrant = toothNumber / 10
        val position = toothNumber % 10

        // Calculate normal based on tooth position in dental arch
        val normalY = if (quadrant in 1..2) -1f else 1f // Upper vs lower

        // Lateral component based on position in arch
        val normalX = when (quadrant) {
            1, 4 -> -0.2f * (position - 4) // Right side
            2, 3 -> 0.2f * (position - 4)  // Left side
            else -> 0f
        }

        // Normalize vector
        val length = sqrt(normalX * normalX + normalY * normalY + 1f)
        return Vector3(normalX / length, normalY / length, 0.15f / length)
    }

    private fun generateToothLandmarks(pose: Pose3D, box: BoundingBox): List<Vector3> {
        val landmarks = mutableListOf<Vector3>()
        val centerX = pose.position.x
        val centerY = pose.position.y
        val centerZ = pose.position.z

        val offset = 0.002f // 2mm offset

        // Key landmarks for bracket placement
        landmarks.add(Vector3(centerX, centerY, centerZ)) // Center
        landmarks.add(Vector3(centerX, centerY + offset, centerZ)) // Incisal
        landmarks.add(Vector3(centerX, centerY - offset, centerZ)) // Gingival
        landmarks.add(Vector3(centerX - offset, centerY, centerZ)) // Mesial
        landmarks.add(Vector3(centerX + offset, centerY, centerZ)) // Distal

        return landmarks
    }

    private fun calculateOptimalBracketPosition(
        pose: Pose3D,
        normal: Vector3
    ): Vector3 {
        // Offset bracket 1mm from tooth surface along normal
        val offsetDistance = 0.001f // 1mm

        return Vector3(
            pose.position.x + normal.x * offsetDistance,
            pose.position.y + normal.y * offsetDistance,
            pose.position.z + normal.z * offsetDistance
        )
    }

    /**
     * Calculate position feedback for bracket placement
     */
    fun calculatePositionFeedback(
        currentPosition: Vector3,
        optimalPosition: Vector3
    ): PositionFeedback {
        val xOffset = (currentPosition.x - optimalPosition.x) * 1000 // Convert to mm
        val yOffset = (currentPosition.y - optimalPosition.y) * 1000
        val zOffset = (currentPosition.z - optimalPosition.z) * 1000

        val distance = sqrt(xOffset * xOffset + yOffset * yOffset + zOffset * zOffset)

        val quality = when {
            distance <= PERFECT_TOLERANCE -> QualityLevel.PERFECT
            distance <= GOOD_TOLERANCE -> QualityLevel.GOOD
            distance <= ACCEPTABLE_TOLERANCE -> QualityLevel.ACCEPTABLE
            else -> QualityLevel.NEEDS_ADJUSTMENT
        }

        val guidance = generateGuidance(xOffset, yOffset, zOffset, quality)

        return PositionFeedback(
            distance = distance,
            quality = quality,
            guidance = guidance,
            xOffset = xOffset,
            yOffset = yOffset,
            zOffset = zOffset
        )
    }

    private fun generateGuidance(
        xOffset: Float,
        yOffset: Float,
        zOffset: Float,
        quality: QualityLevel
    ): String {
        if (quality == QualityLevel.PERFECT) {
            return "✓ Perfect position!"
        }

        val suggestions = mutableListOf<String>()
        val threshold = 0.2f // 0.2mm threshold for guidance

        // Horizontal guidance
        if (abs(xOffset) > threshold) {
            val direction = if (xOffset > 0) "left" else "right"
            suggestions.add("Move ${String.format("%.1f", abs(xOffset))}mm $direction")
        }

        // Vertical guidance
        if (abs(yOffset) > threshold) {
            val direction = if (yOffset > 0) "down" else "up"
            suggestions.add("${String.format("%.1f", abs(yOffset))}mm $direction")
        }

        // Depth guidance
        if (abs(zOffset) > threshold) {
            val direction = if (zOffset > 0) "closer" else "away")
            suggestions.add("${String.format("%.1f", abs(zOffset))}mm $direction")
        }

        return when {
            suggestions.isEmpty() -> "Almost perfect!"
            suggestions.size == 1 -> suggestions[0]
            else -> suggestions.take(2).joinToString(", ")
        }
    }

    // Placeholder mode for testing without real model
    private fun generatePlaceholderDetections(bitmap: Bitmap): List<DetectedTooth> {
        Log.d(TAG, "Using placeholder detection mode")

        // Simulate detecting 3-6 teeth
        val numTeeth = (3..6).random()
        val detectedTeeth = mutableListOf<DetectedTooth>()

        val commonTeeth = listOf("11", "12", "13", "21", "22", "23") // Upper front 6

        for (i in 0 until numTeeth) {
            if (i >= commonTeeth.size) break

            val toothId = commonTeeth[i]
            val x = (bitmap.width * (0.3f + i * 0.1f))
            val y = bitmap.height * 0.5f
            val width = bitmap.width * 0.08f
            val height = bitmap.height * 0.12f

            val boundingBox = BoundingBox(x, y, width, height)
            val pose = estimate3DPose(x + width/2, y + height/2, width, height)
            val normal = calculateSurfaceNormal(toothId, pose)
            val landmarks = generateToothLandmarks(pose, boundingBox)
            val optimal = calculateOptimalBracketPosition(pose, normal)

            detectedTeeth.add(
                DetectedTooth(
                    toothId = toothId,
                    confidence = 0.75f + Math.random().toFloat() * 0.2f,
                    boundingBox = boundingBox,
                    pose = pose,
                    surfaceNormal = normal,
                    landmarks = landmarks,
                    optimalBracketPosition = optimal
                )
            )
        }

        return detectedTeeth
    }

    private fun convertImageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun cleanup() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            interpreter = null
            gpuDelegate = null
            isModelLoaded = false
            Log.d(TAG, "Detector cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }

    fun isReady(): Boolean = isModelLoaded
    fun isUsingPlaceholder(): Boolean = usePlaceholderMode
}
