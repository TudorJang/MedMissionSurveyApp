package com.medmission.survey.data.network

import kotlinx.serialization.Serializable

@Serializable
data class SurveyPayloadDto(
    val recordId: String,
    val no: String? = null,
    val date: String? = null,
    val patient: PatientDto,
    val medicalHistory: MedicalHistoryDto,
    val vitalSigns: VitalSignsDto,
    val symptoms: List<String>,
    val tbInfo: TbInfoDto,
    val smoking: SmokingDto,
    val alcohol: AlcoholDto,
    val environmentalExposure: EnvironmentalExposureDto,
)

@Serializable
data class PatientDto(
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
    val email: String? = null,
    val cellPhone: String? = null,
    val maritalStatus: String? = null,
)

@Serializable
data class MedicalHistoryDto(
    val items: List<String> = emptyList(),
    val others: String? = null,
    val recentSurgeriesOrHospitalization: String? = null,
    val currentMedication: String? = null,
)

@Serializable
data class VitalSignsDto(
    val height: Double? = null,
    val weight: Double? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val pulseRate: Int? = null,
    val respiratoryRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Double? = null,
    val bloodGlucose: Double? = null,
)

@Serializable
data class TbInfoDto(
    val everDiagnosedTB: String? = null,
    val diagnosisYear: String? = null,
    val everReceivedTreatment: String? = null,
    val treatmentCompleted: String? = null,
    val closeContactActiveTB: String? = null,
    val closeContactWhen: String? = null,
    val householdMemberTBTreatment: String? = null,
)

@Serializable
data class SmokingDto(
    val status: String? = null,
    val duration: String? = null,
)

@Serializable
data class AlcoholDto(
    val drinks: Boolean? = null,
    val amount: String? = null,
)

@Serializable
data class EnvironmentalExposureDto(
    val dustSmokeChemicalExposure: Boolean? = null,
    val cooksWithSolidFuels: Boolean? = null,
    val secondhandSmokeExposure: Boolean? = null,
    val crowdedLivingConditions: Boolean? = null,
)
