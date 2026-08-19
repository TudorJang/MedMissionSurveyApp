package com.medmission.survey.ui.laptopselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Where a survey already landed, when it did. Null while unknown or when it never has. */
data class PriorSend(val laptopId: String, val laptopName: String?)

class LaptopSelectViewModel(
    private val laptopEndpointRepository: LaptopEndpointRepository,
    nsdDiscoveryService: NsdDiscoveryService,
    private val surveyRepository: SurveyRepository,
    private val recordId: String,
) : ViewModel() {

    val savedEndpoints: StateFlow<List<LaptopEndpoint>> = laptopEndpointRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discovered: StateFlow<List<DiscoveredLaptop>> = nsdDiscoveryService.discover()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * A survey that already reached one laptop and is sent to a second one appears on
     * both consoles' worklists, and nothing on either laptop can see the other. The
     * screen uses this to ask before letting that happen.
     */
    val priorSend: StateFlow<PriorSend?> = flow {
        val record = surveyRepository.getById(recordId)
        val laptopId = record?.targetLaptopId
        emit(
            if (record?.status == SyncStatus.SENT && laptopId != null)
                PriorSend(laptopId, laptopEndpointRepository.getById(laptopId)?.name?.ifBlank { null })
            else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun addManualEndpoint(name: String, host: String, port: Int, apiKey: String = "") {
        laptopEndpointRepository.addOrUpdate(name, host, port, apiKey)
    }

    suspend fun updateApiKey(laptopId: String, apiKey: String) =
        laptopEndpointRepository.updateApiKey(laptopId, apiKey)

    suspend fun send(laptopId: String): Result<Unit> = surveyRepository.sendToLaptop(recordId, laptopId)
}
