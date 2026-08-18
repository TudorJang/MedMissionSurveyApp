package com.medmission.survey.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

// One row per address: the same laptop added twice would otherwise appear twice, and
// with a key entered on only one of the two cards, sends fail depending on which the
// operator taps.
@Entity(
    tableName = "laptop_endpoints",
    indices = [Index(value = ["host", "port"], unique = true)],
)
data class LaptopEndpoint(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    /** Blank falls back to the key built into the APK — see SurveyRepository. */
    val apiKey: String = "",
    val lastSuccessAt: Long? = null,
)
