object AppVersioning {
    private const val DEFAULT_VERSION_NAME = "0.1.0-dev"
    private const val DEFAULT_VERSION_CODE = 1

    fun versionName(
        env: Map<String, String>,
        gitDescribe: () -> String? = { null },
    ): String {
        val raw = env["APP_VERSION_NAME"].nonBlankOrNull()
            ?: gitDescribe().nonBlankOrNull()
            ?: DEFAULT_VERSION_NAME

        return normalizeVersionName(raw).ifBlank { DEFAULT_VERSION_NAME }
    }

    fun versionCode(env: Map<String, String>): Int =
        env["APP_VERSION_CODE"]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_VERSION_CODE

    private fun normalizeVersionName(raw: String): String =
        raw.trim()
            .replace(Regex("^v(?=\\d)"), "")
            .replace(Regex("[^0-9A-Za-z._+-]+"), "-")
            .trim('-')

    private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
