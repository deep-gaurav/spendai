import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.spendai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spendai.app"
        // Engineered for long-term support and modern API surface.
        // minSdk 26 (Android 8.0) is the floor for the LiteRT-LM JNI binaries
        // and required by current WorkManager constraints.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Expose the .litertlm model filename so instrumentation tests
        // and the dev build can override it.
        buildConfigField("String", "GEMMA_MODEL_FILENAME", "\"gemma-4-e2b-it.litertlm\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No keystore configured in Phase 1. Sign with `apksigner` manually
            // or wire a signingConfig when release builds are needed.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.3+ requires the compilerOptions DSL; the legacy
    // kotlinOptions {} block is a hard error.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Surface experimental LiteRT-LM APIs (ExperimentalFlags, etc.).
            freeCompilerArgs.addAll(
                "-opt-in=com.google.ai.edge.litertlm.ExperimentalApi"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // Keep LiteRT-LM JNI .so files uncompressed so the linker can mmap them.
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // AI / Inference — LiteRT-LM is the official on-device LLM runtime.
    // Provides Engine, EngineConfig, Conversation, Message, Backend, etc.
    // The artifact transitively includes the LiteRT (.tflite) runtime.
    implementation(libs.litertlm.android)

    // Unit tests
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
