package com.dentalapp.artraining.data

import com.google.ar.core.Pose
import com.google.gson.Gson
import java.util.*


/**
 * Complete patient session with all bracket placements
 */
data class PatientSession(
    val id: String = UUID.randomUUID().toString(),
    val patientName: String,
    val timestamp: Long,
    val bracketPlacements: List<BracketPlacement>,
    val deviceInfo: String,
    val sessionDuration: Long = 0L,
    val notes: String = ""
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): PatientSession {
            return Gson().fromJson(json, PatientSession::class.java)
        }
    }
}

/**
 * Individual bracket placement data
 */
data class BracketPlacement(
    val id: String = UUID.randomUUID().toString(),
    val toothId: String,
    val pose: Pose,
    val timestamp: Long,
    val offsetMm: com.dentalapp.artraining.ml.AdvancedToothDetector.Vector3,
    val confidence: Float = 1.0f,
    val isVerified: Boolean = false
) {
    // Serializable version for JSON
    fun toSerializable(): SerializableBracketPlacement {
        val translation = pose.translation
        val rotation = pose.rotationQuaternion

        return SerializableBracketPlacement(
            id = id,
            toothId = toothId,
            positionX = translation[0],
            positionY = translation[1],
            positionZ = translation[2],
            rotationX = rotation[0],
            rotationY = rotation[1],
            rotationZ = rotation[2],
            rotationW = rotation[3],
            offsetX = offsetMm.x,
            offsetY = offsetMm.y,
            offsetZ = offsetMm.z,
            timestamp = timestamp,
            confidence = confidence,
            isVerified = isVerified
        )
    }
}

/**
 * Serializable version of bracket placement (for QR code/JSON)
 */
data class SerializableBracketPlacement(
    val id: String,
    val toothId: String,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val timestamp: Long,
    val confidence: Float,
    val isVerified: Boolean
) {
    fun toBracketPlacement(): BracketPlacement {
        val pose = Pose(
            floatArrayOf(positionX, positionY, positionZ),
            floatArrayOf(rotationX, rotationY, rotationZ, rotationW)
        )

        return BracketPlacement(
            id = id,
            toothId = toothId,
            pose = pose,
            timestamp = timestamp,
            offsetMm = com.dentalapp.artraining.ml.AdvancedToothDetector.Vector3(
                offsetX, offsetY, offsetZ
            ),
            confidence = confidence,
            isVerified = isVerified
        )
    }
}

/**
 * Serializable patient session for QR code
 */
data class SerializablePatientSession(
    val id: String,
    val patientName: String,
    val timestamp: Long,
    val bracketPlacements: List<SerializableBracketPlacement>,
    val deviceInfo: String,
    val sessionDuration: Long,
    val notes: String
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromPatientSession(session: PatientSession): SerializablePatientSession {
            return SerializablePatientSession(
                id = session.id,
                patientName = session.patientName,
                timestamp = session.timestamp,
                bracketPlacements = session.bracketPlacements.map { it.toSerializable() },
                deviceInfo = session.deviceInfo,
                sessionDuration = session.sessionDuration,
                notes = session.notes
            )
        }

        fun fromJson(json: String): SerializablePatientSession {
            return Gson().fromJson(json, SerializablePatientSession::class.java)
        }
    }

    fun toPatientSession(): PatientSession {
        return PatientSession(
            id = id,
            patientName = patientName,
            timestamp = timestamp,
            bracketPlacements = bracketPlacements.map { it.toBracketPlacement() },
            deviceInfo = deviceInfo,
            sessionDuration = sessionDuration,
            notes = notes
        )
    }
}

/**
 * Placement statistics for analysis
 */
data class PlacementStatistics(
    val totalBrackets: Int,
    val averageOffsetMm: Float,
    val maxOffsetMm: Float,
    val accuracyScore: Float, // 0-100
    val teethCovered: List<String>,
    val sessionDuration: Long
) {
    companion object {
        fun calculate(session: PatientSession): PlacementStatistics {
            val placements = session.bracketPlacements

            if (placements.isEmpty()) {
                return PlacementStatistics(0, 0f, 0f, 0f, emptyList(), 0L)
            }

            val offsets = placements.map { placement ->
                val offset = placement.offsetMm
                kotlin.math.sqrt(
                    offset.x * offset.x + offset.y * offset.y + offset.z * offset.z
                )
            }

            val avgOffset = offsets.average().toFloat()
            val maxOffset = offsets.maxOrNull() ?: 0f

            // Calculate accuracy score (100 = perfect, 0 = very poor)
            // Threshold: 0.5mm = perfect (100), 2mm = poor (50), 5mm+ = fail (0)
            val accuracyScore = placements.map { placement ->
                val offset = placement.offsetMm
                val totalOffset = kotlin.math.sqrt(
                    offset.x * offset.x + offset.y * offset.y + offset.z * offset.z
                )
                when {
                    totalOffset <= 0.5f -> 100f
                    totalOffset <= 1.0f -> 90f - (totalOffset - 0.5f) * 20f
                    totalOffset <= 2.0f -> 80f - (totalOffset - 1.0f) * 30f
                    totalOffset <= 5.0f -> 50f - (totalOffset - 2.0f) * 16.67f
                    else -> 0f
                }
            }.average().toFloat()

            return PlacementStatistics(
                totalBrackets = placements.size,
                averageOffsetMm = avgOffset,
                maxOffsetMm = maxOffset,
                accuracyScore = accuracyScore,
                teethCovered = placements.map { it.toothId }.distinct(),
                sessionDuration = session.sessionDuration
            )
        }
    }
}

/**
 * Treatment plan metadata
 */
data class TreatmentPlan(
    val id: String = UUID.randomUUID().toString(),
    val patientName: String,
    val createdDate: Long,
    val plannedTeeth: List<String>, // FDI notation
    val notes: String = "",
    val doctorName: String = "",
    val clinicName: String = ""
)
