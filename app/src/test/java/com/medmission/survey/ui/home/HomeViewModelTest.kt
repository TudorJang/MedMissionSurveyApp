package com.medmission.survey.ui.home

import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Note: HomeViewModel does not sort — ordering is the DAO's "ORDER BY createdAt DESC"
    // and is covered for real in SurveyDaoTest. What matters here is that the ViewModel
    // passes the repository's order through untouched, so feed it deliberately unsorted
    // input and assert the same order comes out.
    @Test
    fun `records passes the repository's order through untouched`() = runTest(testDispatcher) {
        val newest = SurveyRecord(status = SyncStatus.DRAFT, createdAt = 2000L)
        val oldest = SurveyRecord(status = SyncStatus.SENT, createdAt = 1000L)
        val middle = SurveyRecord(status = SyncStatus.PENDING, createdAt = 1500L)
        val unsorted = listOf(middle, oldest, newest)
        val repository: SurveyRepository = mock()
        whenever(repository.observeAll()).thenReturn(flowOf(unsorted))

        val viewModel = HomeViewModel(repository)

        // Keep the shared StateFlow "hot" (WhileSubscribed) while draining pending
        // coroutines so the real stateIn/sharing pipeline actually runs, then assert
        // on the live state rather than a canned collector value.
        val collectorJob = launch { viewModel.records.collect {} }
        advanceUntilIdle()

        assertEquals(unsorted, viewModel.records.first())
        collectorJob.cancel()
    }
}
