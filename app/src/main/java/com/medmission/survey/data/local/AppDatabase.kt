package com.medmission.survey.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord

@Database(
    entities = [SurveyRecord::class, LaptopEndpoint::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun laptopEndpointDao(): LaptopEndpointDao

    companion object {
        /**
         * Adds the per-laptop API key. Blank means "use the key built into the APK",
         * which is exactly how endpoints saved before this version behaved, so every
         * existing row keeps working and no survey waiting to be sent is lost.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE laptop_endpoints ADD COLUMN apiKey TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
