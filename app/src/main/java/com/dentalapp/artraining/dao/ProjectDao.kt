package com.dentalapp.artraining.dao

import androidx.room.*
import com.dentalapp.artraining.data.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastAccessed DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    // Touch lastAccessed (= now)
    @Query("UPDATE projects SET lastAccessed = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = :id")
    suspend fun updateLastAccessed(id: String)
}
