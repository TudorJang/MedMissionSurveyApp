package com.medmission.survey.ui.laptopselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

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

    suspend fun addManualEndpoint(name: String, host: String, port: Int) {
        laptopEndpointRepository.save(LaptopEndpoint(id = UUID.randomUUID().toString(), name = name, host = host, port = port))
    }

    suspend fun send(laptopId: String): Result<Unit> = surveyRepository.sendToLaptop(recordId, laptopId)
}
