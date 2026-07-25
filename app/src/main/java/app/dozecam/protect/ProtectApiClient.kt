package app.dozecam.protect

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
data class ProtectChannel(
    val id: Int,
    val name: String = "",
    val isRtspEnabled: Boolean = false,
    val rtspAlias: String? = null,
)

@Serializable
data class ProtectCamera(
    val id: String,
    val name: String = "",
    val channels: List<ProtectChannel> = emptyList(),
) {
    /** Nursery view: medium quality is plenty and light on decode and Wi-Fi. */
    val preferredChannel: ProtectChannel?
        get() = channels.firstOrNull { it.name.equals("Medium", ignoreCase = true) }
            ?: channels.firstOrNull()
}

@Serializable
data class ProtectBootstrap(
    val cameras: List<ProtectCamera> = emptyList(),
)

data class ProtectSession(
    val cookie: String,
    val csrfToken: String?,
)

class ProtectApiException(message: String, val statusCode: Int? = null) : IOException(message)

/**
 * Minimal client for the UniFi Protect console's local HTTP API. The API is
 * unofficial and shifts between Protect releases; every parse ignores unknown
 * keys and failures surface as [ProtectApiException] with actionable text.
 */
class ProtectApiClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(username: String, password: String): ProtectSession {
        val body = json.encodeToString(LoginRequest(username, password))
        val request = Request.Builder()
            .url(baseUrl.newBuilder().encodedPath("/api/auth/login").build())
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return execute(request) { response, _ ->
            if (!response.isSuccessful) {
                throw ProtectApiException(
                    "Login failed (${response.code}); check the console address and credentials",
                    response.code,
                )
            }
            val cookies = response.headers("Set-Cookie")
            val tokenCookie = cookies.firstOrNull { it.startsWith("TOKEN=") }
                ?.substringBefore(';')
                ?: throw ProtectApiException("Login succeeded but no session cookie was returned")
            ProtectSession(
                cookie = tokenCookie,
                csrfToken = response.header("X-CSRF-Token") ?: response.header("X-Updated-CSRF-Token"),
            )
        }
    }

    suspend fun bootstrap(session: ProtectSession): ProtectBootstrap {
        val request = authorized(session)
            .url(baseUrl.newBuilder().encodedPath("/proxy/protect/api/bootstrap").build())
            .get()
            .build()
        return execute(request) { response, body ->
            if (!response.isSuccessful) {
                throw ProtectApiException(
                    "Camera discovery failed (${response.code}); is this a Protect console?",
                    response.code,
                )
            }
            json.decodeFromString<ProtectBootstrap>(body)
        }
    }

    /** Enables RTSP on the channel and returns the updated camera. */
    suspend fun enableRtsp(
        session: ProtectSession,
        cameraId: String,
        channelId: Int,
    ): ProtectCamera {
        val body = """{"channels":[{"id":$channelId,"isRtspEnabled":true}]}"""
        val request = authorized(session)
            .url(
                baseUrl.newBuilder()
                    .encodedPath("/proxy/protect/api/cameras/$cameraId")
                    .build(),
            )
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        return execute(request) { response, body ->
            if (!response.isSuccessful) {
                throw ProtectApiException(
                    "Enabling the RTSP stream failed (${response.code}); " +
                        "the account may lack camera management permission",
                    response.code,
                )
            }
            json.decodeFromString<ProtectCamera>(body)
        }
    }

    /**
     * Mints a console API key for the public Integration API — the same call
     * Home Assistant makes. This lives on the *private* API because the public
     * one cannot bootstrap its own credential. Consoles older than Protect 5.3
     * have no such endpoint, and accounts without owner rights are refused;
     * both surface as [ProtectApiException] so the caller can fall back.
     */
    suspend fun createApiKey(session: ProtectSession, name: String): String {
        val body = json.encodeToString(ApiKeyRequest(name))
        val request = authorized(session)
            .url(baseUrl.newBuilder().encodedPath("/proxy/users/api/v2/user/self/keys").build())
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return client.exchange(request) { response, payload ->
            if (!response.isSuccessful) {
                throw ProtectApiException(
                    "Creating an API key failed (${response.code}); " +
                        "the account may not own this console",
                    response.code,
                )
            }
            json.decodeFromString<ApiKeyEnvelope>(payload).data?.fullApiKey
                ?: throw ProtectApiException("Console accepted the request but returned no API key")
        }
    }

    /** Plain RTSP on the console's 7447 port; RTSPS (7441) is unsupported by common players. */
    fun rtspUrlFor(alias: String): String = rtspUrl(baseUrl.host, alias)

    private fun authorized(session: ProtectSession): Request.Builder =
        Request.Builder()
            .header("Cookie", session.cookie)
            .apply { session.csrfToken?.let { header("X-CSRF-Token", it) } }

    private suspend fun <T> execute(request: Request, handler: (Response, String) -> T): T =
        client.exchange(request, handler)

    @Serializable
    private data class LoginRequest(
        val username: String,
        val password: String,
        val rememberMe: Boolean = true,
    )

    @Serializable
    private data class ApiKeyRequest(val name: String)

    @Serializable
    private data class ApiKeyEnvelope(val data: ApiKeyData? = null)

    @Serializable
    private data class ApiKeyData(
        @SerialName("full_api_key") val fullApiKey: String? = null,
    )

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * "192.168.1.1", "console.local:8443" → https base URL; null if
         * unparseable. Non-HTTPS schemes are rejected outright — credentials
         * must never ride a connection that bypasses the TOFU TLS flow.
         */
        fun baseUrlFor(hostInput: String): HttpUrl? {
            val trimmed = hostInput.trim().removeSuffix("/")
            if (trimmed.isEmpty()) return null
            val candidate = when {
                trimmed.contains("://") -> trimmed
                // A bare IPv6 literal (two or more colons, unbracketed) needs
                // brackets before it can be a URL host.
                trimmed.count { it == ':' } >= 2 && !trimmed.startsWith("[") ->
                    "https://[$trimmed]"
                else -> "https://$trimmed"
            }
            return candidate.toHttpUrlOrNull()?.takeIf { it.isHttps }
        }
    }
}
