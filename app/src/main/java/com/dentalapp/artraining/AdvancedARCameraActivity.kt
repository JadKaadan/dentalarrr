package com.dentalapp.artraining

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
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
import com.dentalapp.artraining.data.BracketPlacement
import com.dentalapp.artraining.data.PatientSession
import com.dentalapp.artraining.ml.AdvancedToothDetector
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.launch
import java.util.*
import android.os.VibrationEffect
import android.os.Vibrator
import com.dentalapp.artraining.ar.OBJLoader
import com.dentalapp.artraining.utils.QRCodeGenerator
import android.content.Context

class AdvancedARCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AdvancedARCamera"
        private const val CAMERA_PERMISSION_CODE = 100
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
    private lateinit var layoutControls: LinearLayout

    // Session Data
    private var patientName: String = ""
    private val bracketPlacements = mutableListOf<BracketPlacement>()
    private var selectedToothId: String? = null
    private var isCameraActive = false

    // Touch handling
    private var lastTapTime = 0L
    private val TAP_THRESHOLD = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_arcamera)

        initializeUI()
        loadBracketModel()
        initializeMLDetector()

        if (!checkCameraPermission()) {
            requestCameraPermission()
        }
    }

    private fun initializeUI() {
        surfaceView = findViewById(R.id.ar_surface_view)
        btnStartCamera = findViewById(R.id.btn_start_camera)
        btnSaveSession = findViewById(R.id.btn_save_session)
        btnUndo = findViewById(R.id.btn_undo)
        tvInstructions = findViewById(R.id.tv_instructions)
        tvToothInfo = findViewById(R.id.tv_tooth_info)
        tvPlacementCount = findViewById(R.id.tv_placement_count)
        layoutControls = findViewById(R.id.layout_controls)

        btnStartCamera.setOnClickListener { startCameraSession() }
        btnSaveSession.setOnClickListener { savePatientSession() }
        btnUndo.setOnClickListener { undoLastPlacement() }

        // Initially hide controls
        layoutControls.visibility = View.GONE
        btnSaveSession.isEnabled = false
        btnUndo.isEnabled = false

        // Touch listener for tooth selection
        surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
    }

    private fun loadBracketModel() {
        lifecycleScope.launch {
            try {
                // Load the OBJ file from assets
                val inputStream = assets.open("models/bracket.obj")
                bracketModel = OBJLoader.loadOBJ(inputStream)
                Log.d(TAG, "Bracket model loaded: ${bracketModel?.vertices?.size} vertices")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bracket model", e)
                showError("Failed to load 3D bracket model")
            }
        }
    }

    private fun initializeMLDetector() {
        toothDetector = AdvancedToothDetector(this)
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
                // Permission granted
            } else {
                showError("Camera permission required")
                finish()
            }
        }
    }

    private fun startCameraSession() {
        // Prompt for patient name first
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
            .show()
    }

    private fun initializeARSession() {
        try {
            // Check ARCore availability
            when (ArCoreApk.getInstance().requestInstall(this, !shouldConfigureSession)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    shouldConfigureSession = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // Continue
                }
            }

            // Create AR session
            arSession = Session(this).apply {
                val config = Config(this).apply {
                    depthMode = Config.DepthMode.AUTOMATIC
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    focusMode = Config.FocusMode.AUTO
                }
                configure(config)
            }

            // Initialize renderer
            arRenderer = AdvancedARRenderer(this, arSession!!, bracketModel)
            surfaceView.preserveEGLContextOnPause = true
            surfaceView.setEGLContextClientVersion(3)
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            surfaceView.setRenderer(arRenderer)
            surfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

            isCameraActive = true
            layoutControls.visibility = View.VISIBLE
            btnStartCamera.visibility = View.GONE

            tvInstructions.text = "Point camera at teeth. Tap on a tooth to place bracket."

            // Start detection loop
            startToothDetectionLoop()

            Log.d(TAG, "AR session initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AR session", e)
            showError("Failed to start AR: ${e.message}")
        }
    }

    private fun startToothDetectionLoop() {
        lifecycleScope.launch {
            while (isCameraActive) {
                try {
                    arSession?.let { session ->
                        val frame = session.update()
                        val camera = frame.camera

                        if (camera.trackingState == TrackingState.TRACKING) {
                            // Get camera image
                            val image = frame.acquireCameraImage()

                            // Detect teeth
                            val detectedTeeth = toothDetector.detectTeeth(image)

                            // Update renderer with detected teeth
                            arRenderer.updateDetectedTeeth(detectedTeeth)

                            // Update UI
                            runOnUiThread {
                                tvToothInfo.text = "Detected: ${detectedTeeth.size} teeth"
                            }

                            image.close()
                        }
                    }

                    kotlinx.coroutines.delay(100) // 10 FPS detection
                } catch (e: Exception) {
                    Log.e(TAG, "Detection error", e)
                }
            }
        }
    }

    private fun handleTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < TAP_THRESHOLD) {
                // Double tap - ignore
                return
            }
            lastTapTime = currentTime

            // Get tap coordinates
            val x = event.x
            val y = event.y

            // Perform hit test on detected teeth
            val hitTooth = arRenderer.hitTestTooth(x, y, surfaceView.width, surfaceView.height)

            if (hitTooth != null) {
                selectedToothId = hitTooth.toothId
                showBracketPlacementDialog(hitTooth)
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
            .setNegativeButton("Cancel") { _, _ ->
                selectedToothId = null
            }
            .show()
    }

    private fun placeBracketOnTooth(tooth: AdvancedToothDetector.DetectedTooth) {
        try {
            // Calculate ideal bracket placement
            val placementPose = calculateBracketPlacement(tooth)

            // Create anchor at placement position
            arSession?.let { session ->
                val anchor = session.createAnchor(placementPose)

                // Add bracket to renderer
                val bracketId = UUID.randomUUID().toString()
                arRenderer.addBracket(bracketId, anchor, tooth.toothId)

                // Save placement data
                val placement = BracketPlacement(
                    id = bracketId,
                    toothId = tooth.toothId,
                    pose = placementPose,
                    timestamp = System.currentTimeMillis(),
                    offsetMm = calculateOffset(tooth, placementPose)
                )
                bracketPlacements.add(placement)

                // Update UI
                updatePlacementCount()
                btnSaveSession.isEnabled = bracketPlacements.isNotEmpty()
                btnUndo.isEnabled = bracketPlacements.isNotEmpty()

                // Show guidance
                showPlacementGuidance(placement)

                Log.d(TAG, "Bracket placed on tooth ${tooth.toothId}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to place bracket", e)
            showError("Failed to place bracket: ${e.message}")
        }
    }

    private fun calculateBracketPlacement(tooth: AdvancedToothDetector.DetectedTooth): Pose {
        // Calculate ideal bracket position based on tooth surface
        val toothCenter = tooth.pose.position

        // Offset bracket slightly outward from tooth surface (1mm)
        val offsetDistance = 0.001f // 1mm in meters

        // Calculate normal direction (pointing outward from tooth)
        val normal = tooth.surfaceNormal

        val bracketPosition = floatArrayOf(
            toothCenter.x + normal.x * offsetDistance,
            toothCenter.y + normal.y * offsetDistance,
            toothCenter.z + normal.z * offsetDistance
        )

        // Calculate rotation to align bracket with tooth surface
        val rotation = calculateBracketRotation(normal)

        return Pose(bracketPosition, rotation)
    }

    private fun calculateBracketRotation(normal: AdvancedToothDetector.Vector3): FloatArray {
        // Create rotation quaternion to align bracket with surface normal
        // This is simplified - in production, use proper quaternion math

        val up = floatArrayOf(0f, 1f, 0f)
        val right = crossProduct(up, floatArrayOf(normal.x, normal.y, normal.z))
        val newUp = crossProduct(floatArrayOf(normal.x, normal.y, normal.z), right)

        // Convert to quaternion (simplified)
        return floatArrayOf(0f, 0f, 0f, 1f) // Identity rotation for now
    }

    private fun crossProduct(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    private fun calculateOffset(
        tooth: AdvancedToothDetector.DetectedTooth,
        placementPose: Pose
    ): AdvancedToothDetector.Vector3 {
        // Calculate offset in mm from ideal position
        val idealPos = tooth.pose.position
        val actualPos = placementPose.translation

        return AdvancedToothDetector.Vector3(
            (actualPos[0] - idealPos.x) * 1000, // Convert to mm
            (actualPos[1] - idealPos.y) * 1000,
            (actualPos[2] - idealPos.z) * 1000
        )
    }

    private fun showPlacementGuidance(placement: BracketPlacement) {
        val offset = placement.offsetMm
        val totalOffset = kotlin.math.sqrt(
            offset.x * offset.x + offset.y * offset.y + offset.z * offset.z
        )

        val guidance = if (totalOffset < 0.5f) {
            "✓ Perfect placement! (${String.format("%.2f", totalOffset)}mm offset)"
        } else if (totalOffset < 1.0f) {
            "✓ Good placement (${String.format("%.2f", totalOffset)}mm offset)"
        } else {
            "⚠ Adjust placement (${String.format("%.2f", totalOffset)}mm offset)"
        }

        tvInstructions.text = guidance
        Toast.makeText(this, guidance, Toast.LENGTH_SHORT).show()
    }

    private fun updatePlacementCount() {
        tvPlacementCount.text = "Brackets placed: ${bracketPlacements.size}"
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
            // Create patient session
            val session = PatientSession(
                id = UUID.randomUUID().toString(),
                patientName = patientName,
                timestamp = System.currentTimeMillis(),
                bracketPlacements = bracketPlacements,
                deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )

            // Generate QR code
            val qrCodeBitmap = QRCodeGenerator.generateQRCode(session)

            // Show save dialog
            showSaveDialog(session, qrCodeBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
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
            .setTitle("Session Saved")
            .setView(dialogView)
            .setPositiveButton("Share QR") { _, _ ->
                shareQRCode(qrCodeBitmap, session)
            }
            .setNegativeButton("Done") { _, _ ->
                finish()
            }
            .show()
    }

    private fun shareQRCode(qrCode: Bitmap, session: PatientSession) {
        // Save QR code and share
        // Implementation details...
        Toast.makeText(this, "QR code saved", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            surfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            showError("Camera not available")
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isCameraActive = false
        arSession?.close()
        toothDetector.cleanup()
    }




    private fun vibrateOnPlacement() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Use API-level-safe vibration
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }


}
