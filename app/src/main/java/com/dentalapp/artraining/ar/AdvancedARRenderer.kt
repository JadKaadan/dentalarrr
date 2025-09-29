package com.dentalapp.artraining.ar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
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
    }

    // Rendering components
    private var backgroundRenderer: BackgroundRenderer? = null
    private var bracketShader: BracketShader? = null
    private var toothOverlayRenderer: ToothOverlayRenderer? = null

    // Detected teeth
    private var detectedTeeth = listOf<AdvancedToothDetector.DetectedTooth>()

    // Placed brackets
    private val placedBrackets = mutableMapOf<String, PlacedBracket>()

    // View matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    data class PlacedBracket(
        val id: String,
        val anchor: Anchor,
        val toothId: String,
        var modelMatrix: FloatArray = FloatArray(16)
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Initialize renderers
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer?.createOnGlThread(context)

        bracketShader = BracketShader()
        bracketShader?.createOnGlThread(bracketModel)

        toothOverlayRenderer = ToothOverlayRenderer()
        toothOverlayRenderer?.createOnGlThread()

        Log.d(TAG, "OpenGL surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        try {
            val frame = session.update()
            val camera = frame.camera

            // Check if camera is tracking
            if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                return
            }

            // Draw camera background
            backgroundRenderer?.draw(frame)

            // Get view and projection matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

            // Draw tooth overlays
            drawToothOverlays(frame)

            // Draw placed brackets
            drawBrackets(frame)

        } catch (e: Exception) {
            Log.e(TAG, "Render error", e)
        }
    }

    private fun drawToothOverlays(frame: Frame) {
        detectedTeeth.forEach { tooth ->
            toothOverlayRenderer?.drawToothOverlay(
                tooth,
                viewMatrix,
                projectionMatrix
            )
        }
    }

    private fun drawBrackets(frame: Frame) {
        // Enable depth testing
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        placedBrackets.values.forEach { bracket ->
            if (bracket.anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                // Get anchor pose
                val anchorPose = bracket.anchor.pose
                anchorPose.toMatrix(bracket.modelMatrix, 0)

                // Draw bracket
                bracketShader?.draw(
                    bracket.modelMatrix,
                    viewMatrix,
                    projectionMatrix
                )
            }
        }
    }

    fun updateDetectedTeeth(teeth: List<AdvancedToothDetector.DetectedTooth>) {
        detectedTeeth = teeth
    }

    fun addBracket(id: String, anchor: Anchor, toothId: String) {
        placedBrackets[id] = PlacedBracket(id, anchor, toothId)
    }

    fun removeBracket(id: String) {
        placedBrackets.remove(id)?.anchor?.detach()
    }

    fun hitTestTooth(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int
    ): AdvancedToothDetector.DetectedTooth? {
        // Convert screen coordinates to normalized coordinates
        val normalizedX = x / screenWidth
        val normalizedY = y / screenHeight

        // Find tooth at tap location
        return detectedTeeth.find { tooth ->
            val box = tooth.boundingBox
            normalizedX >= box.x / screenWidth &&
                    normalizedX <= (box.x + box.width) / screenWidth &&
                    normalizedY >= box.y / screenHeight &&
                    normalizedY <= (box.y + box.height) / screenHeight
        }
    }

    // Background renderer for camera feed
    private class BackgroundRenderer {
        private var quadProgram = 0
        private var quadPositionParam = 0
        private var quadTexCoordParam = 0
        private var textureId = -1

        private val quadCoords = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            -1.0f, +1.0f, 0.0f,
            +1.0f, -1.0f, 0.0f,
            +1.0f, +1.0f, 0.0f
        )

        private val quadTexCoords = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )

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

            quadProgram = createProgram(vertexShader, fragmentShader)
            quadPositionParam = GLES30.glGetAttribLocation(quadProgram, "a_Position")
            quadTexCoordParam = GLES30.glGetAttribLocation(quadProgram, "a_TexCoord")

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
        }

        fun draw(frame: Frame) {
            if (frame.hasDisplayGeometryChanged()) {
                frame.transformCoordinates2d(
                    com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    createFloatBuffer(quadTexCoords),
                    com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                    createFloatBuffer(quadTexCoords)
                )
            }

            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)

            GLES30.glUseProgram(quadProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            frame.transformDisplayUvCoords(
                createFloatBuffer(quadTexCoords),
                createFloatBuffer(quadTexCoords)
            )

            // Draw quad
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

            GLES30.glVertexAttribPointer(
                quadPositionParam, 3, GLES30.GL_FLOAT, false, 0,
                createFloatBuffer(quadCoords)
            )
            GLES30.glVertexAttribPointer(
                quadTexCoordParam, 2, GLES30.GL_FLOAT, false, 0,
                createFloatBuffer(quadTexCoords)
            )

            GLES30.glEnableVertexAttribArray(quadPositionParam)
            GLES30.glEnableVertexAttribArray(quadTexCoordParam)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(quadPositionParam)
            GLES30.glDisableVertexAttribArray(quadTexCoordParam)
        }
    }

    // Bracket shader for 3D rendering
    private class BracketShader {
        private var program = 0
        private var positionParam = 0
        private var normalParam = 0
        private var modelViewProjectionParam = 0
        private var lightPosParam = 0

        private var vertexBuffer: FloatBuffer? = null
        private var normalBuffer: FloatBuffer? = null
        private var indexBuffer: java.nio.IntBuffer? = null
        private var numIndices = 0

        fun createOnGlThread(model: OBJLoader.OBJModel?) {
            val vertexShader = """
                uniform mat4 u_ModelViewProjection;
                uniform vec3 u_LightPos;
                attribute vec4 a_Position;
                attribute vec3 a_Normal;
                varying vec3 v_ViewPosition;
                varying vec3 v_ViewNormal;
                void main() {
                    v_ViewPosition = (u_ModelViewProjection * a_Position).xyz;
                    v_ViewNormal = normalize(a_Normal);
                    gl_Position = u_ModelViewProjection * a_Position;
                }
            """.trimIndent()

            val fragmentShader = """
                precision mediump float;
                varying vec3 v_ViewPosition;
                varying vec3 v_ViewNormal;
                void main() {
                    vec3 lightDir = normalize(vec3(0.0, 1.0, 1.0));
                    float diffuse = max(dot(v_ViewNormal, lightDir), 0.3);
                    vec3 color = vec3(0.8, 0.8, 0.9) * diffuse;
                    gl_FragColor = vec4(color, 1.0);
                }
            """.trimIndent()

            program = createProgram(vertexShader, fragmentShader)
            positionParam = GLES30.glGetAttribLocation(program, "a_Position")
            normalParam = GLES30.glGetAttribLocation(program, "a_Normal")
            modelViewProjectionParam = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
            lightPosParam = GLES30.glGetUniformLocation(program, "u_LightPos")

            model?.let {
                vertexBuffer = createFloatBuffer(it.vertices)
                normalBuffer = createFloatBuffer(it.normals)
                indexBuffer = createIntBuffer(it.indices)
                numIndices = it.indices.size
            }
        }

        fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
            if (vertexBuffer == null) return

            GLES30.glUseProgram(program)

            // Calculate MVP matrix
            val mvpMatrix = FloatArray(16)
            val mvMatrix = FloatArray(16)
            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

            GLES30.glUniformMatrix4fv(modelViewProjectionParam, 1, false, mvpMatrix, 0)
            GLES30.glUniform3f(lightPosParam, 0.0f, 2.0f, 0.0f)

            // Draw model
            GLES30.glEnableVertexAttribArray(positionParam)
            GLES30.glVertexAttribPointer(
                positionParam, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer
            )

            GLES30.glEnableVertexAttribArray(normalParam)
            GLES30.glVertexAttribPointer(
                normalParam, 3, GLES30.GL_FLOAT, false, 0, normalBuffer
            )

            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                numIndices,
                GLES30.GL_UNSIGNED_INT,
                indexBuffer
            )

            GLES30.glDisableVertexAttribArray(positionParam)
            GLES30.glDisableVertexAttribArray(normalParam)
        }
    }

    // Tooth overlay renderer
    private class ToothOverlayRenderer {
        fun createOnGlThread() {
            // Initialize overlay rendering
        }

        fun drawToothOverlay(
            tooth: AdvancedToothDetector.DetectedTooth,
            viewMatrix: FloatArray,
            projectionMatrix: FloatArray
        ) {
            // Draw bounding box overlay on detected tooth
            // This would render a wireframe box around detected teeth
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
