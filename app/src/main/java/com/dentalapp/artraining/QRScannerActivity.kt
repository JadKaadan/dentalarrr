package com.dentalapp.artraining

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.ProjectResponse
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.dentalapp.artraining.network.ApiService
import kotlinx.coroutines.*

class QRScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QRScannerActivity"
        const val EXTRA_PROJECT_ID = "project_id"
        const val RESULT_PROJECT_LOADED = 100
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var capture: CaptureManager
    private var isScanning = true
    private var isTorchOn = false

    private val apiService = ApiService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        setupBarcodeScanner()
        setupUI()
    }

    private fun setupBarcodeScanner() {
        barcodeView = findViewById(R.id.barcode_scanner)

        // Configure scanner
        barcodeView.initializeFromIntent(intent)

        // Set callback for scan results
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (isScanning) {
                    isScanning = false
                    handleQRResult(result.text)
                }
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
                // Optional: Handle intermediate scanning feedback
            }
        })

        // Initialize capture manager
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, null)
        capture.decode()
    }

    private fun setupUI() {
        // Add cancel button
        findViewById<android.widget.ImageButton>(R.id.btn_cancel_scan).setOnClickListener {
            finish()
        }

        // Add flashlight toggle - fixed torch method
        findViewById<android.widget.ImageButton>(R.id.btn_flashlight).setOnClickListener {
            toggleTorch()
        }
    }

    private fun toggleTorch() {
        try {
            isTorchOn = !isTorchOn
            barcodeView.setTorchOn()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
            Toast.makeText(this, "Flashlight not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleQRResult(qrContent: String) {
        Log.d(TAG, "QR Code scanned: $qrContent")

        try {
            // Parse QR code content - expecting project ID or full project data
            when {
                qrContent.startsWith("dental_project:") -> {
                    val projectId = qrContent.removePrefix("dental_project:")
                    loadProjectById(projectId)
                }
                qrContent.startsWith("{") -> {
                    // Direct JSON project data
                    parseProjectJson(qrContent)
                }
                else -> {
                    showError("Invalid QR code format")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing QR code", e)
            showError("Failed to parse QR code")
        }
    }

    private fun loadProjectById(projectId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                showLoading(true)

                val project = withContext(Dispatchers.IO) {
                    apiService.getProject(projectId)
                }

                showLoading(false)
                returnProject(project)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load project", e)
                showLoading(false)
                showError("Failed to load project from server")
            }
        }
    }

    private fun parseProjectJson(jsonContent: String) {
        try {
            val project = com.google.gson.Gson().fromJson(
                jsonContent,
                ProjectResponse::class.java
            ).toProject()

            returnProject(project)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse project JSON", e)
            showError("Invalid project data in QR code")
        }
    }

    private fun returnProject(project: Project) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_PROJECT_ID, project.id)
            // In a full implementation, you'd pass the project object
            // or cache it locally and pass just the ID
        }

        setResult(RESULT_PROJECT_LOADED, resultIntent)
        finish()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        // Allow scanning again after error
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)
            isScanning = true
        }
    }

    private fun showLoading(show: Boolean) {
        findViewById<android.widget.ProgressBar>(R.id.loading_progress)?.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }
    
}
