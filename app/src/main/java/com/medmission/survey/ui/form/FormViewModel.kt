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
        // otherwise a straight-to-"Done" record is never persisted and sendToLaptop
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

    private var hasLoaded = false

    // Bumped on every genuine field edit. Lets load()'s async DB read detect whether the
    // user started editing while it was in flight, so it doesn't clobber those edits with
    // the pre-edit snapshot it fetched.
    private var editVersion = 0

    /**
     * Loads the stored record once. Idempotent on purpose: the ViewModel now outlives
     * configuration changes, so the caller's LaunchedEffect re-fires on rotation and a
     * second load would overwrite edits the user made since the last autosave.
     */
    fun load() {
        val id = recordId ?: return
        if (hasLoaded) return
        hasLoaded = true
        val versionAtLoadStart = editVersion
        viewModelScope.launch {
            repository.getById(id)?.let {
                // If the user already edited a field while this load was in flight, the
                // fetched row is a stale pre-edit snapshot; applying it would silently
                // discard what they just typed.
                if (editVersion == versionAtLoadStart) _record.value = it
            }
        }
    }

    fun updateField(transform: (SurveyRecord) -> SurveyRecord) {
        val current = _record.value
        val transformed = transform(current)
        if (transformed == current) return
        editVersion++
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
            val toggled = if (symptom in set) set - symptom else set + symptom
            // NONE and any other symptom together is a contradiction the paper form can't
            // express, so selecting one side clears the other rather than blocking the tap.
            val resolved = when {
                symptom == Symptom.NONE && symptom in toggled -> setOf(Symptom.NONE)
                symptom != Symptom.NONE && symptom in toggled -> toggled - Symptom.NONE
                else -> toggled
            }
            record.copy(symptoms = resolved)
        }
    }

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 300L
    }
}
