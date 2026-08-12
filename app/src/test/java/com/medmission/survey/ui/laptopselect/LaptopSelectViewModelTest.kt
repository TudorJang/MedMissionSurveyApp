package com.medmission.survey.ui.laptopselect

import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop
import com.medmission.survey.data.network.NsdDiscoveryService
import com.medmission.survey.data.repository.LaptopEndpointRepository
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private class FakeNsdDiscoveryService(private val results: List<DiscoveredLaptop>) : NsdDiscoveryService {
    override fun discover(): Flow<List<DiscoveredLaptop>> = flowOf(results)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LaptopSelectViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `savedEndpoints reflects the repository and discovered reflects nsd results`() = runTest(testDispatcher) {
        val saved = LaptopEndpoint(id = "1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080)
        val laptopRepo: LaptopEndpointRepository = mock()
        whenever(laptopRepo.observeAll()).thenReturn(flowOf(listOf(saved)))
        val discovered = DiscoveredLaptop("2번 X-ray실", "192.168.1.20", 8080)
        val nsd = FakeNsdDiscoveryService(listOf(discovered))
        val surveyRepo: SurveyRepository = mock()

        val viewModel = LaptopSelectViewModel(laptopRepo, nsd, surveyRepo, recordId = "record-1")

        val savedCollectorJob = launch { viewModel.savedEndpoints.collect {} }
        val discoveredCollectorJob = launch { viewModel.discovered.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(saved), viewModel.savedEndpoints.first())
        assertEquals(listOf(discovered), viewModel.discovered.first())

        savedCollectorJob.cancel()
        discoveredCollectorJob.cancel()
    }

    @Test
    fun `send delegates to SurveyRepository sendToLaptop with the held recordId`() = runTest(testDispatcher) {
        val laptopRepo: LaptopEndpointRepository = mock()
        whenever(laptopRepo.observeAll()).thenReturn(flowOf(emptyList()))
        val surveyRepo: SurveyRepository = mock()
        whenever(surveyRepo.sendToLaptop("record-1", "laptop-1")).thenReturn(Result.success(Unit))

        val viewModel = LaptopSelectViewModel(laptopRepo, FakeNsdDiscoveryService(emptyList()), surveyRepo, recordId = "record-1")
        val result = viewModel.send("laptop-1")

        assertTrue(result.isSuccess)
        verify(surveyRepo).sendToLaptop("record-1", "laptop-1")
    }
}
