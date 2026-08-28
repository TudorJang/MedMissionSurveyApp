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
     *
     * Answers are kept between cycles and only the records that can still change are
     * asked about again, so a day's worth of finished studies costs nothing. See
     * [recordsToPoll].
     */
    val xrayStatuses: StateFlow<Map<String, String>> = repository.observeAll()
        .transformLatest { all ->
            val known = mutableMapOf<String, String>()
            while (true) {
                for (record in recordsToPoll(all, known)) {
                    repository.fetchXrayStatus(record)?.let { known[record.recordId] = it }
                }
                emit(known.toMap())
                delay(REFRESH_MILLIS)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private companion object {
        const val REFRESH_MILLIS = 30_000L
    }
}

/** Statuses the console will not move a study out of, so there is nothing left to ask. */
private val SETTLED = setOf("Completed", "Cancelled")

/** How many questions one cycle may ask. See [recordsToPoll]. */
const val MAX_PER_CYCLE = 20

/**
 * The records worth asking the laptop about this cycle, newest first.
 *
 * Every question is its own request and they run one after another, so a laptop that is
 * asleep or off the network costs the full connect timeout for each. Asking about all of a
 * site's ~150 studies made one cycle outlast its own interval, leaving the tablet
 * transmitting without pause on battery, sharing the site's one access point with the
 * survey uploads that actually matter — and the home screen is where the tablet sits
 * between patients, so it did that all day.
 *
 * Two bounds keep it small: a finished study never changes again, and only so many are in
 * flight at once. The newest are the ones an operator is standing at the desk waiting on.
 *
 * A status we do not recognise counts as still moving: guessing that an unfamiliar word
 * means finished would stop asking about a patient who is still in the queue.
 */
fun recordsToPoll(
    all: List<SurveyRecord>,
    known: Map<String, String>,
): List<SurveyRecord> =
    all.asSequence()
        .filter { it.status == SyncStatus.SENT }
        .filter { known[it.recordId] !in SETTLED }
        .sortedByDescending { it.createdAt }
        .take(MAX_PER_CYCLE)
        .toList()
