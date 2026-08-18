package com.medmission.survey.data.repository

import com.medmission.survey.data.local.LaptopEndpointDao
import com.medmission.survey.data.model.LaptopEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeDao : LaptopEndpointDao {
    val endpoints = mutableMapOf<String, LaptopEndpoint>()
    override suspend fun upsert(endpoint: LaptopEndpoint) { endpoints[endpoint.id] = endpoint }
    override suspend fun getById(id: String): LaptopEndpoint? = endpoints[id]
    override suspend fun getByAddress(host: String, port: Int): LaptopEndpoint? =
        endpoints.values.firstOrNull { it.host == host && it.port == port }
    override fun observeAll(): Flow<List<LaptopEndpoint>> = flowOf(endpoints.values.toList())
}

class LaptopEndpointRepositoryTest {

    @Test
    fun `adding the same address twice keeps one laptop`() = runTest {
        // Tapping Add on a discovered laptop that is already saved used to create a
        // second card at the same address.
        val dao = FakeDao()
        val repository = LaptopEndpointRepository(dao)

        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080)
        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080)

        assertEquals(1, dao.endpoints.size)
    }

    @Test
    fun `re-adding from discovery does not wipe the key the operator entered`() = runTest {
        // Discovery carries no key, and losing the entered one would turn every later
        // send into a 401 with no visible cause.
        val dao = FakeDao()
        val repository = LaptopEndpointRepository(dao)
        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080, "C79QS-CQ8RM-5QRWU-ABDEE")

        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080)

        assertEquals("C79QS-CQ8RM-5QRWU-ABDEE", dao.endpoints.values.single().apiKey)
    }

    @Test
    fun `a newly entered key replaces the stored one`() = runTest {
        val dao = FakeDao()
        val repository = LaptopEndpointRepository(dao)
        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080, "OLD-KEY")

        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080, "  NEW-KEY  ")

        assertEquals("NEW-KEY", dao.endpoints.values.single().apiKey)
    }

    @Test
    fun `the same laptop on a different port stays a separate entry`() = runTest {
        // Two bridges on one machine is a legitimate test setup; only the exact
        // address pair identifies a laptop.
        val dao = FakeDao()
        val repository = LaptopEndpointRepository(dao)

        repository.addOrUpdate("bridge A", "192.168.1.10", 18080)
        repository.addOrUpdate("bridge B", "192.168.1.10", 18081)

        assertEquals(2, dao.endpoints.size)
    }

    @Test
    fun `updateApiKey trims what was typed off the laptop screen`() = runTest {
        val dao = FakeDao()
        val repository = LaptopEndpointRepository(dao)
        repository.addOrUpdate("1번 X-ray실", "192.168.1.10", 18080)
        val id = dao.endpoints.values.single().id

        repository.updateApiKey(id, "  C79QS-CQ8RM-5QRWU-ABDEE\n")

        assertEquals("C79QS-CQ8RM-5QRWU-ABDEE", dao.endpoints.values.single().apiKey)
    }
}
