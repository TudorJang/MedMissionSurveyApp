package com.medmission.survey.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "laptop_endpoints")
data class LaptopEndpoint(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    /** Blank falls back to the key built into the APK — see SurveyRepository. */
    val apiKey: String = "",
    val lastSuccessAt: Long? = null,
)
