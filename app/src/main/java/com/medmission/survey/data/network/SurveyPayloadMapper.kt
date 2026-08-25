package com.medmission.survey.data.network

import com.medmission.survey.data.model.SurveyRecord

object SurveyPayloadMapper {
    /**
     * @param normalisePhone turns what the operator typed into the one form every later
     * system agrees on, E.164. The field itself keeps the national grouping the country
     * writes, because that is what an operator checks against a piece of paper — the
     * conversion belongs at the edge, where the payload is built.
     */
    fun toDto(
        record: SurveyRecord,
        normalisePhone: (String, String?) -> String? = { typed, _ -> typed },
    ): SurveyPayloadDto = SurveyPayloadDto(
        recordId = record.recordId,
        no = record.no,
        date = record.date,
        patient = PatientDto(
            firstName = record.firstName,
            lastName = record.lastName,
            birthDate = record.birthDate,
            gender = record.gender?.name,
            age = record.age,
            address = record.address,
            region = record.region,
            province = record.province,
            city = record.city,
            barangay = record.barangay,
            country = record.country,
            zip = record.zip,
            email = record.email,
            cellPhone = record.cellPhone?.let { normalisePhone(it, record.country) } ?: record.cellPhone,
            maritalStatus = record.maritalStatus?.name,
            maritalStatusOther = record.maritalStatusOther,
        ),
        medicalHistory = MedicalHistoryDto(
            items = record.medicalHistory.map { it.name },
            others = record.medicalHistoryOthers,
            recentSurgeriesOrHospitalization = record.recentSurgeriesOrHospitalization,
            currentMedication = record.currentMedication,
        ),
        vitalSigns = VitalSignsDto(
            height = record.height,
            weight = record.weight,
            bpSystolic = record.bpSystolic,
            bpDiastolic = record.bpDiastolic,
            pulseRate = record.pulseRate,
            respiratoryRate = record.respiratoryRate,
            temperature = record.temperature,
            oxygenSaturation = record.oxygenSaturation,
            bloodGlucose = record.bloodGlucose,
        ),
        symptoms = record.symptoms.map { it.name },
        tbInfo = TbInfoDto(
            everDiagnosedTB = record.everDiagnosedTB?.name,
            diagnosisYear = record.diagnosisYear,
            everReceivedTreatment = record.everReceivedTreatment?.name,
            treatmentCompleted = record.treatmentCompleted?.name,
            closeContactActiveTB = record.closeContactActiveTB?.name,
            closeContactWhen = record.closeContactWhen,
            householdMemberTBTreatment = record.householdMemberTBTreatment?.name,
        ),
        smoking = SmokingDto(
            status = record.smokingStatus?.name,
            duration = record.smokingDuration?.name,
        ),
        alcohol = AlcoholDto(
            drinks = record.drinksAlcohol,
            amount = record.alcoholAmount?.name,
        ),
        environmentalExposure = EnvironmentalExposureDto(
            dustSmokeChemicalExposure = record.dustSmokeChemicalExposure,
            cooksWithSolidFuels = record.cooksWithSolidFuels,
            secondhandSmokeExposure = record.secondhandSmokeExposure,
            crowdedLivingConditions = record.crowdedLivingConditions,
        ),
    )
}
