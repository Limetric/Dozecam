import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Play upload signing activates only when the keystore properties exist;
// checkouts without them keep building unsigned artifacts.
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

android {
    namespace = "app.dozecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.dozecam"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
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

    buildTypes {
        debug {
            // Installs alongside the Play build, mirroring CloudMount's dev-flavor convention.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
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
