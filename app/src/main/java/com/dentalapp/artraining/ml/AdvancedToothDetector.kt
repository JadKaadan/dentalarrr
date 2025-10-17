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
import java.util.Locale
import kotlin.math.*

/**
 * Advanced Tooth Detector using your trained YOLOv8 TFLite model
 * Now supports the real model with bracket manipulation features
 */
class AdvancedToothDetector(private val context: Context) {

    companion object {
        private const val TAG = "AdvancedToothDetector"

        // Model configuration - updated for your real model
        private const val MODEL_FILE = "models/tooth_detection_yolov8.tflite"
        private const val MODEL_INPUT_SIZE = 640 // YOLOv8 standard input

        // Detection thresholds - tuned for dental detection
        private const val CONFIDENCE_THRESHOLD = 0.50f // Lowered for better detection
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 100

        // Measurement tolerances (in mm)
        private const val PERFECT_TOLERANCE = 0.3f
        private const val GOOD_TOLERANCE = 0.5f
        private const val ACCEPTABLE_TOLERANCE = 1.0f

        // Bracket size limits (in mm)
        private const val MIN_BRACKET_SIZE = 2.0f
        private const val MAX_BRACKET_SIZE = 8.0f
        const val DEFAULT_BRACKET_SIZE = 4.0f
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
        val optimalBracketPosition: Vector3,
        val toothClass: Int = 0 // Class index from model
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

    data class BracketTransform(
        var position: Vector3,
        var rotation: Vector3, // Euler angles in degrees
        var scale: Float = DEFAULT_BRACKET_SIZE,
        var isVisible: Boolean = true
    ) {
        fun clampScale() {
            scale = scale.coerceIn(MIN_BRACKET_SIZE, MAX_BRACKET_SIZE)
        }
    }

    enum class QualityLevel {
        PERFECT,    // < 0.3mm
        GOOD,       // 0.3-0.5mm
        ACCEPTABLE, // 0.5-1.0mm
        NEEDS_ADJUSTMENT // > 1.0mm
    }

    // Class names - updated based on your model's output
    // You may need to adjust these based on your actual model classes
    private val classNames = listOf(
        "tooth", "incisor", "canine", "premolar", "molar",
        "upper_tooth", "lower_tooth", "crown", "root"
        // Add more classes based on your model
    )

    // TensorFlow Lite components
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputBuffer: ByteBuffer
    private var isModelLoaded = false
    private var usePlaceholderMode = false

    // Model output info
    private var modelOutputSize = 0
    private var numClasses = 0

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
            // Try to load your real model from different possible paths
            val possibleModelPaths = listOf(
                "models/tooth_detection_yolov8.tflite",
                "tooth_detection_yolov8.tflite",
                "models/tooth_model.tflite",
                "tooth_model.tflite"
            )

            var modelFile: MappedByteBuffer? = null
            var foundPath: String? = null

            for (path in possibleModelPaths) {
                try {
                    modelFile = loadModelFile(path)
                    foundPath = path
                    Log.d(TAG, "Model found at: $path")
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "Model not found at: $path")
                }
            }

            if (modelFile == null) {
                Log.w(TAG, "No ML model found, using placeholder mode")
                usePlaceholderMode = true
                isModelLoaded = true
                numClasses = 5 // Default for placeholder
                return
            }

            val compatList = CompatibilityList()
            val options = Interpreter.Options()

            // Use GPU if available
            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    Log.d(TAG, "GPU acceleration enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "GPU delegate failed, falling back to CPU", e)
                    options.setNumThreads(4)
                    options.setUseXNNPACK(true)
                }
            } else {
                options.setNumThreads(4)
                options.setUseXNNPACK(true)
                Log.d(TAG, "Using optimized CPU inference")
            }

            interpreter = Interpreter(modelFile, options)

            // Get model output info
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            Log.d(TAG, "Model output shape: ${outputShape.contentToString()}")

            // For YOLOv8, output shape is typically [1, num_detections, 4+num_classes]
            // or [1, 84, 8400] format
            if (outputShape.size >= 3) {
                when {
                    outputShape[2] > outputShape[1] -> {
                        // Format: [1, 4+classes, detections] - need to transpose
                        modelOutputSize = outputShape[1] * outputShape[2]
                        numClasses = outputShape[1] - 4
                    }
                    else -> {
                        // Format: [1, detections, 4+classes]
                        modelOutputSize = outputShape[1] * outputShape[2]
                        numClasses = outputShape[2] - 4
                    }
                }
            }

            Log.d(TAG, "Model loaded from $foundPath: output size=$modelOutputSize, classes=$numClasses")

            isModelLoaded = true
            usePlaceholderMode = false

        } catch (e: Exception) {
            Log.w(TAG, "Real model setup failed, using placeholder mode: ${e.message}")
            usePlaceholderMode = true
            isModelLoaded = true
            numClasses = 5 // Default for placeholder

            // Clean up any partially initialized components
            try {
                interpreter?.close()
                gpuDelegate?.close()
            } catch (cleanupException: Exception) {
                Log.w(TAG, "Cleanup exception during fallback", cleanupException)
            }
            interpreter = null
            gpuDelegate = null
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        return try {
            val fileDescriptor = context.assets.openFd(filename)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model file: $filename", e)
            throw e
        }
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
    fun detectTeeth(cameraImage: Image): List<DetectedTooth> {
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

        // Run inference
        val outputArray = Array(1) { FloatArray(modelOutputSize) }
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

        // Handle different YOLOv8 output formats
        val numDetections = when {
            numClasses > 0 -> output.size / (4 + numClasses)
            else -> min(1000, output.size / 84) // fallback
        }

        val stride = if (numClasses > 0) 4 + numClasses else 84

        Log.d(TAG, "Processing $numDetections detections with stride $stride")

        // Parse output
        for (i in 0 until numDetections) {
            val startIdx = i * stride
            if (startIdx + stride > output.size) break

            // YOLOv8 format: [x_center, y_center, width, height, class_confidences...]
            val x = output[startIdx] / MODEL_INPUT_SIZE // Normalize to [0,1]
            val y = output[startIdx + 1] / MODEL_INPUT_SIZE
            val w = output[startIdx + 2] / MODEL_INPUT_SIZE
            val h = output[startIdx + 3] / MODEL_INPUT_SIZE

            // Find class with max confidence
            var maxClassIdx = 0
            var maxConfidence = 0f

            val classStart = startIdx + 4
            val classEnd = min(classStart + numClasses, output.size)

            for (c in 0 until (classEnd - classStart)) {
                val confidence = output[classStart + c]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    maxClassIdx = c
                }
            }

            // Filter by confidence
            if (maxConfidence < CONFIDENCE_THRESHOLD) continue

            // Convert normalized coordinates to pixel coordinates
            val pixelX = x * imageWidth
            val pixelY = y * imageHeight
            val pixelW = w * imageWidth
            val pixelH = h * imageHeight

            // Generate tooth ID based on detection
            val toothId = generateToothId(pixelX, pixelY, imageWidth, imageHeight, maxClassIdx)

            detections.add(
                RawDetection(
                    toothId = toothId,
                    confidence = maxConfidence,
                    x = pixelX,
                    y = pixelY,
                    w = pixelW,
                    h = pixelH,
                    classIdx = maxClassIdx
                )
            )
        }

        Log.d(TAG, "Found ${detections.size} raw detections")

        // Apply NMS
        val nmsDetections = applyNMS(detections)
        Log.d(TAG, "After NMS: ${nmsDetections.size} detections")

        // Convert to full DetectedTooth objects
        return nmsDetections.map { raw ->
            convertToDetectedTooth(raw, imageWidth, imageHeight)
        }
    }

    private fun generateToothId(x: Float, y: Float, imageWidth: Int, imageHeight: Int, classIdx: Int): String {
        // Simple heuristic to assign tooth IDs based on position
        // You may want to train your model to output specific tooth IDs

        val isUpperHalf = y < imageHeight * 0.5f
        val leftThird = x < imageWidth * 0.33f
        val middleThird = x < imageWidth * 0.67f

        return when {
            isUpperHalf && leftThird -> listOf("11", "12", "13").random()
            isUpperHalf && middleThird -> listOf("11", "21").random()
            isUpperHalf -> listOf("21", "22", "23").random()
            leftThird -> listOf("41", "42", "43").random()
            middleThird -> listOf("31", "41").random()
            else -> listOf("31", "32", "33").random()
        }
    }

    private data class RawDetection(
        val toothId: String,
        val confidence: Float,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val classIdx: Int
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

        val boundingBox = BoundingBox(
            x = raw.x - raw.w / 2,
            y = raw.y - raw.h / 2,
            width = raw.w,
            height = raw.h
        )

        // Estimate 3D pose using camera projection
        val pose = estimate3DPose(raw.x, raw.y, raw.w, raw.h)

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
            optimalBracketPosition = optimalPosition,
            toothClass = raw.classIdx
        )
    }

    private fun estimate3DPose(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float
    ): Pose3D {
        // Estimate depth using realistic tooth size
        val avgToothWidth = 8.0f // mm
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
     * BRACKET MANIPULATION METHODS
     */

    fun createBracketTransform(position: Vector3): BracketTransform {
        return BracketTransform(
            position = position,
            rotation = Vector3(0f, 0f, 0f),
            scale = DEFAULT_BRACKET_SIZE
        )
    }

    fun rotateBracket(transform: BracketTransform, deltaX: Float, deltaY: Float, deltaZ: Float) {
        transform.rotation = Vector3(
            (transform.rotation.x + deltaX) % 360f,
            (transform.rotation.y + deltaY) % 360f,
            (transform.rotation.z + deltaZ) % 360f
        )
    }

    fun scaleBracket(transform: BracketTransform, scaleFactor: Float) {
        transform.scale *= scaleFactor
        transform.clampScale()
    }

    fun moveBracket(transform: BracketTransform, deltaX: Float, deltaY: Float, deltaZ: Float) {
        transform.position = Vector3(
            transform.position.x + deltaX,
            transform.position.y + deltaY,
            transform.position.z + deltaZ
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
            suggestions.add("Move ${String.format(Locale.US, "%.1f", abs(xOffset))}mm $direction")
        }

        // Vertical guidance
        if (abs(yOffset) > threshold) {
            val direction = if (yOffset > 0) "down" else "up"
            suggestions.add("${String.format(Locale.US, "%.1f", abs(yOffset))}mm $direction")
        }

        // Depth guidance
        if (abs(zOffset) > threshold) {
            val direction = if (zOffset > 0) "closer" else "away"
            suggestions.add("${String.format(Locale.US, "%.1f", abs(zOffset))}mm $direction")
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
                    optimalBracketPosition = optimal,
                    toothClass = i
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
