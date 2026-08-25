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
    val region: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
    val zip: String? = null,
    /** ISO 3166-1 alpha-2, carried so a payload says which country it was collected in
     *  rather than leaving every later reader to guess from a city name. */
    val country: String? = null,
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

/**
 * Whether nobody has typed anything into this survey yet.
 *
 * Opening the form writes the row before the first keystroke, so backing out of a form
 * — or an app restart that lands on one — would otherwise leave an empty record behind
 * and burn a patient number. Rather than list the fields to check and forget one later,
 * this compares the record against what it looked like the moment it was created:
 * everything the form assigns by itself is carried over, and if the result is equal,
 * nothing was entered.
 */
fun SurveyRecord.isUntouched(): Boolean = this == SurveyRecord(
    recordId = recordId,
    status = status,
    createdAt = createdAt,
    sentAt = sentAt,
    targetLaptopId = targetLaptopId,
    sendAttempts = sendAttempts,
    no = no,
    date = date,
    // The birth date starts on the creation date, so it counts as untouched only while
    // it still equals it. Anything else means somebody entered a real one, and a record
    // with a real birth date in it is not empty even if nothing else was filled.
    birthDate = date,
    age = if (birthDate == date) age else null,
    country = country,
)
