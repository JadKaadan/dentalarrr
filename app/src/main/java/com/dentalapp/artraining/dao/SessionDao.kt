package com.dentalapp.artraining.dao

import androidx.room.*
import com.dentalapp.artraining.data.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY startTime DESC")
    fun getSessionsForProject(projectId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: String): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("SELECT COUNT(*) FROM sessions WHERE endTime IS NOT NULL")
    suspend fun getCompletedSessionCount(): Int

    @Query("SELECT AVG(accuracy) FROM sessions WHERE endTime IS NOT NULL AND accuracy > 0")
    suspend fun getAverageAccuracy(): Double?

    @Query("SELECT * FROM sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentCompletedSessions(limit: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun getSessionCount(): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY startTime DESC")
    fun getSessionsForUser(userId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startTime >= :startTime AND endTime <= :endTime")
    fun getSessionsInDateRange(startTime: Long, endTime: Long): Flow<List<SessionEntity>>
}
