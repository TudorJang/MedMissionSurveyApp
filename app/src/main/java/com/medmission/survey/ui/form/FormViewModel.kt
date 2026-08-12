package com.medmission.survey.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import com.medmission.survey.data.model.SyncStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class FormViewModel(
    private val repository: SurveyRepository,
    private val recordId: String?,
) : ViewModel() {

    private val _record = MutableStateFlow(SurveyRecord(recordId = recordId ?: java.util.UUID.randomUUID().toString()))
    val record: StateFlow<SurveyRecord> = _record.asStateFlow()

    // Autosave requests. replay = 1 so an edit made before the collector below starts
    // is not lost; DROP_OLDEST because only the newest snapshot of the record matters.
    private val autosaveRequests = MutableSharedFlow<SurveyRecord>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // A brand-new survey must exist in the DB before the user ever edits a field:
        // otherwise a straight-to-"완료" record is never persisted and sendToLaptop
        // can't find it. Only for genuinely new records — an existing recordId is
        // loaded by load() and must not be overwritten with this placeholder.
        if (recordId == null) {
            // Capture the value now, not inside the coroutine: by the time the
            // coroutine actually runs the user may already have edited a field,
            // and we'd redundantly re-save the newer value.
            val initial = _record.value
            viewModelScope.launch { repository.saveDraft(initial) }
        }

        // Single serialized writer for autosave. Without this, updateField launched a
        // coroutine per keystroke and Room's @Upsert can run those on its multi-threaded
        // query executor, so rapid edits could land out of order.
        viewModelScope.launch {
            autosaveRequests
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .collectLatest { repository.saveDraft(it) }
        }
    }

    fun load() {
        val id = recordId ?: return
        viewModelScope.launch {
            repository.getById(id)?.let { _record.value = it }
        }
    }

    fun updateField(transform: (SurveyRecord) -> SurveyRecord) {
        val transformed = transform(_record.value)
        // Editing an already-SENT record makes the tablet's copy diverge from what the
        // bridge holds. Move it back to PENDING so the retry worker re-sends it; the
        // bridge upserts by recordId, so re-sending is safe.
        val updated =
            if (transformed.status == SyncStatus.SENT) transformed.copy(status = SyncStatus.PENDING)
            else transformed
        // The UI reads _record directly, so it stays instant; only the DB write debounces.
        _record.value = updated
        autosaveRequests.tryEmit(updated)
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

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 300L
    }
}
