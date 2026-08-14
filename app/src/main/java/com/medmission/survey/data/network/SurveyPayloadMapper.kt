package com.medmission.survey.data.network

import com.medmission.survey.data.model.SurveyRecord

object SurveyPayloadMapper {
    fun toDto(record: SurveyRecord): SurveyPayloadDto = SurveyPayloadDto(
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
            zip = record.zip,
            email = record.email,
            cellPhone = record.cellPhone,
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
