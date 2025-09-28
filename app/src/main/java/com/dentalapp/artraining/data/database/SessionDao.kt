package com.dentalapp.artraining.data.database

import androidx.room.*
import com.dentalapp.artraining.data.TrainingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<TrainingSession>>

    @Query("SELECT * FROM sessions WHERE projectId = :projectId")
    fun getSessionsForProject(projectId: String): Flow<List<TrainingSession>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): TrainingSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TrainingSession)

    @Update
    suspend fun updateSession(session: TrainingSession)

    @Delete
    suspend fun deleteSession(session: TrainingSession)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("SELECT COUNT(*) FROM sessions WHERE endTime IS NOT NULL")
    suspend fun getCompletedSessionCount(): Int

    @Query("SELECT AVG(accuracy) FROM sessions WHERE endTime IS NOT NULL")
    suspend fun getAverageAccuracy(): Double?

    @Query("SELECT * FROM sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentCompletedSessions(limit: Int): List<TrainingSession>

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
