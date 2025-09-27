package com.dentalapp.artraining

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.ToothStatus
import com.dentalapp.artraining.data.TrainingSession
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*
import com.dentalapp.artraining.data.database.DentalDatabase
import com.dentalapp.artraining.data.database.DentalRepository
import com.dentalapp.artraining.data.models.*
import com.dentalapp.artraining.network.ApiService
import com.dentalapp.artraining.reports.ReportGenerator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DentalMainActivity"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val QR_SCANNER_REQUEST = 200
    }

    // Core components
    private lateinit var dentalARManager: DentalARManager
    private lateinit var repository: DentalRepository
    private lateinit var apiService: ApiService
    private lateinit var reportGenerator: ReportGenerator

    // UI components
    private lateinit var projectNameText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var currentToothText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var toothInfoPanel: LinearLayout
    private lateinit var calibrationOverlay: FrameLayout

    // Buttons
    private lateinit var btnScanQR: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnReset: Button
    private lateinit var btnExport: Button
    private lateinit var btnCancelCalibration: Button

    // State
    private var currentProject: Project? = null
    private var currentSession: TrainingSession? = null
    private var arSession: Session? = null
    private var shouldConfigureSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeComponents()
        setupUI()

        if (!checkCameraPermission()) {
            requestCameraPermission()
        } else {
            initializeAR()
        }
    }

    private fun initializeComponents() {
        // Initialize database and repository
        val database = DentalDatabase.getDatabase(this)
        repository = DentalRepository(database)

        // Initialize network service
        apiService = ApiService()

        // Initialize report generator
        reportGenerator = ReportGenerator(this)

        Log.d(TAG, "Core components initialized")
    }

    private fun setupUI() {
        // Find all UI elements
        projectNameText = findViewById(R.id.project_name)
        progressText = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)
        currentToothText = findViewById(R.id.current_tooth)
        guidanceText = findViewById(R.id.guidance_text)
        toothInfoPanel = findViewById(R.id.tooth_info_panel)
        calibrationOverlay = findViewById(R.id.calibration_overlay)

        btnScanQR = findViewById(R.id.btn_scan_qr)
        btnCalibrate = findViewById(R.id.btn_calibrate)
        btnReset = findViewById(R.id.btn_reset)
        btnExport = findViewById(R.id.btn_export)
        btnCancelCalibration = findViewById(R.id.btn_cancel_calibration)

        // Setup button listeners
        btnScanQR.setOnClickListener { startQRScanner() }
        btnCalibrate.setOnClickListener { startCalibration() }
        btnReset.setOnClickListener { resetSession() }
        btnExport.setOnClickListener { exportSession() }
        btnCancelCalibration.setOnClickListener { cancelCalibration() }

        // Initially disable calibrate button until project is loaded
        btnCalibrate.isEnabled = false
        btnExport.visibility = Button.GONE
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
        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initializeAR()
                } else {
                    Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun initializeAR() {
        when (ArCoreApk.getInstance().requestInstall(this, !shouldConfigureSession)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                shouldConfigureSession = true
                return
            }
            ArCoreApk.InstallStatus.INSTALLED -> {
                // ARCore is installed, continue
            }
        }

        if (shouldConfigureSession) {
            configureARSession()
        }

        setupARFragment()
    }

    private fun configureARSession() {
        try {
            arSession = Session(this)
            val config = Config(arSession).apply {
                depthMode = Config.DepthMode.AUTOMATIC
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }
            arSession?.configure(config)
            shouldConfigureSession = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session", e)
            showError("Failed to initialize AR: ${e.message}")
        }
    }

    private fun setupARFragment() {
        // For MVP, we'll use a simple SurfaceView for camera preview
        // instead of the full ArFragment
        initializeCameraPreview()

        // Initialize dental AR manager with AR session
        arSession?.let { session ->
            dentalARManager = DentalARManager(this, session)
            setupDentalARCallbacks()

            Log.i(TAG, "AR components initialized")
            showInfo("Dental AR Ready - Scan QR code to load project")
        }
    }

    private fun initializeCameraPreview() {
        // For MVP, we'll show a simple camera preview
        // In production, this would be a proper AR view with 3D overlays
        Log.d(TAG, "Camera preview initialized (simplified for MVP)")
    }

    private fun setupDentalARCallbacks() {
        dentalARManager.onToothStatusChanged = { toothId, status, guidance ->
            runOnUiThread {
                currentToothText.text = "Tooth #$toothId"
                guidanceText.text = guidance ?: "Position camera over tooth"

                // Update UI based on status
                when (status) {
                    ToothStatus.PLACED -> {
                        currentToothText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    }
                    ToothStatus.PENDING -> {
                        currentToothText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    }
                    ToothStatus.ERROR -> {
                        currentToothText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    }
                }
            }
        }

        dentalARManager.onProgressUpdated = { completed, total ->
            runOnUiThread {
                progressBar.max = total
                progressBar.progress = completed
                progressText.text = "$completed/$total"

                // Show export button when complete
                if (completed == total && total > 0) {
                    btnExport.visibility = Button.VISIBLE
                    showInfo("Session complete! Export your results.")
                }
            }
        }

        dentalARManager.onCalibrationStatusChanged = { isCalibrated ->
            runOnUiThread {
                if (isCalibrated) {
                    calibrationOverlay.visibility = FrameLayout.GONE
                    showInfo("Calibration complete - start placing brackets!")
                } else {
                    calibrationOverlay.visibility = FrameLayout.VISIBLE
                }
            }
        }
    }

    private fun startQRScanner() {
        val intent = Intent(this, QRScannerActivity::class.java)
        startActivityForResult(intent, QR_SCANNER_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            QR_SCANNER_REQUEST -> {
                if (resultCode == QRScannerActivity.RESULT_PROJECT_LOADED) {
                    val projectId = data?.getStringExtra(QRScannerActivity.EXTRA_PROJECT_ID)
                    if (projectId != null) {
                        loadProject(projectId)
                    }
                }
            }
        }
    }

    private fun loadProject(projectId: String) {
        lifecycleScope.launch {
            try {
                showLoading(true)

                // Try to get project from cache or API
                val project = repository.getCachedProjectOrFetch(projectId, apiService)

                if (project != null) {
                    currentProject = project
                    dentalARManager.loadProject(project)

                    projectNameText.text = project.name
                    btnCalibrate.isEnabled = true

                    showInfo("Project loaded: ${project.name}")
                } else {
                    showError("Failed to load project")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading project", e)
                showError("Error loading project: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun startCalibration() {
        currentProject?.let { project ->
            calibrationOverlay.visibility = FrameLayout.VISIBLE
            dentalARManager.startCalibration()
            showInfo("Point camera at AprilTag marker to calibrate")
        } ?: showError("Load a project first")
    }

    private fun cancelCalibration() {
        calibrationOverlay.visibility = FrameLayout.GONE
    }

    private fun resetSession() {
        dentalARManager.resetSession()
        btnExport.visibility = Button.GONE
        progressBar.progress = 0
        progressText.text = "0/0"
        showInfo("Session reset")
    }

    private fun exportSession() {
        lifecycleScope.launch {
            try {
                showLoading(true)

                val session = dentalARManager.exportSession()
                val project = currentProject

                if (session != null && project != null) {
                    // Generate PDF report
                    val pdfFile = reportGenerator.generatePDFReport(session, project)

                    // Generate CSV report
                    val csvFile = reportGenerator.generateCSVReport(session, project)

                    // Save session to database
                    repository.saveSession(session)

                    // Show sharing options
                    if (pdfFile != null) {
                        val shareIntent = reportGenerator.shareReport(pdfFile)
                        startActivity(Intent.createChooser(shareIntent, "Share Report"))
                    }

                    showInfo("Reports generated and saved")
                } else {
                    showError("No session data to export")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting session", e)
                showError("Export failed: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        // You can add a loading overlay here
        btnScanQR.isEnabled = !show
        btnCalibrate.isEnabled = !show && currentProject != null
        btnReset.isEnabled = !show
        btnExport.isEnabled = !show
    }

    private fun showInfo(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.i(TAG, message)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during resume", e)
            showError("Camera not available")
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        if (::dentalARManager.isInitialized) {
            dentalARManager.cleanup()
        }
    }
}