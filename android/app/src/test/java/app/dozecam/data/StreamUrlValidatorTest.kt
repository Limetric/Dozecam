package app.dozecam.data

import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/** The accept/reject cases live in `shared/fixtures/stream-url/`, one file per question. */
class StreamUrlValidatorTest {

    @Serializable
    private data class Case<T>(val name: String, val url: String, val expected: T)

    @Serializable
    private data class Fixture<T>(val cases: List<Case<T>>)

    private val valid = Fixtures.decode<Fixture<Boolean>>("stream-url/valid.json").cases
    private val monitorable = Fixtures.decode<Fixture<Boolean>>("stream-url/monitorable.json").cases
    private val normalized = Fixtures.decode<Fixture<String>>("stream-url/normalize.json").cases

    private fun <T> List<Case<T>>.check(names: Array<out String>, actual: (String) -> T) = names.forEach { name ->
        val case = singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        assertEquals("${case.name}: \"${case.url}\"", case.expected, actual(case.url))
    }

    private fun checkValid(vararg names: String) = valid.check(names, StreamUrlValidator::isValid)

    private fun checkMonitorable(vararg names: String) = monitorable.check(names, StreamUrlValidator::isMonitorable)

    private fun checkNormalized(vararg names: String) = normalized.check(names, StreamUrlValidator::normalize)

    @Test
    fun `accepts plain rtsp url with port and token path`() = checkValid("plain rtsp url with port and token path")

    @Test
    fun `accepts hostname urls and surrounding whitespace`() = checkValid("hostname url with surrounding whitespace")

    @Test
    fun `accepts uppercase scheme`() = checkValid("uppercase scheme")

    @Test
    fun `rejects blank input`() = checkValid("empty input", "whitespace-only input")

    @Test
    fun `rejects non-rtsp schemes`() = checkValid("http scheme")

    @Test
    fun `accepts rtsps urls, including secure-RTSP query params`() =
        checkValid("rtsps url", "rtsps url with Protect's secure-RTSP query param")

    // A stale pre-normalization rtsps entry; normalize() prevents new ones.
    @Test
    fun `only plain rtsp urls are monitorable`() =
        checkMonitorable("plain rtsp url", "stale pre-normalization rtsps url", "empty input is not monitorable", "http url")

    @Test
    fun `normalize rewrites Protect's rtsps console link to its playable rtsp alias`() =
        checkNormalized("Protect's rtsps console link becomes its playable rtsp alias")

    @Test
    fun `normalize leaves an rtsps url on a non-standard port untouched apart from scheme`() =
        checkNormalized("rtsps on a non-standard port changes only the scheme")

    @Test
    fun `normalize is a no-op for plain rtsp urls and trims whitespace`() =
        checkNormalized("plain rtsp is only trimmed")

    @Test
    fun `rejects urls without a host`() = checkValid("scheme and slashes with no host", "opaque rtsp url with no host")

    @Test
    fun `rejects unparseable input`() = checkValid("host containing spaces", "not a url at all")
}
