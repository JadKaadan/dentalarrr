package com.dentalapp.artraining.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import com.dentalapp.artraining.data.BoundingBox
import com.dentalapp.artraining.data.Pose3D
import com.dentalapp.artraining.data.Quaternion3D
import com.dentalapp.artraining.data.ToothDetectionResult
import com.dentalapp.artraining.data.Vector3D
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

class ToothDetector(private val context: Context) {

    companion object {
        private const val TAG = "ToothDetector"
        private const val MODEL_SEGMENTATION = "tooth_segmentation_v1.tflite"
        private const val MODEL_KEYPOINTS = "tooth_keypoints_v1.tflite"
        private const val INPUT_SIZE = 256
        private const val CONFIDENCE_THRESHOLD = 0.6f

        // Tooth IDs we support in MVP (upper front 6 teeth)
        private val SUPPORTED_TEETH = listOf("11", "12", "13", "21", "22", "23")
    }

    // TensorFlow Lite interpreters
    private var segmentationInterpreter: Interpreter? = null
    private var keypointsInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Image preprocessing
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    // Output buffers
    private var segmentationOutput: Array<Array<Array<FloatArray>>>? = null
    private var keypointsOutput: Array<Array<FloatArray>>? = null

    init {
        setupTensorFlowLite()
    }

    private fun setupTensorFlowLite() {
        try {
            // Check GPU compatibility
            val compatList = CompatibilityList()
            val options = Interpreter.Options()

            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate enabled")
            } else {
                options.setNumThreads(4)
                Log.d(TAG, "Using CPU with 4 threads")
            }

            // Load models
            segmentationInterpreter = Interpreter(loadModelFile(MODEL_SEGMENTATION), options)
            keypointsInterpreter = Interpreter(loadModelFile(MODEL_KEYPOINTS), options)

            // Initialize output arrays
            initializeOutputBuffers()

            Log.d(TAG, "TensorFlow Lite models loaded successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TensorFlow Lite", e)
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun initializeOutputBuffers() {
        // Segmentation output: [1, 256, 256, num_classes]
        // For MVP: background(0) + 6 teeth classes
        segmentationOutput = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(7) } } }

        // Keypoints output: [1, num_teeth, num_keypoints * 2]
        // For MVP: 6 teeth * 4 keypoints * 2 coordinates = [1, 6, 8]
        keypointsOutput = Array(1) { Array(6) { FloatArray(8) } }
    }

    suspend fun detectTeeth(cameraImage: Image): List<ToothDetectionResult> {
        try {
            // Convert camera image to bitmap
            val bitmap = imageToRgbBitmap(cameraImage)

            // Preprocess image
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)

            // Run segmentation model
            val segmentationMasks = runSegmentation(processedImage)

            // Run keypoints model
            val keypoints = runKeypoints(processedImage)

            // Combine results to create tooth detections
            return processDetectionResults(segmentationMasks, keypoints, bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Log.e(TAG, "Error during tooth detection", e)
            return emptyList()
        }
    }

    private fun runSegmentation(image: TensorImage): Array<Array<Array<FloatArray>>> {
        val inputBuffer = image.buffer

        segmentationInterpreter?.run(inputBuffer, segmentationOutput)

        return segmentationOutput ?: throw RuntimeException("Segmentation failed")
    }

    private fun runKeypoints(image: TensorImage): Array<Array<FloatArray>> {
        val inputBuffer = image.buffer

        keypointsInterpreter?.run(inputBuffer, keypointsOutput)

        return keypointsOutput ?: throw RuntimeException("Keypoints detection failed")
    }

    private fun processDetectionResults(
        masks: Array<Array<Array<FloatArray>>>,
        keypoints: Array<Array<FloatArray>>,
        originalWidth: Int,
        originalHeight: Int
    ): List<ToothDetectionResult> {

        val results = mutableListOf<ToothDetectionResult>()

        SUPPORTED_TEETH.forEachIndexed { index, toothId ->
            val toothClassIndex = index + 1 // Skip background class

            // Find tooth in segmentation mask
            val toothMask = extractToothMask(masks[0], toothClassIndex)
            val confidence = calculateMaskConfidence(toothMask)

            if (confidence > CONFIDENCE_THRESHOLD) {
                // Extract keypoints for this tooth
                val toothKeypoints = keypoints[0][index]

                // Convert to 3D pose using PnP solver
                val pose = solvePoseFromKeypoints(toothKeypoints, originalWidth, originalHeight)

                // Calculate bounding box
                val boundingBox = calculateBoundingBox(toothMask, originalWidth, originalHeight)

                results.add(
                    ToothDetectionResult(
                        toothId = toothId,
                        confidence = confidence,
                        pose = pose,
                        boundingBox = boundingBox
                    )
                )
            }
        }

        return results
    }

    private fun extractToothMask(
        segmentation: Array<Array<FloatArray>>,
        classIndex: Int
    ): Array<Array<Float>> {

        val mask = Array(INPUT_SIZE) { Array(INPUT_SIZE) { 0f } }

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                mask[y][x] = segmentation[y][x][classIndex]
            }
        }

        return mask
    }

    private fun calculateMaskConfidence(mask: Array<Array<Float>>): Float {
        var totalConfidence = 0f
        var pixelCount = 0

        for (y in mask.indices) {
            for (x in mask[y].indices) {
                if (mask[y][x] > 0.5f) {
                    totalConfidence += mask[y][x]
                    pixelCount++
                }
            }
        }

        return if (pixelCount > 0) totalConfidence / pixelCount else 0f
    }

    private fun solvePoseFromKeypoints(
        keypoints: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): Pose3D {

        // Extract 2D keypoints (4 points * 2 coordinates)
        val imagePoints = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until 4) {
            val x = keypoints[i * 2] * imageWidth / INPUT_SIZE
            val y = keypoints[i * 2 + 1] * imageHeight / INPUT_SIZE
            imagePoints.add(Pair(x, y))
        }

        // 3D model points for a typical bracket (in mm)
        val modelPoints = listOf(
            Vector3D(-2f, -1f, 0f),  // Left edge
            Vector3D(2f, -1f, 0f),   // Right edge
            Vector3D(0f, 1f, 0f),    // Top center
            Vector3D(0f, -2f, 0f)    // Bottom center
        )

        // Simplified PnP solver (for MVP - would use proper cv2.solvePnP in production)
        val pose = solvePnPSimplified(imagePoints, modelPoints)

        return pose
    }

    private fun solvePnPSimplified(
        imagePoints: List<Pair<Float, Float>>,
        modelPoints: List<Vector3D>
    ): Pose3D {

        // Simplified pose estimation for MVP
        // In production, would use proper PnP solver with camera intrinsics

        // Calculate centroid of image points
        val centroidX = imagePoints.map { it.first }.average().toFloat()
        val centroidY = imagePoints.map { it.second }.average().toFloat()

        // Estimate depth based on detected bracket size
        val detectedWidth = imagePoints.maxOf { it.first } - imagePoints.minOf { it.first }
        val estimatedDepth = (4f * 500f) / detectedWidth // Simple depth estimation

        // Estimate rotation from keypoint arrangement
        val topPoint = imagePoints.minBy { it.second }
        val bottomPoint = imagePoints.maxBy { it.second }
        val angle = atan2(topPoint.first - bottomPoint.first, topPoint.second - bottomPoint.second)

        return Pose3D(
            position = Vector3D(
                x = (centroidX - 128f) * 0.1f, // Convert to world coordinates
                y = (centroidY - 128f) * 0.1f,
                z = estimatedDepth
            ),
            rotation = Quaternion3D(
                x = 0f,
                y = sin(angle / 2),
                z = 0f,
                w = cos(angle / 2)
            )
        )
    }

    private fun calculateBoundingBox(
        mask: Array<Array<Float>>,
        originalWidth: Int,
        originalHeight: Int
    ): BoundingBox {

        var minX = INPUT_SIZE
        var maxX = 0
        var minY = INPUT_SIZE
        var maxY = 0

        // Find bounding box of mask
        for (y in mask.indices) {
            for (x in mask[y].indices) {
                if (mask[y][x] > 0.5f) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }

        // Convert to original image coordinates
        val scaleX = originalWidth.toFloat() / INPUT_SIZE
        val scaleY = originalHeight.toFloat() / INPUT_SIZE

        return BoundingBox(
            x = minX * scaleX,
            y = minY * scaleY,
            width = (maxX - minX) * scaleX,
            height = (maxY - minY) * scaleY
        )
    }

    private fun imageToRgbBitmap(image: Image): Bitmap {
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
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Rotate bitmap if needed (camera orientation)
        val matrix = Matrix()
        matrix.postRotate(90f)

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun cleanup() {
        try {
            segmentationInterpreter?.close()
            keypointsInterpreter?.close()
            gpuDelegate?.close()

            segmentationInterpreter = null
            keypointsInterpreter = null
            gpuDelegate = null

            Log.d(TAG, "ToothDetector cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}