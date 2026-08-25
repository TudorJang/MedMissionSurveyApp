package com.medmission.survey.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.isUntouched
import com.medmission.survey.data.repository.SurveyRepository
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.util.formatRecordNo
import com.medmission.survey.util.todayLocalDateString
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class FormViewModel(
    private val repository: SurveyRepository,
    private val recordId: String?,
    private val devicePrefix: String = "0000",
    /** Stamped onto a new record so the payload says where it was collected instead of
     *  leaving a later reader to infer it from a city name. */
    private val country: String? = null,
) : ViewModel() {

    private val _record = MutableStateFlow(
        SurveyRecord(
            recordId = recordId ?: java.util.UUID.randomUUID().toString(),
            country = country,
            // The console substitutes today's date for a worklist item that omits the
            // birth date, which turns every unanswered patient into a newborn without
            // saying so. Showing today here puts the same value in front of the
            // operator, where the age beside it reads 0 and asks to be corrected.
            birthDate = if (recordId == null) todayLocalDateString() else null,
        ),
    )
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
            viewModelScope.launch {
                // No./Date are assigned once, here, from a real DB round-trip — so this
                // merges onto whatever the record looks like *when it resolves* via
                // update{}, rather than overwriting with a value snapshotted before the
                // suspend point. The user could otherwise type into a field while
                // countAll() is in flight and have that edit clobbered.
                val index = repository.countAll() + 1
                val no = formatRecordNo(devicePrefix, index)
                val date = todayLocalDateString()
                _record.update { it.copy(no = no, date = date) }
                repository.saveDraft(_record.value)
            }
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

    /**
     * Leaving the form without entering anything takes the placeholder row with it, so
     * the list does not fill with blanks and the patient numbers stay contiguous. A form
     * with any content in it is left alone — a half-filled survey is somebody's work.
     */
    fun discardIfUntouched(onDone: () -> Unit) {
        viewModelScope.launch {
            // What is on screen, not what reached the database: autosave is debounced,
            // so a name typed a moment ago may not be written yet. Asking the database
            // instead threw away a survey somebody had just started.
            val current = _record.value
            if (current.isUntouched()) {
                repository.discardIfUntouched(current.recordId)
            } else {
                // Leaving early must not lose the keystrokes the debounce still owes.
                repository.saveDraft(current)
            }
            onDone()
        }
    }
}
