package app.dozecam.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestbedSeedTest {

    @Test
    fun `parses names and urls into cameras with stable testbed ids`() {
        val cameras = TestbedSeed.parse(
            """
            [{"name":"Testbed nursery","url":"rtsp://10.0.2.2:8554/nursery"},
             {"name":"Testbed porch","url":"rtsp://10.0.2.2:8554/porch"}]
            """.trimIndent(),
        )

        assertEquals(
            listOf("testbed-testbed-nursery", "testbed-testbed-porch"),
            cameras.map { it.id },
        )
        assertEquals("Testbed nursery", cameras[0].name)
        assertEquals("rtsp://10.0.2.2:8554/nursery", cameras[0].url)
        assertTrue(cameras.all { it.enabled && it.protect == null })
    }

    @Test
    fun `re-parsing the same payload yields the same ids`() {
        val payload = """[{"name":"Testbed nursery","url":"rtsp://10.0.2.2:8554/nursery"}]"""

        assertEquals(TestbedSeed.parse(payload), TestbedSeed.parse(payload))
    }

    @Test
    fun `drops entries with invalid urls or blank names`() {
        val cameras = TestbedSeed.parse(
            """
            [{"name":"No scheme","url":"10.0.2.2/nursery"},
             {"name":"Wrong scheme","url":"https://example.com/cam"},
             {"name":"   ","url":"rtsp://10.0.2.2:8554/nursery"},
             {"name":"Kept","url":"rtsp://10.0.2.2:8554/porch"}]
            """.trimIndent(),
        )

        assertEquals(listOf("Kept"), cameras.map { it.name })
    }

    @Test
    fun `normalizes rtsps urls the way manual entry does`() {
        val cameras =
            TestbedSeed.parse("""[{"name":"Cam","url":"rtsps://192.168.1.9:7441/abc?x"}]""")

        assertEquals("rtsp://192.168.1.9:7447/abc", cameras.single().url)
    }

    @Test
    fun `entries collapsing to the same id keep only the first`() {
        val cameras = TestbedSeed.parse(
            """
            [{"name":"Cam A","url":"rtsp://10.0.2.2:8554/one"},
             {"name":"cam  a","url":"rtsp://10.0.2.2:8554/two"}]
            """.trimIndent(),
        )

        assertEquals(listOf("rtsp://10.0.2.2:8554/one"), cameras.map { it.url })
    }

    @Test
    fun `garbage and empty payloads seed nothing`() {
        assertTrue(TestbedSeed.parse("").isEmpty())
        assertTrue(TestbedSeed.parse("not json").isEmpty())
        assertTrue(TestbedSeed.parse("""{"name":"obj not array"}""").isEmpty())
        assertTrue(TestbedSeed.parse("[]").isEmpty())
    }
}
