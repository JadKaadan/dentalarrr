package com.dentalapp.artraining

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dentalapp.artraining.ar.AdvancedARRenderer
import com.dentalapp.artraining.ar.OBJLoader
import com.dentalapp.artraining.data.BracketPlacement
import com.dentalapp.artraining.data.PatientSession
import com.dentalapp.artraining.ml.AdvancedToothDetector
import com.dentalapp.artraining.utils.QRCodeGenerator
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import android.content.Context
import android.media.Image
import kotlin.math.*

class AdvancedARCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AdvancedARCamera"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val MIN_OPENGL_VERSION = 3.0

        // Touch gesture constants
        private const val TOUCH_TOLERANCE = 50f
        private const val ROTATION_SCALE = 0.5f
        private const val SCALE_SENSITIVITY = 0.01f
        private const val MOVE_SENSITIVITY = 0.001f
    }

    // AR Core
    private var arSession: Session? = null
    private var arRenderer: AdvancedARRenderer? = null
    private var shouldConfigureSession = false

    // ML Detection
    private var toothDetector: AdvancedToothDetector? = null

    // 3D Model
    private var bracketModel: OBJLoader.OBJModel? = null

    // UI Components
    private lateinit var surfaceView: android.opengl.GLSurfaceView
    private lateinit var btnStartCamera: Button
    private lateinit var btnSaveSession: Button
    private lateinit var btnUndo: Button
    private lateinit var btnManipulateMode: Button
    private lateinit var tvInstructions: TextView
    private lateinit var tvToothInfo: TextView
    private lateinit var tvPlacementCount: TextView
    private lateinit var tvManipulationInfo: TextView
    private lateinit var layoutControls: LinearLayout
    private lateinit var layoutManipulation: LinearLayout
    private lateinit var progressBar: ProgressBar

    // Manipulation Controls (optional)
    private var seekBarScale: SeekBar? = null
    private var btnResetTransform: Button? = null
    private var tvScaleValue: TextView? = null

    // Session Data
    private var patientName: String = ""
    private val bracketPlacements = mutableListOf<BracketPlacement>()
    private var isCameraActive = false
    private var isDetecting = false

    // Touch handling and manipulation
    private var lastTapTime = 0L
    private val TAP_THRESHOLD = 300L
    private var isManipulationMode = false
    private var selectedBracketId: String? = null
    private var bracketTransforms = mutableMapOf<String, AdvancedToothDetector.BracketTransform>()

    // Gesture detection
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialDistance = 0f
    private var isScaling = false
    private var isRotating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_advanced_arcamera)
            Log.d(TAG, "Layout set successfully")

            if (!checkOpenGLVersion()) {
                showError("OpenGL ES 3.0 is required but not supported")
                finish()
                return
            }

            initializeUI()
            setupManipulationControls()
            loadBracketModel()
            initializeMLDetector()

            if (!checkCameraPermission()) {
                requestCameraPermission()
            }

            Log.d(TAG, "Activity created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            showError("Failed to initialize app: ${e.message}")
            finish()
        }
    }

    private fun checkOpenGLVersion(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val configInfo = activityManager.deviceConfigurationInfo
            (configInfo.reqGlEsVersion >= 0x30000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check OpenGL version", e)
            false
        }
    }

    private fun initializeUI() {
        try {
            // Find required UI elements
            surfaceView = findViewById(R.id.ar_surface_view)
            btnStartCamera = findViewById(R.id.btn_start_camera)
            tvInstructions = findViewById(R.id.tv_instructions)
            progressBar = findViewById(R.id.loading_indicator)

            Log.d(TAG, "Core UI elements found")

            // Find optional UI elements
            btnSaveSession = findViewByIdSafe(R.id.btn_save_session) ?: createDummyButton()
            btnUndo = findViewByIdSafe(R.id.btn_undo) ?: createDummyButton()
            btnManipulateMode = findViewByIdSafe(R.id.btn_manipulate_mode) ?: createDummyButton()
            tvToothInfo = findViewByIdSafe(R.id.tv_tooth_info) ?: createDummyTextView()
            tvPlacementCount = findViewByIdSafe(R.id.tv_placement_count) ?: createDummyTextView()
            tvManipulationInfo = findViewByIdSafe(R.id.tv_manipulation_info) ?: createDummyTextView()
            layoutControls = findViewByIdSafe(R.id.layout_controls) ?: createDummyLayout()
            layoutManipulation = findViewByIdSafe(R.id.layout_manipulation) ?: createDummyLayout()

            btnStartCamera.setOnClickListener { startCameraSession() }
            btnSaveSession.setOnClickListener { savePatientSession() }
            btnUndo.setOnClickListener { undoLastPlacement() }
            btnManipulateMode.setOnClickListener { toggleManipulationMode() }

            // Initially hide controls
            layoutControls.visibility = View.GONE
            layoutManipulation.visibility = View.GONE
            btnSaveSession.isEnabled = false
            btnUndo.isEnabled = false
            btnManipulateMode.isEnabled = false
            progressBar.visibility = View.GONE

            // Setup touch listener with gesture detection
            surfaceView.setOnTouchListener { _, event ->
                if (isCameraActive) {
                    handleTouch(event)
                }
                true
            }

            Log.d(TAG, "UI initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in initializeUI", e)
            throw e
        }
    }

    private inline fun <reified T : View> findViewByIdSafe(id: Int): T? {
        return try {
            findViewById<T>(id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not find view with id $id", e)
            null
        }
    }

    private fun createDummyButton(): Button {
        return Button(this).apply {
            visibility = View.GONE
            setOnClickListener { /* no-op */ }
        }
    }

    private fun createDummyTextView(): TextView {
        return TextView(this).apply {
            visibility = View.GONE
        }
    }

    private fun createDummyLayout(): LinearLayout {
        return LinearLayout(this).apply {
            visibility = View.GONE
        }
    }

    private fun setupManipulationControls() {
        try {
            seekBarScale = findViewByIdSafe(R.id.seekbar_scale)
            btnResetTransform = findViewByIdSafe(R.id.btn_reset_transform)
            tvScaleValue = findViewByIdSafe(R.id.tv_scale_value)

            seekBarScale?.let { seekBar ->
                tvScaleValue?.let { scaleText ->
                    // Scale control
                    seekBar.max = 100
                    seekBar.progress = 50 // Default size
                    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                val scale = 2.0f + (progress / 100f) * 6.0f // 2mm to 8mm
                                scaleText.text = String.format("%.1fmm", scale)

                                selectedBracketId?.let { bracketId ->
                                    bracketTransforms[bracketId]?.let { transform ->
                                        toothDetector?.scaleBracket(transform, scale / transform.scale)
                                        updateSelectedBracketTransform(bracketId, transform)
                                    }
                                }
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
            }

            btnResetTransform?.setOnClickListener {
                selectedBracketId?.let { bracketId ->
                    resetBracketTransform(bracketId)
                }
            }

            Log.d(TAG, "Manipulation controls setup completed")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup manipulation controls (will continue without them)", e)
            // Continue without manipulation controls
        }
    }

    private fun loadBracketModel() {
        lifecycleScope.launch {
            try {
                showProgress(true, "Loading 3D model...")

                // Try different possible paths for the model
                val possiblePaths = listOf(
                    "models/Bracket.obj",
                    "models/bracket.obj",
                    "Bracket.obj",
                    "bracket.obj"
                )

                var inputStream: java.io.InputStream? = null
                var foundPath: String? = null

                for (path in possiblePaths) {
                    try {
                        inputStream = assets.open(path)
                        foundPath = path
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "Model not found at path: $path")
                    }
                }

                if (inputStream != null && foundPath != null) {
                    bracketModel = OBJLoader.loadOBJ(inputStream)
                    Log.d(TAG, "Bracket model loaded from $foundPath: ${bracketModel?.vertices?.size} vertices")
                } else {
                    Log.w(TAG, "No bracket model found, using default placeholder")
                    bracketModel = null
                }

                showProgress(false)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bracket model", e)
                showError("Failed to load 3D bracket model. Using placeholder.")
                showProgress(false)
                bracketModel = null
            }
        }
    }

    private fun initializeMLDetector() {
        try {
            toothDetector = AdvancedToothDetector(this)

            toothDetector?.let { detector ->
                if (detector.isUsingPlaceholder()) {
                    Toast.makeText(
                        this,
                        "Using simulation mode - ML model not found",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w(TAG, "Using placeholder tooth detection mode")
                } else {
                    Toast.makeText(
                        this,
                        "Real tooth detection model loaded!",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, "Real tooth detection model loaded successfully")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            showError("ML detector initialization failed: ${e.message}")

            try {
                toothDetector = AdvancedToothDetector(this)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Fallback detector also failed", fallbackException)
                showError("Complete detector failure. Using basic mode.")
                toothDetector = null
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted")
            } else {
                showError("Camera permission required for AR functionality")
                finish()
            }
        }
    }

    private fun startCameraSession() {
        showPatientNameDialog()
    }

    private fun showPatientNameDialog() {
        val input = EditText(this)
        input.hint = "Enter patient name"

        AlertDialog.Builder(this)
            .setTitle("New Patient Session")
            .setMessage("Enter the patient's name to begin:")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                patientName = input.text.toString().trim()
                if (patientName.isNotEmpty()) {
                    initializeARSession()
                } else {
                    showError("Patient name is required")
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun initializeARSession() {
        lifecycleScope.launch {
            try {
                showProgress(true, "Initializing AR...")

                // Check ARCore availability
                when (ArCoreApk.getInstance().requestInstall(this@AdvancedARCameraActivity, !shouldConfigureSession)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        shouldConfigureSession = true
                        showProgress(false)
                        return@launch
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // Continue
                    }
                }

                // Create AR session
                arSession = Session(this@AdvancedARCameraActivity).apply {
                    val config = Config(this).apply {
                        depthMode = Config.DepthMode.AUTOMATIC
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        focusMode = Config.FocusMode.AUTO
                    }
                    configure(config)


                    // Get camera intrinsics for ML detector safely after renderer initialized
                    surfaceView.queueEvent {
                        try {
                            arSession?.let { session ->
                                val frame = session.update()
                                val camera = frame.camera
                                if (camera.trackingState == TrackingState.TRACKING) {
                                    val intrinsics = camera.imageIntrinsics
                                    val focalLength = intrinsics.focalLength
                                    val principalPoint = intrinsics.principalPoint
                                    toothDetector?.setCameraIntrinsics(
                                        focalLength[0], focalLength[1],
                                        principalPoint[0], principalPoint[1]
                                    )
                                } else {
                                    toothDetector?.setCameraIntrinsics(500f, 500f, 320f, 240f)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not get camera intrinsics, using defaults", e)
                            toothDetector?.setCameraIntrinsics(500f, 500f, 320f, 240f)
                        }
                    }

                    arRenderer = AdvancedARRenderer(this@AdvancedARCameraActivity, arSession!!, bracketModel)

                }

                    // Initialize renderer
                arRenderer = AdvancedARRenderer(this@AdvancedARCameraActivity, arSession!!, bracketModel)
                surfaceView.apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(arRenderer)
                    renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }

                isCameraActive = true
                layoutControls.visibility = View.VISIBLE
                btnStartCamera.visibility = View.GONE
                showProgress(false)

                tvInstructions.text = "Point camera at teeth. Tap to place bracket."

                // Start detection loop
                startToothDetectionLoop()

                Log.d(TAG, "AR session initialized successfully")

            } catch (e: UnavailableArcoreNotInstalledException) {
                Log.e(TAG, "ARCore not installed", e)
                showError("Please install ARCore from Google Play Store")
            } catch (e: UnavailableUserDeclinedInstallationException) {
                Log.e(TAG, "User declined ARCore", e)
                showError("ARCore installation required")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AR", e)
                showError("AR initialization failed: ${e.message}")
            }
        }
    }

    private fun startToothDetectionLoop() {
        isDetecting = true

        lifecycleScope.launch {
            var frameCount = 0

            while (isCameraActive && isDetecting) {
                try {
                    arSession?.let { session ->
                        val frame = session.update()
                        val camera = frame.camera

                        if (camera.trackingState == TrackingState.TRACKING) {
                            // Detect teeth every 10 frames (~3 fps)
                            if (frameCount % 10 == 0) {
                                var image: Image? = null
                                try {
                                    image = frame.acquireCameraImage()
                                    val detectedTeeth = toothDetector?.detectTeeth(image) ?: emptyList()
                                    arRenderer?.updateDetectedTeeth(detectedTeeth)
                                    runOnUiThread { updateDetectionUI(detectedTeeth) }
                                } catch (e: NotYetAvailableException) {
                                    // Skip frame if not ready
                                } catch (e: Exception) {
                                    Log.w(TAG, "Detection frame error", e)
                                } finally {
                                    try {
                                        image?.close()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            frameCount++
                        }
                    }
                    delay(33) // ~30 FPS
                } catch (e: Exception) {
                    Log.e(TAG, "Detection error", e)
                    delay(100) // small delay to prevent crash loop
                }
            }
        }
    }


    // Additional methods would continue here...
    // For brevity, I'm including the key error-handling improvements

    private fun updateDetectionUI(teeth: List<AdvancedToothDetector.DetectedTooth>) {
        try {
            val teethCount = teeth.size
            val teethIds = teeth.joinToString(", ") { it.toothId }

            tvToothInfo.text = if (teethCount > 0) {
                "Detected: $teethCount teeth ($teethIds)"
            } else {
                "No teeth detected - point camera at teeth"
            }

            // Update manipulation info if in manipulation mode
            if (isManipulationMode) {
                selectedBracketId?.let { bracketId ->
                    val transform = bracketTransforms[bracketId]
                    tvManipulationInfo.text = "Selected: $bracketId | Size: ${String.format("%.1f", transform?.scale ?: 0f)}mm"
                }
            }

            // Update feedback for placed brackets
            val placedBrackets = arRenderer?.getPlacedBrackets() ?: emptyMap()
            val bestFeedback = placedBrackets.values.mapNotNull { it.positionFeedback }.minByOrNull { it.distance }

            bestFeedback?.let { feedback ->
                val color = when (feedback.quality) {
                    AdvancedToothDetector.QualityLevel.PERFECT -> "#4CAF50"
                    AdvancedToothDetector.QualityLevel.GOOD -> "#2196F3"
                    AdvancedToothDetector.QualityLevel.ACCEPTABLE -> "#FFC107"
                    else -> "#F44336"
                }

                if (!isManipulationMode) {
                    tvInstructions.text = android.text.Html.fromHtml(
                        "<font color='$color'>${feedback.guidance}</font>",
                        android.text.Html.FROM_HTML_MODE_LEGACY
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating detection UI", e)
        }
    }

    // Simplified placeholder methods for the remaining functionality
    private fun handleTouch(event: MotionEvent) {
        // Simplified touch handling to avoid crashes
        try {
            if (event.action == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "Touch detected at: ${event.x}, ${event.y}")
                // Add basic touch logic here
            }
        } catch (e: Exception) {
            Log.w(TAG, "Touch handling error", e)
        }
    }

    private fun updateSelectedBracketTransform(bracketId: String, transform: AdvancedToothDetector.BracketTransform) {
        try {
            arRenderer?.updateBracketTransform(bracketId, transform)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating bracket transform", e)
        }
    }

    private fun resetBracketTransform(bracketId: String) {
        try {
            bracketTransforms[bracketId]?.let { transform ->
                transform.rotation = AdvancedToothDetector.Vector3(0f, 0f, 0f)
                transform.scale = AdvancedToothDetector.DEFAULT_BRACKET_SIZE

                updateSelectedBracketTransform(bracketId, transform)

                seekBarScale?.progress = 50
                tvScaleValue?.text = String.format("%.1fmm", transform.scale)

                Toast.makeText(this, "Bracket transform reset", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting bracket transform", e)
        }
    }

    private fun toggleManipulationMode() {
        try {
            isManipulationMode = !isManipulationMode

            if (isManipulationMode) {
                btnManipulateMode.text = "Place Mode"
                layoutManipulation.visibility = View.VISIBLE
                tvInstructions.text = "Manipulation mode: Tap bracket to select, drag to rotate, pinch to scale"
                tvManipulationInfo.visibility = View.VISIBLE
            } else {
                btnManipulateMode.text = "Edit Mode"
                layoutManipulation.visibility = View.GONE
                tvInstructions.text = "Point camera at teeth. Tap to place bracket."
                tvManipulationInfo.visibility = View.GONE
                selectedBracketId = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error toggling manipulation mode", e)
        }
    }

    private fun undoLastPlacement() {
        try {
            if (bracketPlacements.isEmpty()) return

            val lastPlacement = bracketPlacements.removeAt(bracketPlacements.size - 1)
            arRenderer?.removeBracket(lastPlacement.id)
            bracketTransforms.remove(lastPlacement.id)

            if (selectedBracketId == lastPlacement.id) {
                selectedBracketId = null
            }

            updatePlacementCount()
            btnSaveSession.isEnabled = bracketPlacements.isNotEmpty()
            btnUndo.isEnabled = bracketPlacements.isNotEmpty()
            btnManipulateMode.isEnabled = bracketPlacements.isNotEmpty()

            Toast.makeText(this, "Bracket removed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Error undoing placement", e)
        }
    }

    private fun updatePlacementCount() {
        try {
            tvPlacementCount.text = "Brackets: ${bracketPlacements.size}"
        } catch (e: Exception) {
            Log.w(TAG, "Error updating placement count", e)
        }
    }

    private fun savePatientSession() {
        try {
            if (bracketPlacements.isEmpty()) {
                showError("No brackets placed yet")
                return
            }

            showProgress(true, "Saving session...")

            val session = PatientSession(
                id = UUID.randomUUID().toString(),
                patientName = patientName,
                timestamp = System.currentTimeMillis(),
                bracketPlacements = bracketPlacements,
                deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            // Generate QR code
            val qrCodeBitmap = QRCodeGenerator.generateQRCode(session)

            showProgress(false)
            showSaveDialog(session, qrCodeBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
            showProgress(false)
            showError("Failed to save session: ${e.message}")
        }
    }

    private fun showSaveDialog(session: PatientSession, qrCodeBitmap: Bitmap) {
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_save_session, null)
            val imageView = dialogView.findViewById<ImageView>(R.id.iv_qr_code)
            val tvSessionInfo = dialogView.findViewById<TextView>(R.id.tv_session_info)

            imageView.setImageBitmap(qrCodeBitmap)
            tvSessionInfo.text = """
                Patient: ${session.patientName}
                Brackets: ${session.bracketPlacements.size}
                Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))}
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Session Saved Successfully")
                .setView(dialogView)
                .setPositiveButton("Done") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing save dialog", e)
            Toast.makeText(this, "Session saved successfully", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showProgress(show: Boolean, message: String = "") {
        try {
            runOnUiThread {
                progressBar.visibility = if (show) View.VISIBLE else View.GONE
                if (show && message.isNotEmpty()) {
                    tvInstructions.text = message
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error showing progress", e)
        }
    }

    private fun showError(message: String) {
        try {
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error message", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            surfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            showError("Camera not available")
        } catch (e: Exception) {
            Log.e(TAG, "Resume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            surfaceView.onPause()
            arSession?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Pause error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCameraActive = false
        isDetecting = false

        try {
            arSession?.close()
            toothDetector?.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
