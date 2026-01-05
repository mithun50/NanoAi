plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nanoai.llm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nanoai.llm"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK configuration for llama.cpp
        ndk {
            // Support ARM64 (most modern phones) and ARM32 (older devices)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // CMake arguments for native build
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_ARM_NEON=ON",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_NATIVE=OFF",
                    "-DLLAMA_LTO=OFF"
                )
                cppFlags += listOf(
                    "-O3",
                    "-ffast-math",
                    "-funroll-loops",
                    "-fPIC"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    // Native build configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/*.so")
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle components for coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Jsoup for web scraping (RAG)
    implementation("org.jsoup:jsoup:1.17.2")

    // JSON parsing for vector store persistence
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
