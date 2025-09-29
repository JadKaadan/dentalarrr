package com.dentalapp.artraining.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dentalapp.artraining.data.*

// This entity is for caching projects with additional metadata
@Entity(tableName = "project_cache")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val archType: String,
    val tolerance: Tolerance,
    val qrCode: String,
    val enabledTeeth: List<String>,
    val idealPoses: Map<String, Pose3D>,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val lastAccessed: Long = System.currentTimeMillis()  // This is for caching purposes only
) {
    fun toProject(): Project {
        return Project(
            id = id,
            name = name,
            description = description,
            archType = archType,
            tolerance = tolerance,
            qrCode = qrCode,
            enabledTeeth = enabledTeeth,
            idealPoses = idealPoses,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version
        )
    }

    companion object {
        fun fromProject(project: Project): ProjectEntity {
            return ProjectEntity(
                id = project.id,
                name = project.name,
                description = project.description,
                archType = project.archType,
                tolerance = project.tolerance,
                qrCode = project.qrCode,
                enabledTeeth = project.enabledTeeth,
                idealPoses = project.idealPoses,
                createdAt = project.createdAt,
                updatedAt = project.updatedAt,
                version = project.version,
                lastAccessed = System.currentTimeMillis()
            )
        }
    }
}
