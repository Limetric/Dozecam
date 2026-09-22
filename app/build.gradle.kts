import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Every build is signed with the upload key, which lives in the repo encrypted
// (tools/signing.sh, decrypted in CI before release builds). A checkout without
// the decryption secret still builds and tests: debug falls back to the default
// debug key, and release packaging fails loudly rather than shipping unsigned.
val signingPropertiesFile = rootProject.file("keystore_dozecam_upload.properties")
val hasSigningProperties = signingPropertiesFile.isFile
val signingProperties = Properties().apply {
    if (hasSigningProperties) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String =
    signingProperties.getProperty(name)
        ?: error("Missing $name in ${signingPropertiesFile.name}")

// Release versions come from CI, not from a number edited by hand: the release
// workflow passes the tag as APP_VERSION_NAME and the commit count as
// APP_VERSION_CODE, so every build off main is strictly newer than the last.
// A local checkout falls back to `git describe` and code 1.
val appVersionEnv = mapOf(
    "APP_VERSION_NAME" to providers.environmentVariable("APP_VERSION_NAME").orNull.orEmpty(),
    "APP_VERSION_CODE" to providers.environmentVariable("APP_VERSION_CODE").orNull.orEmpty(),
)
val gitDescribeVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("")

android {
    namespace = "app.dozecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dozecam"
        minSdk = 31
        targetSdk = 37
        versionCode = AppVersioning.versionCode(appVersionEnv)
        versionName = AppVersioning.versionName(appVersionEnv) { gitDescribeVersion.get() }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningProperties) {
            create("upload") {
                storeFile = rootProject.file(signingValue("storeFile"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    flavorDimensions += "environment"

    productFlavors {
        create("production") {
            dimension = "environment"
        }

        // Installs alongside the Play build and says so on the launcher;
        // see app/src/dev/res/values/strings.xml.
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    buildTypes {
        debug {
            if (hasSigningProperties) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
        release {
            isMinifyEnabled = true
            if (hasSigningProperties) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric reaches into OpenJDK internals — from 4.17, into
// jdk.internal.access to back an API 36 application's shared memory with a
// real file descriptor — and on Java 17+ the module system refuses that
// unless the packages are opened to it. Without these every Robolectric test
// dies in setUpApplicationState before it runs; the list is Robolectric's own
// (https://robolectric.org/getting-started/), kept whole rather than trimmed
// to today's failure so the next internal it reaches for is already open.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
}

gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { task ->
        task.name.matches(Regex("(assemble|bundle|package).*Release"))
    }
    if (packagesRelease && !hasSigningProperties) {
        throw org.gradle.api.GradleException(
            "Missing ${signingPropertiesFile.name}. " +
                "Run tools/signing.sh decrypt before building release artifacts.",
        )
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        // Compose-rule Robolectric tests need ui-test-manifest's ComponentActivity,
        // which is debugImplementation-only; debug unit tests cover the same code.
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    // libVLC transitively pins an ancient androidx.fragment whose activity-result
    // integration predates ActivityResultRegistry; force a modern version.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.libvlc.all)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
