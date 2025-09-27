package com.dentalapp.artraining.data

import androidx.room.*
import com.google.gson.annotations.SerializedName
import java.util.UUID

// Core dental training models

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val archType: String, // "upper", "lower", "full"
    val tolerance: Tolerance,
    val qrCode: String,
    val enabledTeeth: List<String>, // ["11", "12", "13", "21", "22", "23"]
    val idealPoses: Map<String, Pose3D>,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sessions")
data class TrainingSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val userId: String = "default_user",
    val deviceInfo: String,
    val startTime: Long,
    var endTime: Long? = null,
    var completedTeeth: List<ToothPlacement> = emptyList(),
    var accuracy: Float = 0f,
    var totalTime: Long = 0L
) {
    fun calculateMetrics() {
        endTime?.let { end ->
            totalTime = end - startTime
            accuracy = if (completedTeeth.isNotEmpty()) {
                completedTeeth.count { it.status == ToothStatus.PLACED }.toFloat() / completedTeeth.size * 100f
            } else 0f
        }
    }
}

data class ToothPlacement(
    val toothId: String,
    val finalOffset: PoseOffset,
    val placementTime: Long,
    val status: ToothStatus
)

data class Tolerance(
    val positionMm: Float = 1.5f,
    val rotationDeg: Float = 3.0f
)

data class Pose3D(
    val position: Vector3D,
    val rotation: Quaternion3D
)

data class Vector3D(
    val x: Float,
    val y: Float,
    val z: Float
)

data class Quaternion3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
)

data class PoseOffset(
    val translation: SimpleVector3D = SimpleVector3D(0f, 0f, 0f),
    val rotation: SimpleVector3D = SimpleVector3D(0f, 0f, 0f),
    val translationMagnitude: Float = 0f,
    val rotationMagnitude: Float = 0f
)

data class SimpleVector3D(
    val x: Float,
    val y: Float,
    val z: Float
)

enum class ToothStatus {
    PENDING,
    PLACED,
    ERROR
}

// ML detection result
data class ToothDetectionResult(
    val toothId: String,
    val confidence: Float,
    val pose: Pose3D,
    val boundingBox: BoundingBox
)

data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

// API models for backend communication
data class ProjectResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("arch_type") val archType: String,
    @SerializedName("tolerance_pos_mm") val tolerancePosMm: Float,
    @SerializedName("tolerance_rot_deg") val toleranceRotDeg: Float,
    @SerializedName("qr_code") val qrCode: String,
    @SerializedName("enabled_teeth") val enabledTeeth: List<String>,
    @SerializedName("ideal_poses") val idealPoses: Map<String, Pose3DApi>
) {
    fun toProject(): Project {
        return Project(
            id = id,
            name = name,
            archType = archType,
            tolerance = Tolerance(tolerancePosMm, toleranceRotDeg),
            qrCode = qrCode,
            enabledTeeth = enabledTeeth,
            idealPoses = idealPoses.mapValues { it.value.toPose3D() }
        )
    }
}

data class Pose3DApi(
    @SerializedName("position") val position: Vector3DApi,
    @SerializedName("rotation") val rotation: Quaternion3DApi
) {
    fun toPose3D(): Pose3D {
        return Pose3D(
            position = position.toVector3D(),
            rotation = rotation.toQuaternion3D()
        )
    }
}

data class Vector3DApi(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float,
    @SerializedName("z") val z: Float
) {
    fun toVector3D(): Vector3D = Vector3D(x, y, z)
}

data class Quaternion3DApi(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float,
    @SerializedName("z") val z: Float,
    @SerializedName("w") val w: Float
) {
    fun toQuaternion3D(): Quaternion3D = Quaternion3D(x, y, z, w)
}

// Room type converters
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }

    @TypeConverter
    fun fromTolerance(tolerance: Tolerance): String {
        return "${tolerance.positionMm},${tolerance.rotationDeg}"
    }

    @TypeConverter
    fun toTolerance(value: String): Tolerance {
        val parts = value.split(",")
        return Tolerance(parts[0].toFloat(), parts[1].toFloat())
    }

    @TypeConverter
    fun fromPoseMap(poses: Map<String, Pose3D>): String {
        // Simple JSON serialization - in production, use proper JSON converter
        return poses.entries.joinToString("|") { (key, pose) ->
            "$key:${pose.position.x},${pose.position.y},${pose.position.z}," +
                    "${pose.rotation.x},${pose.rotation.y},${pose.rotation.z},${pose.rotation.w}"
        }
    }

    @TypeConverter
    fun toPoseMap(value: String): Map<String, Pose3D> {
        if (value.isEmpty()) return emptyMap()

        return value.split("|").associate { entry ->
            val parts = entry.split(":")
            val key = parts[0]
            val values = parts[1].split(",").map { it.toFloat() }

            key to Pose3D(
                position = Vector3D(values[0], values[1], values[2]),
                rotation = Quaternion3D(values[3], values[4], values[5], values[6])
            )
        }
    }

    @TypeConverter
    fun fromToothPlacementList(placements: List<ToothPlacement>): String {
        // Simplified serialization for MVP
        return placements.joinToString("|") { placement ->
            "${placement.toothId}:${placement.status}:${placement.placementTime}"
        }
    }

    @TypeConverter
    fun toToothPlacementList(value: String): List<ToothPlacement> {
        if (value.isEmpty()) return emptyList()

        return value.split("|").map { entry ->
            val parts = entry.split(":")
            ToothPlacement(
                toothId = parts[0],
                finalOffset = PoseOffset(), // Simplified for MVP
                placementTime = parts[2].toLong(),
                status = ToothStatus.valueOf(parts[1])
            )
        }
    }
}





