package com.medmission.survey.data.network

import com.medmission.survey.data.model.AlcoholAmount
import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MaritalStatus
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SmokingDuration
import com.medmission.survey.data.model.SmokingStatus
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.model.YesNoUnknown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyPayloadMapperTest {
    @Test
    fun `maps patient, medical history, symptoms and tb info onto the dto`() {
        val record = SurveyRecord(
            firstName = "Juan",
            lastName = "Dela Cruz",
            gender = Gender.MALE,
            medicalHistory = setOf(MedicalHistoryItem.ASTHMA),
            medicalHistoryOthers = "Migraine",
            symptoms = setOf(Symptom.COUGH, Symptom.FEVER),
            everDiagnosedTB = YesNoUnknown.NO,
        )

        val dto = SurveyPayloadMapper.toDto(record)

        assertEquals(record.recordId, dto.recordId)
        assertEquals("Juan", dto.patient.firstName)
        assertEquals("MALE", dto.patient.gender)
        assertEquals(listOf("ASTHMA"), dto.medicalHistory.items)
        assertEquals("Migraine", dto.medicalHistory.others)
        assertEquals(setOf("COUGH", "FEVER"), dto.symptoms.toSet())
        assertEquals("NO", dto.tbInfo.everDiagnosedTB)
    }

    @Test
    fun `a fully-populated record survives a real JSON round trip with the expected structure`() {
        val record = SurveyRecord(
            status = SyncStatus.PENDING,
            no = "MM-001",
            date = "2026-08-12",
            firstName = "Juan",
            lastName = "Dela Cruz",
            birthDate = "1980-03-04",
            gender = Gender.MALE,
            age = 46,
            address = "12 Mabini St",
            region = "NATIONAL CAPITAL REGION (NCR)",
            province = null,
            city = "Manila",
            barangay = "Ermita",
            zip = "1000",
            email = "juan@example.com",
            cellPhone = "+63-900-000-0000",
            maritalStatus = MaritalStatus.OTHER,
            maritalStatusOther = "Domestic partnership",
            medicalHistory = setOf(MedicalHistoryItem.ASTHMA, MedicalHistoryItem.DIABETES),
            medicalHistoryOthers = "Migraine",
            recentSurgeriesOrHospitalization = "Appendectomy 2019",
            currentMedication = "Salbutamol",
            height = 170.5,
            weight = 68.2,
            bpSystolic = 128,
            bpDiastolic = 82,
            pulseRate = 76,
            respiratoryRate = 18,
            temperature = 36.8,
            oxygenSaturation = 98.0,
            bloodGlucose = 5.4,
            symptoms = setOf(Symptom.COUGH, Symptom.NIGHT_SWEATS),
            everDiagnosedTB = YesNoUnknown.YES,
            diagnosisYear = "2015",
            everReceivedTreatment = YesNoUnknown.YES,
            treatmentCompleted = YesNoUnknown.NO,
            closeContactActiveTB = YesNoUnknown.DONT_KNOW,
            closeContactWhen = "2024",
            householdMemberTBTreatment = YesNoUnknown.NO,
            smokingStatus = SmokingStatus.FORMER,
            smokingDuration = SmokingDuration.FIVE_TO_10,
            drinksAlcohol = true,
            alcoholAmount = AlcoholAmount.ONE_TO_TWO,
            dustSmokeChemicalExposure = true,
            cooksWithSolidFuels = false,
            secondhandSmokeExposure = true,
            crowdedLivingConditions = false,
        )

        val dto = SurveyPayloadMapper.toDto(record)
        val json = Json.encodeToString(SurveyPayloadDto.serializer(), dto)
        val decoded = Json.decodeFromString(SurveyPayloadDto.serializer(), json)

        // The whole payload survives encode -> decode unchanged.
        assertEquals(dto, decoded)

        // ...and every top-level group actually lands where the bridge expects it,
        // rather than being flattened or renamed by the serializer.
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(
            setOf(
                "recordId", "no", "date", "patient", "medicalHistory", "vitalSigns",
                "symptoms", "tbInfo", "smoking", "alcohol", "environmentalExposure",
            ),
            root.keys,
        )
        assertEquals(record.recordId, root.getValue("recordId").jsonPrimitive.content)
        assertEquals("MM-001", root.getValue("no").jsonPrimitive.content)
        assertEquals("2026-08-12", root.getValue("date").jsonPrimitive.content)

        val patient = root.getValue("patient").jsonObject
        assertEquals("Juan", patient.getValue("firstName").jsonPrimitive.content)
        assertEquals("Dela Cruz", patient.getValue("lastName").jsonPrimitive.content)
        assertEquals("1980-03-04", patient.getValue("birthDate").jsonPrimitive.content)
        assertEquals("MALE", patient.getValue("gender").jsonPrimitive.content)
        assertEquals(46, patient.getValue("age").jsonPrimitive.int)
        assertEquals("OTHER", patient.getValue("maritalStatus").jsonPrimitive.content)
        assertEquals("Domestic partnership", patient.getValue("maritalStatusOther").jsonPrimitive.content)
        assertEquals("NATIONAL CAPITAL REGION (NCR)", patient.getValue("region").jsonPrimitive.content)
        assertEquals("Ermita", patient.getValue("barangay").jsonPrimitive.content)
        assertEquals(false, patient.containsKey("province")) // null fields are absent, not null (see wire-contract.md §2)

        val medicalHistory = root.getValue("medicalHistory").jsonObject
        assertEquals(
            setOf("ASTHMA", "DIABETES"),
            medicalHistory.getValue("items").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals("Migraine", medicalHistory.getValue("others").jsonPrimitive.content)
        assertEquals(
            "Appendectomy 2019",
            medicalHistory.getValue("recentSurgeriesOrHospitalization").jsonPrimitive.content,
        )

        val vitalSigns = root.getValue("vitalSigns").jsonObject
        assertEquals(170.5, vitalSigns.getValue("height").jsonPrimitive.double, 0.001)
        assertEquals(128, vitalSigns.getValue("bpSystolic").jsonPrimitive.int)
        assertEquals(98.0, vitalSigns.getValue("oxygenSaturation").jsonPrimitive.double, 0.001)

        // symptoms is a flat array of enum names, not an object.
        assertEquals(
            setOf("COUGH", "NIGHT_SWEATS"),
            root.getValue("symptoms").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val tbInfo = root.getValue("tbInfo").jsonObject
        assertEquals("YES", tbInfo.getValue("everDiagnosedTB").jsonPrimitive.content)
        assertEquals("DONT_KNOW", tbInfo.getValue("closeContactActiveTB").jsonPrimitive.content)
        assertEquals("2015", tbInfo.getValue("diagnosisYear").jsonPrimitive.content)

        val smoking = root.getValue("smoking").jsonObject
        assertEquals("FORMER", smoking.getValue("status").jsonPrimitive.content)
        assertEquals("FIVE_TO_10", smoking.getValue("duration").jsonPrimitive.content)

        val alcohol = root.getValue("alcohol").jsonObject
        assertEquals(true, alcohol.getValue("drinks").jsonPrimitive.boolean)
        assertEquals("ONE_TO_TWO", alcohol.getValue("amount").jsonPrimitive.content)

        val exposure = root.getValue("environmentalExposure").jsonObject
        assertEquals(true, exposure.getValue("dustSmokeChemicalExposure").jsonPrimitive.boolean)
        assertEquals(false, exposure.getValue("cooksWithSolidFuels").jsonPrimitive.boolean)
        assertEquals(true, exposure.getValue("secondhandSmokeExposure").jsonPrimitive.boolean)
        assertEquals(false, exposure.getValue("crowdedLivingConditions").jsonPrimitive.boolean)

        // Local-only sync bookkeeping must never reach the wire. Note "status" does
        // exist on the wire under smoking.status, so check the root key specifically.
        assertEquals(false, json.contains("sendAttempts"))
        assertEquals(false, json.contains("targetLaptopId"))
        assertEquals(false, json.contains("sentAt"))
        assertEquals(false, json.contains("createdAt"))
        assertEquals(false, root.containsKey("status"))
        assertEquals(false, json.contains("PENDING"))
    }

    @Test
    fun `serializes a fully-empty record to JSON without throwing`() {
        val record = SurveyRecord()
        val dto = SurveyPayloadMapper.toDto(record)
        val json = Json.encodeToString(SurveyPayloadDto.serializer(), dto)

        // Contract note for the bridge: kotlinx.serialization does not encode defaults,
        // so unanswered fields are ABSENT rather than present-and-null. The nested
        // group objects and the symptoms array are always present. Documented in
        // docs/reference/wire-contract.md.
        assertEquals(
            """{"recordId":"${record.recordId}","patient":{},"medicalHistory":{},""" +
                """"vitalSigns":{},"symptoms":[],"tbInfo":{},"smoking":{},"alcohol":{},""" +
                """"environmentalExposure":{}}""",
            json,
        )
    }
}
