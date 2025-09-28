package com.dentalapp.artraining.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey
    val userId: String,
    val lastProjectId: String? = null,
    val sessionCount: Int = 0,
    val lastAccessTime: Long = System.currentTimeMillis(),
    val preferredTolerancePositionMm: Float = 1.5f,
    val preferredToleranceRotationDeg: Float = 3.0f,
    val enableHapticFeedback: Boolean = true,
    val enableAudioGuidance: Boolean = true,
    val showAdvancedMetrics: Boolean = false,
    val autoExportReports: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
