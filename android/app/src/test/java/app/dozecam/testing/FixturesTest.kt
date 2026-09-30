package app.dozecam.testing

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FixturesTest {

    @Serializable
    private data class Case(val threshold: Double, val sustainMs: Long)

    private fun tempRoot(): File = Files.createTempDirectory("fixtures").toFile().apply { deleteOnExit() }

    @Test
    fun `root is the shared fixtures directory of this checkout`() {
        val root = Fixtures.root
        assertEquals("fixtures", root.name)
        assertEquals("shared", root.parentFile.name)
        assertTrue(File(root.parentFile.parentFile, "android/app/build.gradle.kts").isFile)
    }

    @Test
    fun `decodes JSON fixtures into test types`() {
        val root = tempRoot()
        File(root, "detector").mkdirs()
        File(root, "detector/case.json").writeText("""{"threshold": 0.1, "sustainMs": 1500}""")
        assertEquals(Case(0.1, 1500), Fixtures.decode<Case>("detector/case.json", root))
    }

    @Test
    fun `unknown keys fail rather than being ignored`() {
        val root = tempRoot()
        File(root, "case.json").writeText("""{"threshold": 0.1, "sustainMs": 1500, "typo": 1}""")
        assertThrows(SerializationException::class.java) { Fixtures.decode<Case>("case.json", root) }
    }

    @Test
    fun `reads raw bytes`() {
        val root = tempRoot()
        File(root, "frame.bin").writeBytes(byteArrayOf(0, 1, -1))
        assertArrayEquals(byteArrayOf(0, 1, -1), Fixtures.bytes("frame.bin", root))
    }

    @Test
    fun `a missing fixture names its path`() {
        val error = assertThrows(IllegalArgumentException::class.java) { Fixtures.bytes("nope.bin", tempRoot()) }
        assertTrue(error.message!!.endsWith("nope.bin"))
    }
}
