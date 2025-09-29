    package com.dentalapp.artraining.dao

    import androidx.room.*
    import com.dentalapp.artraining.data.entities.UserPreferencesEntity
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface UserPreferencesDao {

        @Query("SELECT * FROM user_preferences WHERE userId = :userId")
        suspend fun getUserPreferences(userId: String): UserPreferencesEntity?

        @Query("SELECT * FROM user_preferences WHERE userId = :userId")
        fun getUserPreferencesFlow(userId: String): Flow<UserPreferencesEntity?>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insertUserPreferences(preferences: UserPreferencesEntity)

        @Update
        suspend fun updateUserPreferences(preferences: UserPreferencesEntity)

        @Delete
        suspend fun deleteUserPreferences(preferences: UserPreferencesEntity)

        @Query("DELETE FROM user_preferences WHERE userId = :userId")
        suspend fun deleteUserPreferencesById(userId: String)

        @Query("UPDATE user_preferences SET lastProjectId = :projectId WHERE userId = :userId")
        suspend fun updateLastProject(userId: String, projectId: String)

        @Query("UPDATE user_preferences SET sessionCount = sessionCount + 1 WHERE userId = :userId")
        suspend fun incrementSessionCount(userId: String)

        @Query("UPDATE user_preferences SET lastAccessTime = :timestamp WHERE userId = :userId")
        suspend fun updateLastAccessTime(userId: String, timestamp: Long = System.currentTimeMillis())

        @Query("SELECT * FROM user_preferences")
        fun getAllUserPreferences(): Flow<List<UserPreferencesEntity>>

        @Query("DELETE FROM user_preferences")
        suspend fun deleteAllUserPreferences()
    }
