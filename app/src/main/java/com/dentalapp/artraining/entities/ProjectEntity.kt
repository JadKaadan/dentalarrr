package com.dentalapp.artraining.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dentalapp.artraining.data.*

// This mirrors the domain model + includes lastAccessed
@Entity(tableName = "projects")
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
    val lastAccessed: Long = System.currentTimeMillis()
) {
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
                lastAccessed = project.lastAccessed
            )
        }
    }
}

