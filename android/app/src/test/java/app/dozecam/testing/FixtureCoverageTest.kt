package app.dozecam.testing

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests pick shared fixture cases by name, so a case added to a fixture that
 * no test names would silently never run. Every named case must appear, as a
 * string literal, in some Android test.
 *
 * A case is an object with a string `name` in a top-level array of a fixture
 * file. Verbatim console responses (`protect-api/public`, `protect-api/legacy`)
 * are not cases: their `name` fields are camera names.
 */
class FixtureCoverageTest {

    private val responseDirs = listOf("protect-api/public", "protect-api/legacy")

    private fun caseNames(): Map<String, List<String>> =
        Fixtures.root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { it.relativeTo(Fixtures.root).invariantSeparatorsPath }
            .filter { path -> responseDirs.none { path.startsWith("$it/") } }
            .associateWith { path ->
                val top = Fixtures.json(path) as? JsonObject ?: return@associateWith emptyList()
                top.values.filterIsInstance<JsonArray>().flatMap { array ->
                    array.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
                }
            }

    @Test
    fun `every named fixture case is run by an Android test`() {
        val sources = File(Fixtures.root.parentFile.parentFile, "android/app/src/test/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val names = caseNames()
        assertTrue("found no named fixture cases under ${Fixtures.root}", names.values.flatten().isNotEmpty())
        val unused = names.flatMap { (path, cases) ->
            cases.filterNot { "\"$it\"" in sources }.map { "$path: \"$it\"" }
        }
        assertTrue("fixture cases no Android test runs:\n${unused.joinToString("\n")}", unused.isEmpty())
    }
}
