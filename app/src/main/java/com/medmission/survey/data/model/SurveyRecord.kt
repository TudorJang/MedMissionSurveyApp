package com.medmission.survey.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "survey_records")
data class SurveyRecord(
    @PrimaryKey val recordId: String = UUID.randomUUID().toString(),
    val status: SyncStatus = SyncStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val targetLaptopId: String? = null,
    val sendAttempts: Int = 0,

    val no: String? = null,
    val date: String? = null,

    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val gender: Gender? = null,
    val age: Int? = null,
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
    val email: String? = null,
    val cellPhone: String? = null,
    val maritalStatus: MaritalStatus? = null,
    val maritalStatusOther: String? = null,

    val medicalHistory: Set<MedicalHistoryItem> = emptySet(),
    val medicalHistoryOthers: String? = null,
    val recentSurgeriesOrHospitalization: String? = null,
    val currentMedication: String? = null,

    val height: Double? = null,
    val weight: Double? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val pulseRate: Int? = null,
    val respiratoryRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Double? = null,
    val bloodGlucose: Double? = null,

    val symptoms: Set<Symptom> = emptySet(),

    val everDiagnosedTB: YesNoUnknown? = null,
    val diagnosisYear: String? = null,
    val everReceivedTreatment: YesNoUnknown? = null,
    val treatmentCompleted: YesNoUnknown? = null,
    val closeContactActiveTB: YesNoUnknown? = null,
    val closeContactWhen: String? = null,
    val householdMemberTBTreatment: YesNoUnknown? = null,

    val smokingStatus: SmokingStatus? = null,
    val smokingDuration: SmokingDuration? = null,
    val drinksAlcohol: Boolean? = null,
    val alcoholAmount: AlcoholAmount? = null,

    val dustSmokeChemicalExposure: Boolean? = null,
    val cooksWithSolidFuels: Boolean? = null,
    val secondhandSmokeExposure: Boolean? = null,
    val crowdedLivingConditions: Boolean? = null,
)
