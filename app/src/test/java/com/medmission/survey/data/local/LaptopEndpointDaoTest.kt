package com.medmission.survey.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaptopEndpointDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LaptopEndpointDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.laptopEndpointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then observeAll returns endpoints sorted by name`() = runBlocking {
        dao.upsert(LaptopEndpoint(id = "2", name = "2번 X-ray실", host = "192.168.1.20", port = 8080))
        dao.upsert(LaptopEndpoint(id = "1", name = "1번 X-ray실", host = "192.168.1.10", port = 8080))

        val all = dao.observeAll().first()

        assertEquals(listOf("1번 X-ray실", "2번 X-ray실"), all.map { it.name })
    }

    @Test
    fun `getById finds a saved endpoint by id`() = runBlocking {
        val endpoint = LaptopEndpoint(id = "3", name = "3번 X-ray실", host = "192.168.1.30", port = 9090)
        dao.upsert(endpoint)

        assertEquals(endpoint, dao.getById("3"))
    }
}
