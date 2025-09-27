package com.dentalapp.artraining.reports

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.ToothStatus
import com.dentalapp.artraining.data.TrainingSession
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ReportGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ReportGenerator"
        private const val PAGE_WIDTH = 595 // A4 width in points
        private const val PAGE_HEIGHT = 842 // A4 height in points
        private const val MARGIN = 50
    }

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortDateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    suspend fun generatePDFReport(
        session: TrainingSession,
        project: Project
    ): File? {
        return try {
            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            // Setup paint objects
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 24f
                isAntiAlias = true
                isFakeBoldText = true
            }

            val headerPaint = Paint().apply {
                color = Color.BLACK
                textSize = 16f
                isAntiAlias = true
                isFakeBoldText = true
            }

            val bodyPaint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
                isAntiAlias = true
            }

            val tablePaint = Paint().apply {
                color = Color.GRAY
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }

            var yPosition = MARGIN + 30f

            // Title
            canvas.drawText("Dental AR Training Report", MARGIN.toFloat(), yPosition, titlePaint)
            yPosition += 50f

            // Session info
            canvas.drawText("Session Information", MARGIN.toFloat(), yPosition, headerPaint)
            yPosition += 25f

            canvas.drawText("Project: ${project.name}", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 20f

            canvas.drawText("Session ID: ${session.id}", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 20f

            canvas.drawText("Start Time: ${dateFormatter.format(Date(session.startTime))}", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 20f

            session.endTime?.let { endTime ->
                canvas.drawText("End Time: ${dateFormatter.format(Date(endTime))}", MARGIN.toFloat(), yPosition, bodyPaint)
                yPosition += 20f

                val duration = (endTime - session.startTime) / 1000
                val minutes = duration / 60
                val seconds = duration % 60
                canvas.drawText("Duration: ${minutes}m ${seconds}s", MARGIN.toFloat(), yPosition, bodyPaint)
                yPosition += 20f
            }

            canvas.drawText("Device: ${session.deviceInfo}", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 40f

            // Summary statistics
            canvas.drawText("Summary", MARGIN.toFloat(), yPosition, headerPaint)
            yPosition += 25f

            val completedTeeth = session.completedTeeth.count { it.status == ToothStatus.PLACED }
            val totalTeeth = session.completedTeeth.size
            val accuracy = if (totalTeeth > 0) (completedTeeth.toFloat() / totalTeeth * 100) else 0f

            canvas.drawText("Teeth Placed: $completedTeeth / $totalTeeth", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 20f

            canvas.drawText("Accuracy: ${String.format("%.1f", accuracy)}%", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 20f

            val avgTime = if (session.completedTeeth.isNotEmpty()) {
                session.completedTeeth.map { (it.placementTime - session.startTime) / 1000 }.average()
            } else 0.0
            canvas.drawText("Average Time per Tooth: ${String.format("%.1f", avgTime)}s", MARGIN.toFloat(), yPosition, bodyPaint)
            yPosition += 40f

            // Detailed results table
            canvas.drawText("Detailed Results", MARGIN.toFloat(), yPosition, headerPaint)
            yPosition += 25f

            // Table headers
            val tableLeft = MARGIN.toFloat()
            val colWidth = (PAGE_WIDTH - 2 * MARGIN) / 5f
            val tableTop = yPosition

            canvas.drawText("Tooth ID", tableLeft, yPosition, bodyPaint)
            canvas.drawText("Status", tableLeft + colWidth, yPosition, bodyPaint)
            canvas.drawText("Time (s)", tableLeft + colWidth * 2, yPosition, bodyPaint)
            canvas.drawText("Position Error (mm)", tableLeft + colWidth * 3, yPosition, bodyPaint)
            canvas.drawText("Rotation Error (°)", tableLeft + colWidth * 4, yPosition, bodyPaint)
            yPosition += 20f

            // Table lines
            canvas.drawLine(tableLeft, tableTop + 15f, tableLeft + colWidth * 5, tableTop + 15f, tablePaint)

            // Table data
            session.completedTeeth.forEach { placement ->
                canvas.drawText(placement.toothId, tableLeft, yPosition, bodyPaint)

                val statusText = when (placement.status) {
                    ToothStatus.PLACED -> "✓ Placed"
                    ToothStatus.PENDING -> "⏳ Pending"
                    ToothStatus.ERROR -> "✗ Error"
                }
                canvas.drawText(statusText, tableLeft + colWidth, yPosition, bodyPaint)

                val timeSeconds = ((placement.placementTime - session.startTime) / 1000f)
                canvas.drawText(String.format("%.1f", timeSeconds), tableLeft + colWidth * 2, yPosition, bodyPaint)

                canvas.drawText(
                    String.format("%.2f", placement.finalOffset.translationMagnitude),
                    tableLeft + colWidth * 3,
                    yPosition,
                    bodyPaint
                )

                canvas.drawText(
                    String.format("%.1f", placement.finalOffset.rotationMagnitude),
                    tableLeft + colWidth * 4,
                    yPosition,
                    bodyPaint
                )

                yPosition += 18f
            }

            // Footer
            yPosition = PAGE_HEIGHT - MARGIN - 20f
            canvas.drawText(
                "Generated by Dental AR Training App - ${dateFormatter.format(Date())}",
                MARGIN.toFloat(),
                yPosition,
                bodyPaint
            )

            document.finishPage(page)

            // Save to file
            val fileName = "dental_report_${session.id}_${shortDateFormatter.format(Date())}.pdf"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            val outputStream = FileOutputStream(file)
            document.writeTo(outputStream)
            document.close()
            outputStream.close()

            android.util.Log.d(TAG, "PDF report generated: ${file.absolutePath}")
            file

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to generate PDF report", e)
            null
        }
    }

    suspend fun generateCSVReport(
        session: TrainingSession,
        project: Project
    ): File? {
        return try {
            val fileName = "dental_report_${session.id}_${shortDateFormatter.format(Date())}.csv"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            val csvContent = buildString {
                // Header information
                appendLine("# Dental AR Training Report")
                appendLine("# Generated: ${dateFormatter.format(Date())}")
                appendLine("# Project: ${project.name}")
                appendLine("# Session ID: ${session.id}")
                appendLine("# Start Time: ${dateFormatter.format(Date(session.startTime))}")
                session.endTime?.let {
                    appendLine("# End Time: ${dateFormatter.format(Date(it))}")
                    appendLine("# Duration: ${(it - session.startTime) / 1000}s")
                }
                appendLine("# Device: ${session.deviceInfo}")
                appendLine("#")

                // Summary statistics
                val completedTeeth = session.completedTeeth.count { it.status == ToothStatus.PLACED }
                val totalTeeth = session.completedTeeth.size
                val accuracy = if (totalTeeth > 0) (completedTeeth.toFloat() / totalTeeth * 100) else 0f

                appendLine("# Summary Statistics")
                appendLine("# Teeth Placed: $completedTeeth / $totalTeeth")
                appendLine("# Accuracy: ${String.format("%.1f", accuracy)}%")
                appendLine("#")

                // CSV headers
                appendLine("tooth_id,status,placement_time_seconds,position_error_mm,rotation_error_degrees,translation_x,translation_y,translation_z,rotation_x,rotation_y,rotation_z")

                // Data rows
                session.completedTeeth.forEach { placement ->
                    val timeSeconds = (placement.placementTime - session.startTime) / 1000f
                    val status = placement.status.name.lowercase()

                    appendLine(
                        "${placement.toothId}," +
                                "$status," +
                                "${String.format("%.2f", timeSeconds)}," +
                                "${String.format("%.3f", placement.finalOffset.translationMagnitude)}," +
                                "${String.format("%.2f", placement.finalOffset.rotationMagnitude)}," +
                                "${String.format("%.3f", placement.finalOffset.translation.x)}," +
                                "${String.format("%.3f", placement.finalOffset.translation.y)}," +
                                "${String.format("%.3f", placement.finalOffset.translation.z)}," +
                                "${String.format("%.2f", placement.finalOffset.rotation.x)}," +
                                "${String.format("%.2f", placement.finalOffset.rotation.y)}," +
                                "${String.format("%.2f", placement.finalOffset.rotation.z)}"
                    )
                }
            }

            file.writeText(csvContent)
            android.util.Log.d(TAG, "CSV report generated: ${file.absolutePath}")
            file

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to generate CSV report", e)
            null
        }
    }

    suspend fun generateSessionSummary(sessions: List<TrainingSession>): SessionSummary {
        val completedSessions = sessions.filter { it.endTime != null }

        return SessionSummary(
            totalSessions = sessions.size,
            completedSessions = completedSessions.size,
            averageAccuracy = if (completedSessions.isNotEmpty()) {
                completedSessions.map { session ->
                    val completed = session.completedTeeth.count { it.status == ToothStatus.PLACED }
                    val total = session.completedTeeth.size
                    if (total > 0) (completed.toFloat() / total * 100) else 0f
                }.average().toFloat()
            } else 0f,
            averageCompletionTime = if (completedSessions.isNotEmpty()) {
                completedSessions.map { it.totalTime }.average().toLong()
            } else 0L,
            bestAccuracy = completedSessions.maxOfOrNull { session ->
                val completed = session.completedTeeth.count { it.status == ToothStatus.PLACED }
                val total = session.completedTeeth.size
                if (total > 0) (completed.toFloat() / total * 100) else 0f
            } ?: 0f,
            fastestTime = completedSessions.minOfOrNull { it.totalTime } ?: 0L,
            mostRecentSession = sessions.maxByOrNull { it.startTime }?.startTime,
            teethPlacementStats = calculateTeethStats(sessions)
        )
    }

    private fun calculateTeethStats(sessions: List<TrainingSession>): Map<String, ToothStats> {
        val allPlacements = sessions.flatMap { it.completedTeeth }
        val groupedByTooth = allPlacements.groupBy { it.toothId }

        return groupedByTooth.mapValues { (_, placements) ->
            val successful = placements.count { it.status == ToothStatus.PLACED }
            val total = placements.size
            val averageTime = placements.map { (it.placementTime) / 1000f }.average().toFloat()
            val averageAccuracy = if (total > 0) (successful.toFloat() / total * 100) else 0f

            ToothStats(
                attempts = total,
                successful = successful,
                successRate = averageAccuracy,
                averageTime = averageTime,
                averagePositionError = placements.map { it.finalOffset.translationMagnitude }.average().toFloat(),
                averageRotationError = placements.map { it.finalOffset.rotationMagnitude }.average().toFloat()
            )
        }
    }

    fun shareReport(file: File): android.content.Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "pdf" -> "application/pdf"
                "csv" -> "text/csv"
                else -> "text/plain"
            }
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Dental AR Training Report")
            putExtra(android.content.Intent.EXTRA_TEXT, "Training session report attached.")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

data class SessionSummary(
    val totalSessions: Int,
    val completedSessions: Int,
    val averageAccuracy: Float,
    val averageCompletionTime: Long,
    val bestAccuracy: Float,
    val fastestTime: Long,
    val mostRecentSession: Long?,
    val teethPlacementStats: Map<String, ToothStats>
)

data class ToothStats(
    val attempts: Int,
    val successful: Int,
    val successRate: Float,
    val averageTime: Float,
    val averagePositionError: Float,
    val averageRotationError: Float
)