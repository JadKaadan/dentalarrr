package com.dentalapp.artraining.data.repository

import android.content.Context
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.TrainingSession
import com.dentalapp.artraining.data.database.AppDatabase
import com.dentalapp.artraining.data.entities.*
import com.dentalapp.artraining.data.models.*
import com.dentalapp.artraining.network.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DentalRepository(context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val projectDao = database.projectDao()
    private val sessionDao = database.sessionDao()
    private val userPreferencesDao = database.userPreferencesDao()
    private val apiService = ApiService()

    // Project operations
    fun getAllProjects(): Flow<List<Project>> {
        return projectDao.getAllProjects().map { entities ->
            entities.map { it.toProject() }
        }
    }

    suspend fun getProject(projectId: String): Project? {
        // Try local first
        val localProject = projectDao.getProjectById(projectId)
        if (localProject != null) {
            projectDao.updateLastAccessed(projectId)
            return localProject.toProject()
        }

        // Fallback to API
        return try {
            val apiProject = apiService.getProject(projectId)
            // Cache it locally
            projectDao.insertProject(ProjectEntity.fromProject(apiProject))
            apiProject
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveProject(project: Project) {
        projectDao.insertProject(ProjectEntity.fromProject(project))
    }

    suspend fun deleteProject(projectId: String) {
        projectDao.deleteProjectById(projectId)
    }

    // Session operations
    fun getSessionsForProject(projectId: String): Flow<List<TrainingSession>> {
        return sessionDao.getSessionsForProject(projectId).map { entities ->
            entities.map { it.toTrainingSession() }
        }
    }

    suspend fun saveSession(session: TrainingSession) {
        sessionDao.insertSession(SessionEntity.fromTrainingSession(session))
    }

    suspend fun getSessionStats(): SessionStats {
        val totalSessions = sessionDao.getCompletedSessionCount()
        val averageAccuracy = sessionDao.getAverageAccuracy() ?: 0.0
        val recentSessions = sessionDao.getRecentCompletedSessions(5)

        return SessionStats(
            totalSessions = totalSessions,
            averageAccuracy = averageAccuracy,
            recentSessions = recentSessions.map { it.toTrainingSession() }
        )
    }

    // User preferences
    suspend fun getUserPreferences(userId: String): UserPreferencesEntity {
        return userPreferencesDao.getUserPreferences(userId)
            ?: UserPreferencesEntity(userId = userId)
    }

    suspend fun updateUserPreferences(preferences: UserPreferencesEntity) {
        userPreferencesDao.insertUserPreferences(preferences)
    }

    suspend fun updateLastProject(userId: String, projectId: String) {
        userPreferencesDao.updateLastProject(userId, projectId)
        userPreferencesDao.incrementSessionCount(userId)
    }
}

data class SessionStats(
    val totalSessions: Int,
    val averageAccuracy: Double,
    val recentSessions: List<TrainingSession>
)