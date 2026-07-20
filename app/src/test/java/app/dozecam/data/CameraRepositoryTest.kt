package app.dozecam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun securePrefs(): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "cameras.preferences_pb") },
        )

    private fun TestScope.repository(
        securePrefs: SharedPreferences = securePrefs(),
        dataStore: DataStore<Preferences> = dataStore(),
    ) = CameraRepository(securePrefs, dataStore)

    @Test
    fun `starts empty`() = runTest {
        val repository = repository()

        assertTrue(repository.cameras.first().isEmpty())
        assertNull(repository.selectedCamera.first())
    }

    @Test
    fun `upsert adds and updates cameras`() = runTest {
        val repository = repository()
        val nursery = Camera("a", "Nursery", "rtsp://cam:7447/a")

        repository.upsert(nursery)
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))
        repository.upsert(nursery.copy(name = "Nursery 2"))

        val cameras = repository.cameras.first()
        assertEquals(listOf("Nursery 2", "Play room"), cameras.map { it.name })
    }

    @Test
    fun `cameras survive a new repository over the same storage`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        repository(prefs, store).upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))

        val reloaded = repository(prefs, store).cameras.first()

        assertEquals(listOf("Nursery"), reloaded.map { it.name })
    }

    @Test
    fun `selection falls back to the first camera and follows explicit selects`() = runTest {
        val repository = repository()
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))

        assertEquals("a", repository.selectedCamera.first()?.id)

        repository.select("b")
        assertEquals("b", repository.selectedCamera.first()?.id)
    }

    @Test
    fun `removing the selected camera falls back to the first remaining`() = runTest {
        val repository = repository()
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))
        repository.select("b")

        repository.remove("b")

        assertEquals("a", repository.selectedCamera.first()?.id)
        assertEquals(1, repository.cameras.first().size)
    }

    @Test
    fun `legacy single-url installs migrate into secure storage and are scrubbed`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        store.edit { it[stringPreferencesKey("stream_url")] = "rtsp://cam:7447/legacy" }
        val repository = repository(prefs, store)

        val cameras = repository.cameras.first()

        assertEquals(1, cameras.size)
        assertEquals("rtsp://cam:7447/legacy", cameras.first().url)
        assertEquals(cameras.first(), repository.selectedCamera.first())
        // The plaintext token no longer lives in the unencrypted DataStore.
        assertNull(store.data.first()[stringPreferencesKey("stream_url")])
        assertTrue(prefs.getString("cameras", "")!!.contains("legacy"))
    }

    @Test
    fun `legacy active monitoring url migrates into secure storage and is scrubbed`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        store.edit {
            it[stringPreferencesKey("stream_url")] = "rtsp://cam:7447/legacy"
            it[stringPreferencesKey("active_monitoring_url")] = "rtsp://cam:7447/active"
        }
        val repository = repository(prefs, store)

        repository.cameras.first() // triggers migration

        assertEquals("rtsp://cam:7447/active", prefs.getString("active_monitoring_url", null))
        assertNull(store.data.first()[stringPreferencesKey("active_monitoring_url")])
    }

    @Test
    fun `legacy v04 camera lists migrate and are scrubbed`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        store.edit {
            it[stringPreferencesKey("cameras")] =
                """[{"id":"a","name":"Nursery","url":"rtsp://cam:7447/a"}]"""
        }
        val repository = repository(prefs, store)

        assertEquals(listOf("Nursery"), repository.cameras.first().map { it.name })
        assertNull(store.data.first()[stringPreferencesKey("cameras")])
    }
}
