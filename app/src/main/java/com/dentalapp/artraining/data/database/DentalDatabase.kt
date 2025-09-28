package com.dentalapp.artraining.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.dentalapp.artraining.dao.UserPreferencesDao
import com.dentalapp.artraining.dao.ProjectDao
import com.dentalapp.artraining.dao.SessionDao
import com.dentalapp.artraining.data.entities.ProjectEntity
import com.dentalapp.artraining.data.entities.SessionEntity
import com.dentalapp.artraining.entities.UserPreferencesEntity
import com.dentalapp.artraining.data.Converters

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
abstract class DentalDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun userPreferencesDao(): UserPreferencesDao

    companion object {
        @Volatile
        private var INSTANCE: DentalDatabase? = null

        fun getDatabase(context: Context): DentalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DentalDatabase::class.java,
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
