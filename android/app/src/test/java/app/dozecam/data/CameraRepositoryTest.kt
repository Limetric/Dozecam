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
        assertTrue(repository.enabledCameras.first().isEmpty())
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
    fun `removing a camera drops it from both views`() = runTest {
        val repository = repository()
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))

        repository.remove("b")

        assertEquals(listOf("a"), repository.cameras.first().map { it.id })
        assertEquals(listOf("a"), repository.enabledCameras.first().map { it.id })
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

    @Test
    fun `the protect identity survives a reload`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        repository(prefs, store).upsert(
            Camera(
                id = "protect-cam1-1",
                name = "Nursery",
                url = "rtsp://cam:7447/a",
                protect = ProtectStream(cameraId = "cam1", channel = 1),
            ),
        )

        val reloaded = repository(prefs, store).cameras.first().single()

        assertEquals(ProtectStream("cam1", 1), reloaded.protect)
    }

    @Test
    fun `cameras stored before livestream support still load`() = runTest {
        val prefs = securePrefs()
        // Exactly what v1.0 wrote: no protect field at all. Failing to decode
        // this would empty every existing user's camera list on upgrade.
        prefs.edit()
            .putString(
                "cameras",
                """[{"id":"a","name":"Nursery","url":"rtsp://cam:7447/a"}]""",
            )
            .commit()

        val cameras = repository(prefs).cameras.first()

        assertEquals(listOf("Nursery"), cameras.map { it.name })
        assertNull(cameras.single().protect)
    }

    @Test
    fun `a camera stored before the enabled flag existed comes back enabled`() = runTest {
        val prefs = securePrefs()
        prefs.edit()
            .putString("cameras", """[{"id":"a","name":"Nursery","url":"rtsp://cam:7447/a"}]""")
            .commit()

        // Upgrading must not silently stop monitoring an existing install.
        assertTrue(repository(prefs).cameras.first().single().enabled)
    }

    @Test
    fun `enabledCameras is the switched-on subset in list order`() = runTest {
        val repository = repository()
        repository.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        repository.upsert(Camera("b", "Play room", "rtsp://cam:7447/b"))
        repository.upsert(Camera("c", "Hall", "rtsp://cam:7447/c"))

        repository.setEnabled("b", false)

        assertEquals(listOf("a", "c"), repository.enabledCameras.first().map { it.id })
        assertEquals(3, repository.cameras.first().size)
    }

    @Test
    fun `setEnabled survives a new repository over the same storage`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        repository(prefs, store).apply {
            upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
            setEnabled("a", false)
        }

        assertEquals(false, repository(prefs, store).cameras.first().single().enabled)
    }

    @Test
    fun `setEnabled leaves the rest of the camera untouched`() = runTest {
        val repository = repository()
        repository.upsert(
            Camera(
                id = "a",
                name = "Nursery",
                url = "rtsp://cam:7447/a",
                protect = ProtectStream("cam1", 1, consoleHost = "console.lan"),
            ),
        )

        repository.setEnabled("a", false)

        val camera = repository.cameras.first().single()
        assertEquals("cam1", camera.protect?.cameraId)
        assertEquals("console.lan", camera.protect?.consoleHost)
    }



    @Test
    fun `the owning console survives a reload`() = runTest {
        val prefs = securePrefs()
        val store = dataStore()
        repository(prefs, store).upsert(
            Camera(
                id = "protect-cam1-1",
                name = "Nursery",
                url = "rtsp://cam:7447/a",
                protect = ProtectStream("cam1", 1, consoleHost = "console.lan"),
            ),
        )

        val reloaded = repository(prefs, store).cameras.first().single()

        assertEquals("console.lan", reloaded.protect?.consoleHost)
    }

    @Test
    fun `a camera stored before ownership was recorded still loads`() = runTest {
        val prefs = securePrefs()
        prefs.edit()
            .putString(
                "cameras",
                """[{"id":"a","name":"Nursery","url":"rtsp://c:7447/a",""" +
                    """"protect":{"cameraId":"cam1","channel":1}}]""",
            )
            .commit()

        val camera = repository(prefs).cameras.first().single()

        assertEquals("cam1", camera.protect?.cameraId)
        assertNull(camera.protect?.consoleHost)
    }
}
