package com.medmission.survey.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FormViewModel(
    private val repository: SurveyRepository,
    private val recordId: String?,
) : ViewModel() {

    private val _record = MutableStateFlow(SurveyRecord(recordId = recordId ?: java.util.UUID.randomUUID().toString()))
    val record: StateFlow<SurveyRecord> = _record.asStateFlow()

    fun load() {
        val id = recordId ?: return
        viewModelScope.launch {
            repository.getById(id)?.let { _record.value = it }
        }
    }

    fun updateField(transform: (SurveyRecord) -> SurveyRecord) {
        val updated = transform(_record.value)
        _record.value = updated
        viewModelScope.launch { repository.saveDraft(updated) }
    }

    fun toggleMedicalHistory(item: MedicalHistoryItem) {
        updateField { record ->
            val set = record.medicalHistory
            record.copy(medicalHistory = if (item in set) set - item else set + item)
        }
    }

    fun toggleSymptom(symptom: Symptom) {
        updateField { record ->
            val set = record.symptoms
            record.copy(symptoms = if (symptom in set) set - symptom else set + symptom)
        }
    }
}
