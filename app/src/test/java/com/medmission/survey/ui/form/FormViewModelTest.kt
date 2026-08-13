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
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.medmission.survey.data.model.SyncStatus

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
        whenever(repository.countAll()).thenReturn(0)
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
        whenever(repository.countAll()).thenReturn(0)
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.updateField { it.copy(firstName = "Juan", gender = Gender.MALE) }
        advanceUntilIdle()

        assertEquals("Juan", viewModel.record.first().firstName)
        assertEquals(Gender.MALE, viewModel.record.first().gender)
        verify(repository).saveDraft(viewModel.record.first())
    }

    @Test
    fun `a brand-new record is persisted immediately, before any field is edited`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(0)
        val viewModel = FormViewModel(repository, recordId = null)
        advanceUntilIdle()

        // Straight to "완료" with no edits must still leave a row for sendToLaptop to find.
        verify(repository).saveDraft(viewModel.record.first())
    }

    @Test
    fun `a brand-new record is assigned an auto-generated No and today's date`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(0)

        val viewModel = FormViewModel(repository, recordId = null, devicePrefix = "A3F2")
        advanceUntilIdle()

        val record = viewModel.record.first()
        assertEquals("TAB-A3F2-0001", record.no)
        assertEquals(com.medmission.survey.util.todayLocalDateString(), record.date)
        verify(repository).saveDraft(record)
    }

    @Test
    fun `the Nth new record on a device gets index N`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(5)

        val viewModel = FormViewModel(repository, recordId = null, devicePrefix = "A3F2")
        advanceUntilIdle()

        assertEquals("TAB-A3F2-0006", viewModel.record.first().no)
    }

    @Test
    fun `loading an existing record does not overwrite it with a blank placeholder`() = runTest(testDispatcher) {
        val existing = SurveyRecord(firstName = "Maria")
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)

        FormViewModel(repository, recordId = existing.recordId)
        advanceUntilIdle()

        verify(repository, never()).saveDraft(any())
    }

    @Test
    fun `a second load does not clobber edits made since the first one`() = runTest(testDispatcher) {
        // The ViewModel survives configuration changes, so the caller's LaunchedEffect
        // re-fires load() on rotation. That must not revert unsaved edits.
        val existing = SurveyRecord(firstName = "Maria")
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)
        val viewModel = FormViewModel(repository, recordId = existing.recordId)
        viewModel.load()
        advanceUntilIdle()

        viewModel.updateField { it.copy(firstName = "Maria Clara") }
        viewModel.load()
        advanceUntilIdle()

        assertEquals("Maria Clara", viewModel.record.first().firstName)
    }

    @Test
    fun `editing a SENT record moves it back to PENDING so it is re-sent`() = runTest(testDispatcher) {
        val existing = SurveyRecord(firstName = "Ana", status = SyncStatus.SENT)
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)
        val viewModel = FormViewModel(repository, recordId = existing.recordId)
        viewModel.load()
        advanceUntilIdle()

        viewModel.updateField { it.copy(firstName = "Ana Maria") }
        advanceUntilIdle()

        val updated = viewModel.record.first()
        assertEquals("Ana Maria", updated.firstName)
        assertEquals(SyncStatus.PENDING, updated.status)
        verify(repository).saveDraft(updated)
    }

    @Test
    fun `re-applying the same value to a SENT record does not demote it to PENDING`() = runTest(testDispatcher) {
        val existing = SurveyRecord(firstName = "Ana", status = SyncStatus.SENT)
        val repository: SurveyRepository = mock()
        whenever(repository.getById(existing.recordId)).thenReturn(existing)
        val viewModel = FormViewModel(repository, recordId = existing.recordId)
        viewModel.load()
        advanceUntilIdle()

        // Re-tapping an already-selected chip or retyping the same text calls
        // updateField with a transform that produces an identical record.
        viewModel.updateField { it.copy(firstName = "Ana") }
        advanceUntilIdle()

        val updated = viewModel.record.first()
        assertEquals(SyncStatus.SENT, updated.status)
        verify(repository, never()).saveDraft(any())
    }

    @Test
    fun `an edit made while load is still in flight is not clobbered by the stale snapshot`() = runTest(testDispatcher) {
        val existing = SurveyRecord(firstName = "Maria")
        val loadGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository: SurveyRepository = mock {
            onBlocking { getById(existing.recordId) } doSuspendableAnswer {
                loadGate.await()
                existing
            }
        }
        val viewModel = FormViewModel(repository, recordId = existing.recordId)

        viewModel.load()
        // The DB read hasn't resolved yet; the user starts typing in the meantime.
        viewModel.updateField { it.copy(firstName = "Maria Clara") }
        loadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("Maria Clara", viewModel.record.first().firstName)
    }

    @Test
    fun `toggling a medical history item adds and removes it from the set`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(0)
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        advanceUntilIdle()
        assertTrue(viewModel.record.first().medicalHistory.contains(MedicalHistoryItem.ASTHMA))

        viewModel.toggleMedicalHistory(MedicalHistoryItem.ASTHMA)
        advanceUntilIdle()
        assertTrue(viewModel.record.first().medicalHistory.isEmpty())
    }

    @Test
    fun `selecting NONE clears any other selected symptoms`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(0)
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.toggleSymptom(com.medmission.survey.data.model.Symptom.COUGH)
        viewModel.toggleSymptom(com.medmission.survey.data.model.Symptom.FEVER)
        viewModel.toggleSymptom(com.medmission.survey.data.model.Symptom.NONE)
        advanceUntilIdle()

        assertEquals(setOf(com.medmission.survey.data.model.Symptom.NONE), viewModel.record.first().symptoms)
    }

    @Test
    fun `selecting another symptom clears a previously selected NONE`() = runTest(testDispatcher) {
        val repository: SurveyRepository = mock()
        whenever(repository.countAll()).thenReturn(0)
        val viewModel = FormViewModel(repository, recordId = null)

        viewModel.toggleSymptom(com.medmission.survey.data.model.Symptom.NONE)
        viewModel.toggleSymptom(com.medmission.survey.data.model.Symptom.COUGH)
        advanceUntilIdle()

        assertEquals(setOf(com.medmission.survey.data.model.Symptom.COUGH), viewModel.record.first().symptoms)
    }
}
