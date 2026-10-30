import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself; the Compose compiler plugin turns @Composable functions into UI code.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Generates JSON conversion code for classes marked @Serializable.
    alias(libs.plugins.kotlin.serialization)
    // KSP runs code generators during the build; Room uses it to write the DAO/database code.
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.piatra"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.piatra"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // Runs the tests in app/src/androidTest/ on the phone.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // The release key, kept out of the repository (docs/building.md, "Release build"). Its
    // details are in a properties file: the one the Gradle property `piatra.keystore` names
    // (set it in ~/.gradle/gradle.properties), or else keystore.properties next to this
    // project's settings.gradle.kts (git-ignored).
    val keystoreFile = (findProperty("piatra.keystore") as String?)?.let { file(it) }
        ?: rootProject.file("keystore.properties")
    val releaseKey = keystoreFile.takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A debug build is "debuggable": slower to run (it can't be fully optimised), which shows
        // as choppy animations. The release build isn't. Debug and release builds are signed
        // with different keys, so one can't be installed over the other: Android refuses an
        // update signed by another key. (adb `run-as`, used to look at the database, only works
        // on the debug build.)
        release {
            // Without the release key (a fresh clone), fall back to the debug key so the
            // release build still works for testing; Gradle prints a warning.
            signingConfig = if (releaseKey != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("piatra: no release key found, signing the release build with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }
}

room {
    // Each schema version is saved as JSON here and committed, so migrations can compare them.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Our own module (cactus/), built with the app rather than downloaded.
    implementation(project(":cactus"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.documentfile)
    // Bluetooth LE in coroutines, the library upstream uses too.
    implementation(libs.kable)
    ksp(libs.room.compiler)
    debugImplementation(libs.compose.ui.tooling)

    // Unit tests (app/src/test/): run on the computer's JVM with `./gradlew test`, no phone needed.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests (app/src/androidTest/): run on the phone with
    // `./gradlew connectedDebugAndroidTest`, for code that needs Android itself (e.g. MediaCodec).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
