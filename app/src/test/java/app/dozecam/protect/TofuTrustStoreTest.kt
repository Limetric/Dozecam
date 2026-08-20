package app.dozecam.protect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TofuTrustStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(): TofuTrustStore = TofuTrustStore(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "trust.preferences_pb") },
        ),
    )

    @Test
    fun `a pinned endpoint reads back`() = runTest {
        val store = store()

        store.pin("192.168.1.1:7441", "AA:BB")

        assertEquals("AA:BB", store.fingerprintFor("192.168.1.1:7441").first())
    }

    @Test
    fun `pinning again replaces the fingerprint`() = runTest {
        val store = store()
        store.pin("192.168.1.1", "AA:BB")

        store.pin("192.168.1.1", "CC:DD")

        assertEquals("CC:DD", store.fingerprintFor("192.168.1.1").first())
    }

    @Test
    fun `forgetting a host's endpoints leaves its console pin`() = runTest {
        val store = store()
        store.pin("192.168.1.1", "CONSOLE")
        store.pin("192.168.1.1:7441", "MEDIA-1")
        store.pin("192.168.1.1:7443", "MEDIA-2")

        store.forgetLearnedEndpoints("192.168.1.1")

        assertEquals("CONSOLE", store.fingerprintFor("192.168.1.1").first())
        assertNull(store.fingerprintFor("192.168.1.1:7441").first())
        assertNull(store.fingerprintFor("192.168.1.1:7443").first())
    }

    @Test
    fun `forgetting one host leaves another console's endpoints alone`() = runTest {
        val store = store()
        store.pin("192.168.1.1:7441", "MEDIA-1")
        store.pin("192.168.1.2:7441", "MEDIA-2")

        store.forgetLearnedEndpoints("192.168.1.1")

        assertNull(store.fingerprintFor("192.168.1.1:7441").first())
        assertEquals("MEDIA-2", store.fingerprintFor("192.168.1.2:7441").first())
    }
}
