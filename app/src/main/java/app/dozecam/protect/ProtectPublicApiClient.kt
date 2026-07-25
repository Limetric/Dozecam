package app.dozecam.protect

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class PublicCamera(
    val id: String,
    // Nullable on the wire (the spec allows `oneOf [string, null]`).
    val name: String? = null,
)

/**
 * Client for the UniFi Protect *public* Integration API (Protect 5.3+), the
 * one Ubiquiti documents and Home Assistant migrated its stream URLs to. It is
 * authenticated by a console API key rather than a login session, so it cannot
 * mint its own credential — [ProtectApiClient.createApiKey] does that.
 *
 * Only the two calls onboarding needs are implemented: list cameras, and read
 * or create a camera's RTSPS streams.
 */
class ProtectPublicApiClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun cameras(apiKey: String): List<PublicCamera> {
        val request = authorized(apiKey).url(endpoint("cameras")).get().build()
        return execute(request, "Camera discovery") { body ->
            json.decodeFromString<List<PublicCamera>>(body)
        }
    }

    /** Streams already active on the camera, keyed by quality. */
    suspend fun rtspsStreams(apiKey: String, cameraId: String): Map<String, String> {
        val request = authorized(apiKey)
            .url(endpoint("cameras", cameraId, "rtsps-stream"))
            .get()
            .build()
        return execute(request, "Reading the camera's streams", ::parseStreams)
    }

    /** Enables the given qualities and returns the camera's streams by quality. */
    suspend fun createRtspsStreams(
        apiKey: String,
        cameraId: String,
        qualities: List<String>,
    ): Map<String, String> {
        val body = json.encodeToString(QualitiesRequest(qualities))
        val request = authorized(apiKey)
            .url(endpoint("cameras", cameraId, "rtsps-stream"))
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return execute(request, "Enabling the RTSP stream", ::parseStreams)
    }

    /**
     * The Integration API hands back `rtsps://<console>:7441/<alias>?enableSrtp`.
     * Only the alias travels: the host is whatever the console believes it is,
     * which need not be the address the user actually reached it on (Home
     * Assistant hit exactly this — core#176487), and the RTSPS port is not a
     * stream libVLC or Media3 can open here. Re-point the alias at the console
     * address that just answered, on the plain RTSP port.
     */
    fun streamUrlFor(rtspsUrl: String): String? =
        aliasOf(rtspsUrl)?.let { rtspUrl(baseUrl.host, it) }

    private fun aliasOf(rtspsUrl: String): String? {
        val path = runCatching { URI(rtspsUrl.trim()) }.getOrNull()?.path ?: return null
        return path.split('/').lastOrNull { it.isNotBlank() }
    }

    /**
     * A quality the camera does not currently serve can still be present as a
     * key with a null value, so take only the string ones.
     */
    private fun parseStreams(body: String): Map<String, String> =
        json.decodeFromString<JsonObject>(body).mapNotNull { (quality, url) ->
            val value = (url as? JsonPrimitive)?.takeIf { it.isString }?.content
            value?.let { quality to it }
        }.toMap()

    private fun endpoint(vararg segments: String): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath("/proxy/protect/integration/v1")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()

    private fun authorized(apiKey: String): Request.Builder =
        Request.Builder().header("X-API-KEY", apiKey)

    private suspend fun <T> execute(
        request: Request,
        action: String,
        parse: (String) -> T,
    ): T = client.exchange(request) { response, body ->
        if (!response.isSuccessful) {
            throw ProtectApiException(
                "$action failed (${response.code}) on the Protect integration API",
                response.code,
            )
        }
        parse(body)
    }

    @Serializable
    private data class QualitiesRequest(val qualities: List<String>)

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Nursery view: medium quality is plenty and light on decode and Wi-Fi. */
        const val QUALITY_MEDIUM = "medium"
    }
}
