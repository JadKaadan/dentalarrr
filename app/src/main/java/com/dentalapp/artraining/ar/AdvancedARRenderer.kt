package com.dentalapp.artraining.ar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.dentalapp.artraining.ml.AdvancedToothDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class AdvancedARRenderer(
    private val context: Context,
    private val session: Session,
    private val bracketModel: OBJLoader.OBJModel?
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "AdvancedARRenderer"

        // Overlay colors (RGBA)
        private val COLOR_DETECTED = floatArrayOf(0.3f, 0.8f, 0.3f, 0.6f) // Green
        private val COLOR_PERFECT = floatArrayOf(0.2f, 1.0f, 0.2f, 0.8f) // Bright green
        private val COLOR_GOOD = floatArrayOf(0.3f, 0.7f, 0.9f, 0.6f) // Blue
        private val COLOR_NEEDS_ADJUSTMENT = floatArrayOf(1.0f, 0.7f, 0.2f, 0.6f) // Orange
        private val COLOR_ERROR = floatArrayOf(1.0f, 0.3f, 0.3f, 0.6f) // Red
    }

    // Rendering components
    private var backgroundRenderer: BackgroundRenderer? = null
    private var bracketRenderer: BracketRenderer? = null
    private var overlayRenderer: ToothOverlayRenderer? = null
    private var measurementRenderer: MeasurementRenderer? = null

    // Detected teeth and placed brackets
    private var detectedTeeth = listOf<AdvancedToothDetector.DetectedTooth>()
    private val placedBrackets = mutableMapOf<String, PlacedBracket>()

    // View matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Performance tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    data class PlacedBracket(
        val id: String,
        val anchor: Anchor,
        val toothId: String,
        val detectedTooth: AdvancedToothDetector.DetectedTooth?,
        var modelMatrix: FloatArray = FloatArray(16),
        var positionFeedback: AdvancedToothDetector.PositionFeedback? = null
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        try {
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread(context)

            bracketRenderer = BracketRenderer()
            bracketRenderer?.createOnGlThread(bracketModel)

            overlayRenderer = ToothOverlayRenderer()
            overlayRenderer?.createOnGlThread()

            measurementRenderer = MeasurementRenderer()
            measurementRenderer?.createOnGlThread()

            Log.d(TAG, "OpenGL renderers initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize renderers", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        try {
            val frame = session.update()
            val camera = frame.camera

            if (camera.trackingState != TrackingState.TRACKING) {
                return
            }

            // Update matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)

            // Draw camera background
            backgroundRenderer?.draw(frame)

            // Draw tooth overlays
            drawToothOverlays(frame)

            // Draw placed brackets
            drawPlacedBrackets(frame)

            // Draw measurements and guidance
            drawMeasurements(frame)

            // Update FPS
            updateFPS()

        } catch (e: Exception) {
            Log.e(TAG, "Render error: ${e.message}", e)
        }
    }

    private fun drawToothOverlays(frame: Frame) {
        detectedTeeth.forEach { tooth ->
            // Determine overlay color based on bracket placement
            val bracket = placedBrackets.values.find { it.toothId == tooth.toothId }
            val color = when {
                bracket != null -> {
                    // Color based on position feedback
                    when (bracket.positionFeedback?.quality) {
                        AdvancedToothDetector.QualityLevel.PERFECT -> COLOR_PERFECT
                        AdvancedToothDetector.QualityLevel.GOOD -> COLOR_GOOD
                        AdvancedToothDetector.QualityLevel.ACCEPTABLE -> COLOR_NEEDS_ADJUSTMENT
                        else -> COLOR_ERROR
                    }
                }
                else -> COLOR_DETECTED // Just detected, no bracket yet
            }

            // Draw overlay box around tooth
            overlayRenderer?.drawToothOverlay(
                tooth = tooth,
                color = color,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix
            )

            // Draw optimal bracket position indicator
            overlayRenderer?.drawOptimalPosition(
                position = tooth.optimalBracketPosition,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix
            )
        }
    }

    private fun drawPlacedBrackets(frame: Frame) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        placedBrackets.values.forEach { bracket ->
            if (bracket.anchor.trackingState == TrackingState.TRACKING) {
                // Update model matrix from anchor
                val anchorPose = bracket.anchor.pose
                anchorPose.toMatrix(bracket.modelMatrix, 0)

                // Determine bracket color based on position feedback
                val color = when (bracket.positionFeedback?.quality) {
                    AdvancedToothDetector.QualityLevel.PERFECT -> COLOR_PERFECT
                    AdvancedToothDetector.QualityLevel.GOOD -> COLOR_GOOD
                    AdvancedToothDetector.QualityLevel.ACCEPTABLE -> COLOR_NEEDS_ADJUSTMENT
                    else -> COLOR_ERROR
                }

                // Draw the bracket
                bracketRenderer?.draw(
                    modelMatrix = bracket.modelMatrix,
                    viewMatrix = viewMatrix,
                    projectionMatrix = projectionMatrix,
                    color = color
                )
            }
        }
    }

    private fun drawMeasurements(frame: Frame) {
        placedBrackets.values.forEach { bracket ->
            bracket.positionFeedback?.let { feedback ->
                bracket.detectedTooth?.let { tooth ->
                    // Draw distance measurement
                    measurementRenderer?.drawDistanceMeasurement(
                        from = tooth.optimalBracketPosition,
                        to = AdvancedToothDetector.Vector3(
                            bracket.modelMatrix[12],
                            bracket.modelMatrix[13],
                            bracket.modelMatrix[14]
                        ),
                        distance = feedback.distance,
                        quality = feedback.quality,
                        viewMatrix = viewMatrix,
                        projectionMatrix = projectionMatrix
                    )
                }
            }
        }
    }

    fun updateDetectedTeeth(teeth: List<AdvancedToothDetector.DetectedTooth>) {
        detectedTeeth = teeth

        // Update position feedback for placed brackets
        placedBrackets.values.forEach { bracket ->
            val detectedTooth = teeth.find { it.toothId == bracket.toothId }
            bracket.detectedTooth = detectedTooth

            detectedTooth?.let { tooth ->
                // Calculate current bracket position
                val currentPos = AdvancedToothDetector.Vector3(
                    bracket.modelMatrix[12],
                    bracket.modelMatrix[13],
                    bracket.modelMatrix[14]
                )

                // Calculate feedback
                bracket.positionFeedback = calculatePositionFeedback(
                    currentPos,
                    tooth.optimalBracketPosition
                )
            }
        }
    }

    private fun calculatePositionFeedback(
        current: AdvancedToothDetector.Vector3,
        optimal: AdvancedToothDetector.Vector3
    ): AdvancedToothDetector.PositionFeedback {
        val xOffset = (current.x - optimal.x) * 1000f
        val yOffset = (current.y - optimal.y) * 1000f
        val zOffset = (current.z - optimal.z) * 1000f

        val distance = kotlin.math.sqrt(
            xOffset * xOffset + yOffset * yOffset + zOffset * zOffset
        )

        val quality = when {
            distance <= 0.3f -> AdvancedToothDetector.QualityLevel.PERFECT
            distance <= 0.5f -> AdvancedToothDetector.QualityLevel.GOOD
            distance <= 1.0f -> AdvancedToothDetector.QualityLevel.ACCEPTABLE
            else -> AdvancedToothDetector.QualityLevel.NEEDS_ADJUSTMENT
        }

        val guidance = when (quality) {
            AdvancedToothDetector.QualityLevel.PERFECT -> "✓ Perfect!"
            AdvancedToothDetector.QualityLevel.GOOD -> "Good - ${String.format("%.2f", distance)}mm"
            AdvancedToothDetector.QualityLevel.ACCEPTABLE -> "Adjust - ${String.format("%.2f", distance)}mm"
            else -> "Move - ${String.format("%.2f", distance)}mm"
        }

        return AdvancedToothDetector.PositionFeedback(
            distance = distance,
            quality = quality,
            guidance = guidance,
            xOffset = xOffset,
            yOffset = yOffset,
            zOffset = zOffset
        )
    }

    fun addBracket(id: String, anchor: Anchor, toothId: String) {
        val detectedTooth = detectedTeeth.find { it.toothId == toothId }
        placedBrackets[id] = PlacedBracket(
            id = id,
            anchor = anchor,
            toothId = toothId,
            detectedTooth = detectedTooth
        )
        Log.d(TAG, "Bracket added: $id on tooth $toothId")
    }

    fun removeBracket(id: String) {
        placedBrackets.remove(id)?.anchor?.detach()
        Log.d(TAG, "Bracket removed: $id")
    }

    fun hitTestTooth(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int
    ): AdvancedToothDetector.DetectedTooth? {
        val normalizedX = x / screenWidth
        val normalizedY = y / screenHeight

        return detectedTeeth.find { tooth ->
            val box = tooth.boundingBox
            normalizedX >= box.x / screenWidth &&
                    normalizedX <= (box.x + box.width) / screenWidth &&
                    normalizedY >= box.y / screenHeight &&
                    normalizedY <= (box.y + box.height) / screenHeight
        }
    }

    fun getPlacedBrackets(): Map<String, PlacedBracket> = placedBrackets.toMap()

    private fun updateFPS() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTime >= 1000) {
            val fps = frameCount.toFloat() / ((currentTime - lastFpsTime) / 1000f)
            Log.v(TAG, "FPS: ${String.format("%.1f", fps)}")
            frameCount = 0
            lastFpsTime = currentTime
        }
    }

    // Background renderer for camera feed
    private class BackgroundRenderer {
        private var program = 0
        private var textureId = -1

        fun createOnGlThread(context: Context) {
            val vertexShader = """
                attribute vec4 a_Position;
                attribute vec2 a_TexCoord;
                varying vec2 v_TexCoord;
                void main() {
                    gl_Position = a_Position;
                    v_TexCoord = a_TexCoord;
                }
            """.trimIndent()

            val fragmentShader = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 v_TexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, v_TexCoord);
                }
            """.trimIndent()

            program = createProgram(vertexShader, fragmentShader)

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
        }

        fun draw(frame: Frame) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(program)
            // Background rendering implementation
        }
    }

    // Bracket renderer
    private class BracketRenderer {
        private var program = 0
        private var vertexBuffer: FloatBuffer? = null
        private var normalBuffer: FloatBuffer? = null
        private var indexBuffer: java.nio.IntBuffer? = null
        private var numIndices = 0

        fun createOnGlThread(model: OBJLoader.OBJModel?) {
            val vertexShader = """
                uniform mat4 u_MVP;
                attribute vec4 a_Position;
                attribute vec3 a_Normal;
                varying vec3 v_Normal;
                void main() {
                    v_Normal = a_Normal;
                    gl_Position = u_MVP * a_Position;
                }
            """.trimIndent()

            val fragmentShader = """
                precision mediump float;
                uniform vec4 u_Color;
                varying vec3 v_Normal;
                void main() {
                    vec3 lightDir = normalize(vec3(0.5, 1.0, 0.5));
                    float diffuse = max(dot(normalize(v_Normal), lightDir), 0.4);
                    gl_FragColor = vec4(u_Color.rgb * diffuse, u_Color.a);
                }
            """.trimIndent()

            program = createProgram(vertexShader, fragmentShader)

            model?.let {
                vertexBuffer = createFloatBuffer(it.vertices)
                normalBuffer = createFloatBuffer(it.normals)
                indexBuffer = createIntBuffer(it.indices)
                numIndices = it.indices.size
            }
        }

        fun draw(
            modelMatrix: FloatArray,
            viewMatrix: FloatArray,
            projectionMatrix: FloatArray,
            color: FloatArray
        ) {
            if (vertexBuffer == null) return

            GLES30.glUseProgram(program)

            // Calculate MVP
            val mvp = FloatArray(16)
            val mv = FloatArray(16)
            Matrix.multiplyMM(mv, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, mv, 0)

            val mvpHandle = GLES30.glGetUniformLocation(program, "u_MVP")
            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)

            val colorHandle = GLES30.glGetUniformLocation(program, "u_Color")
            GLES30.glUniform4fv(colorHandle, 1, color, 0)

            val posHandle = GLES30.glGetAttribLocation(program, "a_Position")
            GLES30.glEnableVertexAttribArray(posHandle)
            GLES30.glVertexAttribPointer(posHandle, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)

            val normHandle = GLES30.glGetAttribLocation(program, "a_Normal")
            GLES30.glEnableVertexAttribArray(normHandle)
            GLES30.glVertexAttribPointer(normHandle, 3, GLES30.GL_FLOAT, false, 0, normalBuffer)

            GLES30.glDrawElements(GLES30.GL_TRIANGLES, numIndices, GLES30.GL_UNSIGNED_INT, indexBuffer)

            GLES30.glDisableVertexAttribArray(posHandle)
            GLES30.glDisableVertexAttribArray(normHandle)
        }
    }

    // Tooth overlay renderer
    private class ToothOverlayRenderer {
        private var program = 0

        fun createOnGlThread() {
            val vertexShader = """
                uniform mat4 u_MVP;
                attribute vec4 a_Position;
                void main() {
                    gl_Position = u_MVP * a_Position;
                }
            """.trimIndent()

            val fragmentShader = """
                precision mediump float;
                uniform vec4 u_Color;
                void main() {
                    gl_FragColor = u_Color;
                }
            """.trimIndent()

            program = createProgram(vertexShader, fragmentShader)
        }

        fun drawToothOverlay(
            tooth: AdvancedToothDetector.DetectedTooth,
            color: FloatArray,
            viewMatrix: FloatArray,
            projectionMatrix: FloatArray
        ) {
            // Draw bounding box around detected tooth
            val box = tooth.boundingBox
            // Implementation for drawing 3D overlay box
        }

        fun drawOptimalPosition(
            position: AdvancedToothDetector.Vector3,
            viewMatrix: FloatArray,
            projectionMatrix: FloatArray
        ) {
            // Draw a small marker at optimal bracket position
        }
    }

    // Measurement renderer
    private class MeasurementRenderer {
        fun createOnGlThread() {
            // Initialize measurement rendering
        }

        fun drawDistanceMeasurement(
            from: AdvancedToothDetector.Vector3,
            to: AdvancedToothDetector.Vector3,
            distance: Float,
            quality: AdvancedToothDetector.QualityLevel,
            viewMatrix: FloatArray,
            projectionMatrix: FloatArray
        ) {
            // Draw line from optimal position to current position
            // Show distance text
        }
    }
}

// Utility functions
private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vertexShader)
    GLES30.glAttachShader(program, fragmentShader)
    GLES30.glLinkProgram(program)

    return program
}

private fun loadShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    return shader
}

private fun createFloatBuffer(data: FloatArray): FloatBuffer {
    return ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(data)
        .position(0) as FloatBuffer
}

private fun createIntBuffer(data: IntArray): java.nio.IntBuffer {
    return ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
        .put(data)
        .position(0) as java.nio.IntBuffer
}
