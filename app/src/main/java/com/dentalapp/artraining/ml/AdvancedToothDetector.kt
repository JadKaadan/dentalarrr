package com.dentalapp.artraining.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.dentalapp.artraining.data.Pose3D
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AdvancedToothDetector(private val context: Context) {

    companion object {
        private const val TAG = "AdvancedToothDetector"
        private const val MODEL_FILE = "tooth_detection_full.tflite"
        private const val INPUT_SIZE = 416 // YOLO-style input
        private const val NUM_CLASSES = 32 // All permanent teeth
        private const val CONFIDENCE_THRESHOLD = 0.65f
        private const val IOU_THRESHOLD = 0.4f
    }

    data class Vector3(val x: Float, val y: Float, val z: Float)

    data class DetectedTooth(
        val toothId: String,
        val confidence: Float,
        val boundingBox: BoundingBox,
        val pose: Pose3D,
        val surfaceNormal: Vector3,
        val landmarks: List<Vector3> // Key points on tooth surface
    )

    data class BoundingBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputBuffer: ByteBuffer

    // Tooth numbering (FDI notation)
    private val toothLabels = listOf(
        // Upper right: 11-18
        "11", "12", "13", "14", "15", "16", "17", "18",
        // Upper left: 21-28
        "21", "22", "23", "24", "25", "26", "27", "28",
        // Lower left: 31-38
        "31", "32", "33", "34", "35", "36", "37", "38",
        // Lower right: 41-48
        "41", "42", "43", "44", "45", "46", "47", "48"
    )

    init {
        // Initialize input buffer
        inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        inputBuffer.order(ByteOrder.nativeOrder())

        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            val compatList = CompatibilityList()
            val options = Interpreter.Options()

            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate enabled for tooth detection")
            } else {
                options.setNumThreads(4)
                Log.d(TAG, "Using CPU with 4 threads")
            }

            interpreter = Interpreter(loadModelFile(), options)
            Log.d(TAG, "Tooth detector initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    suspend fun detectTeeth(cameraImage: Image): List<DetectedTooth> {
        try {
            // Convert camera image to bitmap
            val bitmap = convertImageToBitmap(cameraImage)

            // Preprocess image
            preprocessImage(bitmap)

            // Run inference
            val outputArray = runInference()

            // Post-process results
            return postProcessResults(outputArray, bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            return emptyList()
        }
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

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun preprocessImage(bitmap: Bitmap) {
        inputBuffer.rewind()

        // Resize bitmap to input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Convert to float array normalized to [0, 1]
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resizedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
    }

    private fun runInference(): Array<FloatArray> {
        // Output format: [num_detections, (x, y, w, h, confidence, class_probs...)]
        val outputSize = 100 // Max detections
        val outputArray = Array(outputSize) { FloatArray(5 + NUM_CLASSES) }

        interpreter?.run(inputBuffer, outputArray)

        return outputArray
    }

    private fun postProcessResults(
        outputArray: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedTooth> {
        val detections = mutableListOf<DetectedTooth>()

        for (detection in outputArray) {
            val confidence = detection[4]
            if (confidence < CONFIDENCE_THRESHOLD) continue

            // Get class with highest probability
            var maxClass = 0
            var maxProb = 0f
            for (i in 5 until detection.size) {
                if (detection[i] > maxProb) {
                    maxProb = detection[i]
                    maxClass = i - 5
                }
            }

            if (maxClass >= toothLabels.size) continue

            val toothId = toothLabels[maxClass]

            // Bounding box (normalized coordinates)
            val centerX = detection[0]
            val centerY = detection[1]
            val width = detection[2]
            val height = detection[3]

            val boundingBox = BoundingBox(
                x = (centerX - width / 2) * imageWidth,
                y = (centerY - height / 2) * imageHeight,
                width = width * imageWidth,
                height = height * imageHeight
            )

            // Estimate 3D pose from 2D detection
            val pose = estimate3DPose(centerX, centerY, width, height, imageWidth, imageHeight)

            // Calculate surface normal
            val surfaceNormal = calculateSurfaceNormal(toothId, pose)

            // Generate landmarks
            val landmarks = generateToothLandmarks(pose, boundingBox)

            detections.add(
                DetectedTooth(
                    toothId = toothId,
                    confidence = confidence * maxProb,
                    boundingBox = boundingBox,
                    pose = pose,
                    surfaceNormal = surfaceNormal,
                    landmarks = landmarks
                )
            )
        }

        // Apply NMS (Non-Maximum Suppression)
        return applyNMS(detections)
    }

    private fun estimate3DPose(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Pose3D {
        // Convert 2D screen coordinates to 3D world coordinates
        // This is a simplified estimation - in production, use camera intrinsics

        // Estimate depth based on detected size
        val avgToothWidth = 8.0f // mm
        val focalLength = 500f // pixels (approximate)
        val depthZ = (avgToothWidth * focalLength) / (width * imageWidth)

        // Convert to world coordinates
        val worldX = ((centerX - 0.5f) * imageWidth / focalLength) * depthZ
        val worldY = ((centerY - 0.5f) * imageHeight / focalLength) * depthZ

        return Pose3D(
            position = com.dentalapp.artraining.data.Vector3D(worldX, worldY, depthZ),
            rotation = com.dentalapp.artraining.data.Quaternion3D(0f, 0f, 0f, 1f)
        )
    }

    private fun calculateSurfaceNormal(toothId: String, pose: Pose3D): Vector3 {
        // Calculate tooth surface normal based on tooth position in arch

        val toothNumber = toothId.toIntOrNull() ?: 11
        val quadrant = toothNumber / 10
        val position = toothNumber % 10

        // Simplified normal calculation
        // Upper teeth point downward, lower teeth point upward
        // Adjust based on position in arch

        val normalY = if (quadrant in 1..2) -1f else 1f

        // Adjust X based on tooth position (more lateral for molars)
        val normalX = when (quadrant) {
            1, 4 -> -0.3f * (position - 4) // Right side
            2, 3 -> 0.3f * (position - 4)  // Left side
            else -> 0f
        }

        // Normalize
        val length = kotlin.math.sqrt(normalX * normalX + normalY * normalY + 1f)
        return Vector3(normalX / length, normalY / length, 0.2f / length)
    }

    private fun generateToothLandmarks(pose: Pose3D, boundingBox: BoundingBox): List<Vector3> {
        // Generate key points on tooth surface for bracket placement
        val landmarks = mutableListOf<Vector3>()

        val centerX = pose.position.x
        val centerY = pose.position.y
        val centerZ = pose.position.z

        // Generate 5 landmarks: center, top, bottom, left, right
        landmarks.add(Vector3(centerX, centerY, centerZ)) // Center

        val offset = 0.002f // 2mm offset
        landmarks.add(Vector3(centerX, centerY + offset, centerZ)) // Top
        landmarks.add(Vector3(centerX, centerY - offset, centerZ)) // Bottom
        landmarks.add(Vector3(centerX - offset, centerY, centerZ)) // Left
        landmarks.add(Vector3(centerX + offset, centerY, centerZ)) // Right

        return landmarks
    }

    private fun applyNMS(detections: List<DetectedTooth>): List<DetectedTooth> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<DetectedTooth>()

        for (detection in sortedDetections) {
            var shouldAdd = true

            for (selectedDetection in selectedDetections) {
                if (calculateIOU(detection.boundingBox, selectedDetection.boundingBox) > IOU_THRESHOLD) {
                    shouldAdd = false
                    break
                }
            }

            if (shouldAdd) {
                selectedDetections.add(detection)
            }
        }

        return selectedDetections
    }

    private fun calculateIOU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x, box2.x)
        val y1 = maxOf(box1.y, box2.y)
        val x2 = minOf(box1.x + box1.width, box2.x + box2.width)
        val y2 = minOf(box1.y + box1.height, box2.y + box2.height)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = box1.width * box1.height
        val area2 = box2.width * box2.height
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }

    fun cleanup() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            interpreter = null
            gpuDelegate = null
            Log.d(TAG, "Tooth detector cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
