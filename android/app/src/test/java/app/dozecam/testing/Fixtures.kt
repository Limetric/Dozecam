package app.dozecam.testing

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Loads the golden test vectors in `shared/fixtures` (#61), which the iOS
 * tests read too. The directory comes from the `dozecam.fixtures` system
 * property that app/build.gradle.kts sets on every test task.
 */
object Fixtures {
    val root: File
        get() = File(
            checkNotNull(System.getProperty("dozecam.fixtures")) {
                "dozecam.fixtures is not set; run the tests through Gradle"
            },
        )

    private val json = Json { ignoreUnknownKeys = false }

    fun file(path: String, root: File = this.root): File =
        File(root, path).also { require(it.isFile) { "no fixture at ${it.path}" } }

    fun bytes(path: String, root: File = this.root): ByteArray = file(path, root).readBytes()

    fun text(path: String, root: File = this.root): String = file(path, root).readText()

    fun json(path: String, root: File = this.root): JsonElement = json.parseToJsonElement(text(path, root))

    /** Decodes a fixture into a `@Serializable` type declared by the test. */
    inline fun <reified T> decode(path: String, root: File = this.root): T =
        Json { ignoreUnknownKeys = false }.decodeFromString(text(path, root))
}
