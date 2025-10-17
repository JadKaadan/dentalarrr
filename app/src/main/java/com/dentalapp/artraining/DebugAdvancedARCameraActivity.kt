package com.dentalapp.artraining

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Debug version of the AR Camera Activity to isolate crash issues
 * Use this temporarily to identify what's causing the crash
 */
class DebugAdvancedARCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DebugARCamera"
        private const val CAMERA_PERMISSION_CODE = 100
    }

    // UI Components - only the essential ones
    private lateinit var btnStartCamera: Button
    private lateinit var tvInstructions: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "onCreate started")

            setContentView(R.layout.activity_advanced_arcamera)
            Log.d(TAG, "Layout set successfully")

            initializeBasicUI()
            Log.d(TAG, "Basic UI initialized")

            checkAssets()
            Log.d(TAG, "Assets checked")

            if (!checkCameraPermission()) {
                requestCameraPermission()
            }

            Log.d(TAG, "onCreate completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showError("Failed to initialize: ${e.message}")
        }
    }

    private fun initializeBasicUI() {
        try {
            // Find essential UI elements
            btnStartCamera = findViewById(R.id.btn_start_camera)
            tvInstructions = findViewById(R.id.tv_instructions)
            progressBar = findViewById(R.id.loading_indicator)

            Log.d(TAG, "Essential UI elements found")

            // Optional UI elements (may not exist in current layout)
            val optionalElements = mapOf(
                "tv_patient_name" to R.id.tv_patient_name,
                "tv_tooth_info" to R.id.tv_tooth_info,
                "tv_placement_count" to R.id.tv_placement_count,
                "btn_save_session" to R.id.btn_save_session,
                "btn_undo" to R.id.btn_undo,
                "layout_controls" to R.id.layout_controls
            )

            optionalElements.forEach { (name, id) ->
                try {
                    val view = findViewById<View>(id)
                    if (view != null) {
                        Log.d(TAG, "Found optional element: $name")
                    } else {
                        Log.w(TAG, "Optional element not found: $name")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error finding optional element $name: ${e.message}")
                }
            }

            btnStartCamera.setOnClickListener {
                testBasicFunctionality()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in initializeBasicUI", e)
            throw e
        }
    }

    private fun checkAssets() {
        try {
            val assetManager = assets

            // Check if models directory exists
            try {
                val modelsFiles = assetManager.list("models")
                if (modelsFiles != null) {
                    Log.d(TAG, "Files in models/ directory:")
                    modelsFiles.forEach { file ->
                        Log.d(TAG, "  - $file")
                    }
                } else {
                    Log.w(TAG, "models/ directory not found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading models/ directory: ${e.message}")
            }

            // Check if files exist in root assets
            try {
                val rootFiles = assetManager.list("")
                if (rootFiles != null) {
                    Log.d(TAG, "Files in root assets directory:")
                    rootFiles.forEach { file ->
                        Log.d(TAG, "  - $file")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading root assets: ${e.message}")
            }

            // Test specific file access
            val filesToTest = listOf(
                "models/Bracket.obj",
                "models/tooth_detection_yolov8.tflite",
                "Bracket.obj",
                "tooth_detection_yolov8.tflite"
            )

            filesToTest.forEach { fileName ->
                try {
                    val inputStream = assetManager.open(fileName)
                    val size = inputStream.available()
                    inputStream.close()
                    Log.d(TAG, "✓ Found file: $fileName (size: $size bytes)")
                } catch (e: Exception) {
                    Log.d(TAG, "✗ File not found: $fileName")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking assets", e)
        }
    }

    private fun testBasicFunctionality() {
        lifecycleScope.launch {
            try {
                showProgress(true, "Testing basic functionality...")

                // Test 1: Basic ML detector initialization
                Log.d(TAG, "Testing ML detector initialization...")
                val toothDetector = com.dentalapp.artraining.ml.AdvancedToothDetector(this@DebugAdvancedARCameraActivity)
                Log.d(TAG, "✓ ML detector created successfully")
                Log.d(TAG, "Using placeholder mode: ${toothDetector.isUsingPlaceholder()}")

                // Test 2: OBJ loader
                Log.d(TAG, "Testing OBJ loader...")
                try {
                    val inputStream = assets.open("models/Bracket.obj")
                    val model = com.dentalapp.artraining.ar.OBJLoader.loadOBJ(inputStream)
                    Log.d(TAG, "✓ OBJ model loaded: ${model.vertices.size} vertices")
                } catch (e: Exception) {
                    Log.w(TAG, "OBJ loading failed: ${e.message}")
                }

                // Test 3: Check OpenGL support
                Log.d(TAG, "Testing OpenGL support...")
                val activityManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val configInfo = activityManager.deviceConfigurationInfo
                val supportsOpenGL = configInfo.reqGlEsVersion >= 0x30000
                Log.d(TAG, "OpenGL ES 3.0 supported: $supportsOpenGL")

                showProgress(false)
                tvInstructions.text = "✓ Basic tests completed. Check logs for details."

                Toast.makeText(this@DebugAdvancedARCameraActivity,
                    "Tests completed! Check logcat for details.", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error in basic functionality test", e)
                showProgress(false)
                showError("Test failed: ${e.message}")
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
                showError("Camera permission required")
            }
        }
    }
}
