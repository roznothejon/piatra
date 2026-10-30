// The Cactus on-device model engine, as an Android library: `app` depends on it.
// Holds the prebuilt engine (src/main/jniLibs), a tiny C library we compile ourselves
// (src/main/cpp) and the Kotlin bindings to both (src/main/kotlin).
plugins {
    alias(libs.plugins.android.library)
}

android {
    // Must stay com.cactus: the engine's C functions are named after the Kotlin package.
    namespace = "com.cactus"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // The engine only exists for 64-bit ARM, so build our C code for that alone.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Compiles src/main/cpp with the NDK's compiler, via CMake.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
