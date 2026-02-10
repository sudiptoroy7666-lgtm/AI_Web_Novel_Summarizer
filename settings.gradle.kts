pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }

    // Enable version catalog for better dependency management
    versionCatalogs {
        create("libs") {
            // Core libraries
            library("kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
            library("core-ktx", "androidx.core:core-ktx:1.16.0")
            library("appcompat", "androidx.appcompat:appcompat:1.7.0")
            library("material", "com.google.android.material:material:1.12.0")

            // Room
            library("room-runtime", "androidx.room:room-runtime:2.7.1")
            library("room-ktx", "androidx.room:room-ktx:2.7.1")
            library("room-compiler", "androidx.room:room-compiler:2.7.1")

            // Coroutines
            library("coroutines-android", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            library("coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

            // Lifecycle
            library("lifecycle-viewmodel-ktx", "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
            library("lifecycle-livedata-ktx", "androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
            library("lifecycle-runtime-ktx", "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

            // Navigation
            library("navigation-fragment-ktx", "androidx.navigation:navigation-fragment-ktx:2.8.5")
            library("navigation-ui-ktx", "androidx.navigation:navigation-ui-ktx:2.8.5")

            // WebKit
            library("webkit", "androidx.webkit:webkit:1.11.0")
        }
    }
}


rootProject.name = "Novel_Summary"
include(":app")
 