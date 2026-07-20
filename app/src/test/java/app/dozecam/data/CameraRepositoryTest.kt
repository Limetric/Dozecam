package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "cameras.preferences_pb") },
        )

    @Test
    fun `starts empty`() = runTest {
        val repository = CameraRepository(dataStore())

        assertTrue(repository.cameras.first().isEmpty())
        assertNull(repository.selectedCamera.first())
    }

    @Test
    fun `upsert adds and updates cameras`() = runTest {
        val repository = CameraRepository(dataStore())
        val nursery = Camera("a", "Nursery", "rtsp://cam:7447/a")

        repository.upsert(nursery)
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))
        repository.upsert(nursery.copy(name = "Nursery 2"))

        val cameras = repository.cameras.first()
        assertEquals(listOf("Nursery 2", "Play room"), cameras.map { it.name })
    }

    @Test
    fun `selection falls back to the first camera and follows explicit selects`() = runTest {
        val repository = CameraRepository(dataStore())
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))

        assertEquals("a", repository.selectedCamera.first()?.id)

        repository.select("b")
        assertEquals("b", repository.selectedCamera.first()?.id)
    }

    @Test
    fun `removing the selected camera falls back to the first remaining`() = runTest {
        val repository = CameraRepository(dataStore())
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))
        repository.select("b")

        repository.remove("b")

        assertEquals("a", repository.selectedCamera.first()?.id)
        assertEquals(1, repository.cameras.first().size)
    }

    @Test
    fun `legacy single-url installs surface as a first camera`() = runTest {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("stream_url")] = "rtsp://cam:7447/legacy" }
        val repository = CameraRepository(store)

        val cameras = repository.cameras.first()
        assertEquals(1, cameras.size)
        assertEquals("rtsp://cam:7447/legacy", cameras.first().url)
        assertEquals(cameras.first(), repository.selectedCamera.first())
    }

    @Test
    fun `writing cameras supersedes the legacy url`() = runTest {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("stream_url")] = "rtsp://cam:7447/legacy" }
        val repository = CameraRepository(store)

        repository.remove(CameraRepository.LEGACY_ID)

        assertTrue(repository.cameras.first().isEmpty())
    }
}
