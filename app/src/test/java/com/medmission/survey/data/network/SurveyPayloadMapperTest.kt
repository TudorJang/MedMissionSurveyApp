package com.medmission.survey.data.network

import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.YesNoUnknown
import kotlinx.serialization.json.Json
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
    fun `serializes a fully-empty record to JSON without throwing`() {
        val dto = SurveyPayloadMapper.toDto(SurveyRecord())
        val json = Json.encodeToString(SurveyPayloadDto.serializer(), dto)

        assertEquals(true, json.contains("\"recordId\""))
    }
}
