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
    val lastSuccessAt: Long? = null,
)
