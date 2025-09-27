package com.dentalapp.artraining.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.dentalapp.artraining.data.dao.ProjectDao
import com.dentalapp.artraining.data.dao.SessionDao
import com.dentalapp.artraining.data.dao.UserPreferencesDao
import com.dentalapp.artraining.data.entities.ProjectEntity
import com.dentalapp.artraining.data.entities.SessionEntity
import com.dentalapp.artraining.data.entities.UserPreferencesEntity

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        UserPreferencesEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun userPreferencesDao(): UserPreferencesDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dental_ar_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}