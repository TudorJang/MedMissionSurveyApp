package com.medmission.survey.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.model.SurveyRecord

@Database(
    entities = [SurveyRecord::class, LaptopEndpoint::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun laptopEndpointDao(): LaptopEndpointDao
}
