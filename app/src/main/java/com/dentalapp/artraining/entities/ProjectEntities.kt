package com.dentalapp.artraining.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dentalapp.artraining.data.Pose3D
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.Tolerance
import com.dentalapp.artraining.data.models.*

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val enabledTeeth: List<String>,
    val idealPoses: Map<String, Pose3D>,
    val tolerance: Tolerance,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
    val isDownloaded: Boolean = true,
    val lastAccessedAt: Long = System.currentTimeMillis()
) {
    fun toProject(): Project {
        return Project(
            id = id,
            name = name,
            description = description,
            enabledTeeth = enabledTeeth,
            idealPoses = idealPoses,
            tolerance = tolerance,
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
                enabledTeeth = project.enabledTeeth,
                idealPoses = project.idealPoses,
                tolerance = project.tolerance,
                createdAt = project.createdAt,
                updatedAt = project.updatedAt,
                version = project.version
            )
        }
    }
}