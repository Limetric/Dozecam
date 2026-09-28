package app.dozecam.dev

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TestbedSeedReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedIntent(payload: String) =
        Intent().putExtra(TestbedSeedReceiver.EXTRA_CAMERAS, payload)

    @Test
    fun `seeds broadcast cameras into the repository`() {
        TestbedSeedReceiver().onReceive(
            context,
            seedIntent(
                """
                [{"name":"Testbed nursery","url":"rtsp://10.0.2.2:8554/nursery"},
                 {"name":"Testbed porch","url":"rtsp://10.0.2.2:8554/porch"}]
                """.trimIndent(),
            ),
        )

        val cameras = runBlocking {
            withTimeout(5_000) {
                context.appContainer.cameras.cameras.first { it.size == 2 }
            }
        }
        assertEquals(
            listOf("Testbed nursery", "Testbed porch"),
            cameras.map { it.name },
        )
    }

    @Test
    fun `re-seeding upserts instead of duplicating`() {
        val nursery = """{"name":"Testbed nursery","url":"rtsp://10.0.2.2:8554/nursery"}"""
        val porch = """{"name":"Testbed porch","url":"rtsp://10.0.2.2:8554/porch"}"""
        val receiver = TestbedSeedReceiver()

        receiver.onReceive(context, seedIntent("[$nursery]"))
        runBlocking {
            withTimeout(5_000) { context.appContainer.cameras.cameras.first { it.size == 1 } }
        }
        // The second seed repeats nursery and adds porch; porch's arrival
        // proves the whole batch landed, so a duplicate nursery would show.
        receiver.onReceive(context, seedIntent("[$nursery,$porch]"))

        val cameras = runBlocking {
            withTimeout(5_000) {
                context.appContainer.cameras.cameras.first { list ->
                    list.any { it.name == "Testbed porch" }
                }
            }
        }
        assertEquals(listOf("Testbed nursery", "Testbed porch"), cameras.map { it.name })
    }

    @Test
    fun `a payload with no valid cameras seeds nothing`() {
        TestbedSeedReceiver().onReceive(context, seedIntent("not json"))
        TestbedSeedReceiver().onReceive(context, Intent())

        val cameras = runBlocking { context.appContainer.cameras.cameras.first() }
        assertTrue(cameras.isEmpty())
    }
}
