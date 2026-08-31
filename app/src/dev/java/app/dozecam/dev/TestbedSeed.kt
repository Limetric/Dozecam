package app.dozecam.dev

import app.dozecam.data.Camera
import app.dozecam.data.StreamUrlValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SeedEntry(val name: String, val url: String)

/** Turns the seed broadcast's JSON payload into storable cameras. */
object TestbedSeed {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses `[{"name": …, "url": …}, …]`. Ids derive from the name, so
     * re-seeding after a testbed restart upserts the existing entries instead
     * of stacking duplicates. Entries [StreamUrlValidator] rejects are
     * dropped, and URLs go through the same [StreamUrlValidator.normalize]
     * rewrite as manual entry in settings.
     */
    fun parse(payload: String): List<Camera> {
        val entries = runCatching {
            json.decodeFromString<List<SeedEntry>>(payload)
        }.getOrElse { return emptyList() }
        return entries.mapNotNull { entry ->
            val name = entry.name.trim()
            if (name.isEmpty() || !StreamUrlValidator.isValid(entry.url)) {
                return@mapNotNull null
            }
            Camera(
                id = "testbed-" + slug(name),
                name = name,
                url = StreamUrlValidator.normalize(entry.url),
            )
        }.distinctBy { it.id }
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
