pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "piatra"
include(":app")
// The on-device model engine (speech-to-text and the language model).
include(":cactus")
