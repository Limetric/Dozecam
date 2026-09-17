import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersioningTest {
    @Test
    fun `APP_VERSION_NAME wins over git describe and strips tag prefix`() {
        val versionName = AppVersioning.versionName(
            env = mapOf("APP_VERSION_NAME" to " v1.2.3 "),
            gitDescribe = { "v9.9.9" },
        )

        assertEquals("1.2.3", versionName)
    }

    @Test
    fun `version name sanitizes slash refs`() {
        val versionName = AppVersioning.versionName(
            env = mapOf("APP_VERSION_NAME" to "feature/app-version"),
            gitDescribe = { "ignored" },
        )

        assertEquals("feature-app-version", versionName)
    }

    @Test
    fun `version name falls back to normalized git describe`() {
        val versionName = AppVersioning.versionName(
            env = emptyMap(),
            gitDescribe = { "v2.0.0-3-gabc1234-dirty" },
        )

        assertEquals("2.0.0-3-gabc1234-dirty", versionName)
    }

    @Test
    fun `version name uses development fallback when inputs are blank`() {
        val versionName = AppVersioning.versionName(
            env = mapOf("APP_VERSION_NAME" to " "),
            gitDescribe = { "" },
        )

        assertEquals("0.1.0-dev", versionName)
    }

    @Test
    fun `version helper does not expose project directory shell out overload`() {
        val hasProjectDirOverload = AppVersioning::class.java.declaredMethods.any { method ->
            method.name == "versionName" &&
                method.parameterTypes.any { it == java.io.File::class.java }
        }

        assertEquals(false, hasProjectDirOverload)
    }

    @Test
    fun `version helper does not read process environment`() {
        val classBytes = AppVersioning::class.java.getResourceAsStream("/AppVersioning.class")!!
            .readBytes()
        val classText = classBytes.toString(Charsets.ISO_8859_1)

        assertEquals(false, classText.contains("getenv"))
    }

    @Test
    fun `version code uses positive APP_VERSION_CODE`() {
        val versionCode = AppVersioning.versionCode(
            env = mapOf("APP_VERSION_CODE" to "42"),
        )

        assertEquals(42, versionCode)
    }

    @Test
    fun `version code trims APP_VERSION_CODE`() {
        val versionCode = AppVersioning.versionCode(
            env = mapOf("APP_VERSION_CODE" to " 7 "),
        )

        assertEquals(7, versionCode)
    }

    @Test
    fun `version code falls back when APP_VERSION_CODE is missing invalid or non-positive`() {
        assertEquals(1, AppVersioning.versionCode(env = emptyMap()))
        assertEquals(1, AppVersioning.versionCode(env = mapOf("APP_VERSION_CODE" to "abc")))
        assertEquals(1, AppVersioning.versionCode(env = mapOf("APP_VERSION_CODE" to "0")))
        assertEquals(1, AppVersioning.versionCode(env = mapOf("APP_VERSION_CODE" to "-1")))
    }
}
