plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}



android {
    namespace = "com.example.novel_summary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.novel_summary"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys
        val groqApiKeyPrimary = project.findProperty("GROQ_API_KEY_PRIMARY") as? String ?: ""
        val groqApiKeyFallback = project.findProperty("GROQ_API_KEY_FALLBACK") as? String ?: ""

        buildConfigField("String", "GROQ_API_KEY_PRIMARY", "\"$groqApiKeyPrimary\"")
        buildConfigField("String", "GROQ_API_KEY_FALLBACK", "\"$groqApiKeyFallback\"")



        // Model names per key
        buildConfigField("String", "GROQ_MODEL_PRIMARY", "\"llama-3.3-70b-versatile\"")
        buildConfigField("String", "GROQ_MODEL_FALLBACK", "\"llama-3.1-8b-instant\"")


        // API endpoints
        buildConfigField("String", "GROQ_BASE_URL", "\"https://api.groq.com/openai/v1/\"")




        // build.gradle.kts - CORRECT VALUES
        buildConfigField("int", "MAX_CONTENT_CEREBRAS", "240000")  // ~60K tokens (safe margin)
        buildConfigField("int", "MAX_CONTENT_PRIMARY", "450000")   // ~112K tokens (safe margin)
        buildConfigField("int", "MAX_CONTENT_FALLBACK", "450000")  // ~112K tokens (safe margin)


                // Cerebras Configuration (ultra-fast inference

        val cerebrasApiKey = project.findProperty("CEREBRAS_API_KEY") as? String ?: ""

        buildConfigField("String", "CEREBRAS_API_KEY", "\"$cerebrasApiKey\"")
        buildConfigField("String", "CEREBRAS_BASE_URL", "\"https://api.cerebras.ai/v1/\"")
// Updated model string below:
        buildConfigField("String", "CEREBRAS_MODEL", "\"gpt-oss-120b\"")


        


    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // Core Android KTX - Updated versions compatible with AGP 8.9.1
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Activity and Fragment (required for AGP 8.9.1)
    implementation("androidx.activity:activity-ktx:1.12.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(libs.androidx.activity)

    // Room Database (2026 latest version compatible with AGP 8.9.1)
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Kotlin Coroutines (latest 2026 version)
    val coroutinesVersion = "1.10.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Lifecycle Components (updated for AGP 8.9.1)
    val lifecycleVersion = "2.8.7"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")

    // Jetpack WebKit for modern WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // Navigation Component (optional but recommended)
    val navVersion = "2.8.5"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Network dependencies for Groq API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")


    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
}