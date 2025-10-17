package com.dentalapp.artraining

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
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

class AdvancedARCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AdvancedARCamera"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val MIN_OPENGL_VERSION = 3.0
    }

    // AR Core
    private var arSession: Session? = null
    private lateinit var arRenderer: AdvancedARRenderer
    private var shouldConfigureSession = false

    // ML Detection
    private lateinit var toothDetector: AdvancedToothDetector

    // 3D Model
    private var bracketModel: OBJLoader.OBJModel? = null

    // UI Components
    private lateinit var surfaceView: android.opengl.GLSurfaceView
    private lateinit var btnStartCamera: Button
    private lateinit var btnSaveSession: Button
    private lateinit var btnUndo: Button
    private lateinit var tvInstructions: TextView
    private lateinit var tvToothInfo: TextView
    private lateinit var tvPlacementCount: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var layoutControls: LinearLayout
    private lateinit var progressBar: ProgressBar

    // Session Data
    private var patientName: String = ""
    private val bracketPlacements = mutableListOf<BracketPlacement>()
    private var isCameraActive = false
    private var isDetecting = false

    // Touch handling
    private var lastTapTime = 0L
    private val TAP_THRESHOLD = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_arcamera)

        if (!checkOpenGLVersion()) {
            showError("OpenGL ES 3.0 is required but not supported")
            finish()
            return
        }

        initializeUI()
        loadBracketModel()
        initializeMLDetector()

        if (!checkCameraPermission()) {
            requestCameraPermission()
        }
    }

    private fun checkOpenGLVersion(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        return configInfo.reqGlEsVersion >= MIN_OPENGL_VERSION
    }

    private fun initializeUI() {
        surfaceView = findViewById(R.id.ar_surface_view)
        btnStartCamera = findViewById(R.id.btn_start_camera)
        btnSaveSession = findViewById(R.id.btn_save_session)
        btnUndo = findViewById(R.id.btn_undo)
        tvInstructions = findViewById(R.id.tv_instructions)
        tvToothInfo = findViewById(R.id.tv_tooth_info)
        tvPlacementCount = findViewById(R.id.tv_placement_count)
        tvFeedback = findViewById<TextView>(R.id.tv_instructions) // Reusing for feedback
        layoutControls = findViewById(R.id.layout_controls)
        progressBar = findViewById(R.id.loading_indicator)

        btnStartCamera.setOnClickListener { startCameraSession() }
        btnSaveSession.setOnClickListener { savePatientSession() }
        btnUndo.setOnClickListener { undoLastPlacement() }

        // Initially hide controls
        layoutControls.visibility = View.GONE
        btnSaveSession.isEnabled = false
        btnUndo.isEnabled = false
        progressBar.visibility = View.GONE

        // Touch listener for tooth selection
        surfaceView.setOnTouchListener { _, event ->
            if (isCameraActive) {
                handleTouch(event)
            }
            true
        }
    }

    private fun loadBracketModel() {
        lifecycleScope.launch {
            try {
                showProgress(true, "Loading 3D model...")

                val inputStream = assets.open("models/Bracket.obj")
                bracketModel = OBJLoader.loadOBJ(inputStream)

                Log.d(TAG, "Bracket model loaded: ${bracketModel?.vertices?.size} vertices")
                showProgress(false)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bracket model", e)
                showError("Failed to load 3D bracket model. Using placeholder.")
                showProgress(false)
            }
        }
    }

    private fun initializeMLDetector() {
        try {
            toothDetector = AdvancedToothDetector(this)

            if (toothDetector.isUsingPlaceholder()) {
                Toast.makeText(
                    this,
                    "Using simulation mode - waiting for trained model",
                    Toast.LENGTH_LONG
                ).show()
            }

            Log.d(TAG, "Tooth detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            showError("ML detector initialization failed")
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

    // Replace lines 240-260 in AdvancedARCameraActivity.kt with this:

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

                    // Get camera intrinsics for ML detector - FIXED
                    try {
                        // Update the session to get a frame
                        val frame = update()
                        val camera = frame.camera

                        if (camera.trackingState == TrackingState.TRACKING) {
                            // Get intrinsics from camera
                            val intrinsics = camera.imageIntrinsics
                            val focalLength = intrinsics.focalLength
                            val principalPoint = intrinsics.principalPoint

                            toothDetector.setCameraIntrinsics(
                                focalLength[0],
                                focalLength[1],
                                principalPoint[0],
                                principalPoint[1]
                            )
                        } else {
                            // Use default values if tracking not ready
                            toothDetector.setCameraIntrinsics(500f, 500f, 320f, 240f)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get camera intrinsics, using defaults", e)
                        toothDetector.setCameraIntrinsics(500f, 500f, 320f, 240f)
                    }
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
                            // Detect teeth every 10 frames (~3 fps) to reduce load
                            if (frameCount % 10 == 0) {
                                try {
                                    val image = frame.acquireCameraImage()

                                    // Detect teeth
                                    val detectedTeeth = toothDetector.detectTeeth(image)

                                    // Update renderer
                                    arRenderer.updateDetectedTeeth(detectedTeeth)

                                    // Update UI
                                    runOnUiThread {
                                        updateDetectionUI(detectedTeeth)
                                    }

                                    image.close()
                                } catch (e: NotYetAvailableException) {
                                    // Image not ready, skip this frame
                                }
                            }

                            frameCount++
                        }
                    }

                    delay(33) // ~30 FPS main loop

                } catch (e: Exception) {
                    Log.e(TAG, "Detection error", e)
                    delay(100) // Wait before retry
                }
            }
        }
    }

    private fun updateDetectionUI(teeth: List<AdvancedToothDetector.DetectedTooth>) {
        val teethCount = teeth.size
        val teethIds = teeth.joinToString(", ") { it.toothId }

        tvToothInfo.text = if (teethCount > 0) {
            "Detected: $teethCount teeth ($teethIds)"
        } else {
            "No teeth detected - point camera at teeth"
        }

        // Update feedback for placed brackets
        val placedBrackets = arRenderer.getPlacedBrackets()
        val bestFeedback = placedBrackets.values.mapNotNull { it.positionFeedback }.minByOrNull { it.distance }

        bestFeedback?.let { feedback ->
            val color = when (feedback.quality) {
                AdvancedToothDetector.QualityLevel.PERFECT -> "#4CAF50"
                AdvancedToothDetector.QualityLevel.GOOD -> "#2196F3"
                AdvancedToothDetector.QualityLevel.ACCEPTABLE -> "#FFC107"
                else -> "#F44336"
            }

            tvInstructions.text = android.text.Html.fromHtml(
                "<font color='$color'>${feedback.guidance}</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY
            )
        }
    }

    private fun handleTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < TAP_THRESHOLD) {
                return // Ignore double tap
            }
            lastTapTime = currentTime

            val x = event.x
            val y = event.y

            // Hit test on detected teeth
            val hitTooth = arRenderer.hitTestTooth(x, y, surfaceView.width, surfaceView.height)

            if (hitTooth != null) {
                showBracketPlacementDialog(hitTooth)
            } else {
                Toast.makeText(this, "Tap on a detected tooth", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBracketPlacementDialog(tooth: AdvancedToothDetector.DetectedTooth) {
        val message = """
            Tooth: ${tooth.toothId}
            Confidence: ${String.format("%.1f%%", tooth.confidence * 100)}
            
            Place bracket on this tooth?
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Place Bracket")
            .setMessage(message)
            .setPositiveButton("Place") { _, _ ->
                placeBracketOnTooth(tooth)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeBracketOnTooth(tooth: AdvancedToothDetector.DetectedTooth) {
        try {
            // Create anchor at optimal bracket position
            val optimalPos = tooth.optimalBracketPosition
            val pose = Pose(
                floatArrayOf(optimalPos.x, optimalPos.y, optimalPos.z),
                floatArrayOf(0f, 0f, 0f, 1f) // Neutral rotation
            )

            arSession?.let { session ->
                val anchor = session.createAnchor(pose)
                val bracketId = UUID.randomUUID().toString()

                // Add to renderer
                arRenderer.addBracket(bracketId, anchor, tooth.toothId)

                // Save placement data
                val placement = BracketPlacement(
                    id = bracketId,
                    toothId = tooth.toothId,
                    pose = pose,
                    timestamp = System.currentTimeMillis(),
                    offsetMm = AdvancedToothDetector.Vector3(0f, 0f, 0f), // Initial offset
                    confidence = tooth.confidence
                )
                bracketPlacements.add(placement)

                // Update UI
                updatePlacementCount()
                btnSaveSession.isEnabled = true
                btnUndo.isEnabled = true

                // Haptic feedback
                vibrateOnPlacement()

                Log.d(TAG, "Bracket placed on tooth ${tooth.toothId}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to place bracket", e)
            showError("Failed to place bracket: ${e.message}")
        }
    }

    private fun updatePlacementCount() {
        tvPlacementCount.text = "Brackets: ${bracketPlacements.size}"
    }

    private fun undoLastPlacement() {
        if (bracketPlacements.isEmpty()) return

        val lastPlacement = bracketPlacements.removeAt(bracketPlacements.size - 1)
        arRenderer.removeBracket(lastPlacement.id)

        updatePlacementCount()
        btnSaveSession.isEnabled = bracketPlacements.isNotEmpty()
        btnUndo.isEnabled = bracketPlacements.isNotEmpty()

        Toast.makeText(this, "Bracket removed", Toast.LENGTH_SHORT).show()
    }

    private fun savePatientSession() {
        if (bracketPlacements.isEmpty()) {
            showError("No brackets placed yet")
            return
        }

        try {
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
    }

    private fun vibrateOnPlacement() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun showProgress(show: Boolean, message: String = "") {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
            if (show && message.isNotEmpty()) {
                tvInstructions.text = message
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.e(TAG, message)
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
            toothDetector.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
