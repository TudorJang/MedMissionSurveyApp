package com.medmission.survey.ui.form

import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FormViewModelTest {

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
    fun `starting with no recordId begins a fresh DRAFT record`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        val record = viewModel.record.first()

        assertTrue(record.firstName == null)
        assertEquals(com.medmission.survey.data.model.SyncStatus.DRAFT, record.status)
    }

    @Test
    fun `starting with an existing recordId loads it from the repository`() = runTest(testDispatcher) {
        val existing = SurveyRecord(firstName = "Maria")
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)

        val viewModel = FormViewModel(repository, recordId = existing.recordId)
        viewModel.load()
        advanceUntilIdle()

        assertEquals("Maria", viewModel.record.first().firstName)
    }

    @Test
    fun `updateField mutates the record and persists a draft`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.updateField { it.copy(firstName = "Juan", gender = Gender.MALE) }
        advanceUntilIdle()

        assertEquals("Juan", viewModel.record.first().firstName)
        assertEquals(Gender.MALE, viewModel.record.first().gender)
        verify(repository).saveDraft(viewModel.record.first())
    }

    @Test
    fun `toggling a medical history item adds and removes it from the set`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        advanceUntilIdle()
        assertTrue(viewModel.record.first().medicalHistory.contains(MedicalHistoryItem.ASTHMA))

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        advanceUntilIdle()
        assertTrue(viewModel.record.first().medicalHistory.isEmpty())
    }
}
