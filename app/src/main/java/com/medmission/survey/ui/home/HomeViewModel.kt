package com.medmission.survey.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

class HomeViewModel(repository: SurveyRepository) : ViewModel() {
    val records: StateFlow<List<SurveyRecord>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * What the X-ray side did with each SENT record, by recordId. Polled while the
     * screen is on show — the bridge cannot push, and the registration desk should not
     * have to walk over to the laptop to learn a patient has been shot.
     */
    val xrayStatuses: StateFlow<Map<String, String>> = repository.observeAll()
        .transformLatest { all ->
            while (true) {
                val statuses = buildMap {
                    for (record in all.filter { it.status == SyncStatus.SENT }) {
                        repository.fetchXrayStatus(record)?.let { put(record.recordId, it) }
                    }
                }
                emit(statuses)
                delay(REFRESH_MILLIS)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private companion object {
        const val REFRESH_MILLIS = 30_000L
    }
}
