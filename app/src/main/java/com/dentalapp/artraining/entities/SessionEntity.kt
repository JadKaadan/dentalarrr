package com.dentalapp.artraining.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dentalapp.artraining.data.ToothPlacement
import com.dentalapp.artraining.data.TrainingSession

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val projectId: String,
    val userId: String,
    val deviceInfo: String,
    val startTime: Long,
    val endTime: Long? = null,
    val completedTeeth: List<ToothPlacement> = emptyList(),
    val accuracy: Float = 0f,
    val totalTime: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isUploaded: Boolean = false
) {
    fun toTrainingSession(): TrainingSession {
        return TrainingSession(
            id = id,
            projectId = projectId,
            userId = userId,
            deviceInfo = deviceInfo,
            startTime = startTime,
            endTime = endTime,
            completedTeeth = completedTeeth,
            accuracy = accuracy,
            totalTime = totalTime
        )
    }

    companion object {
        fun fromTrainingSession(session: TrainingSession): SessionEntity {
            return SessionEntity(
                id = session.id,
                projectId = session.projectId,
                userId = session.userId,
                deviceInfo = session.deviceInfo,
                startTime = session.startTime,
                endTime = session.endTime,
                completedTeeth = session.completedTeeth,
                accuracy = session.accuracy,
                totalTime = session.totalTime
            )
        }
    }
}
