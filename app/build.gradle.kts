import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 6
        versionName = "0.5.0-32k-context"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Expose the .litertlm model filename so instrumentation tests
        // and the dev build can override it.
        buildConfigField("String", "GEMMA_MODEL_FILENAME", "\"gemma-4-E2B-it.litertlm\"")
    }

    // Release signing is driven by environment variables so the keystore
    // never lives in the repo. In CI these come from GitHub Actions
    // secrets (see .github/workflows/build.yml). Locally, export the four
    // SPENDAI_SIGNING_* vars before running `assembleRelease`; when absent
    // the release APK is left unsigned so local dev builds still succeed.
    signingConfigs {
        create("release") {
            // Read signing secrets from the process environment (set by CI
            // from GitHub Actions secrets). System.getenv avoids the
            // android-extension receiver that hides Project.providers here.
            val storeFile = System.getenv("SPENDAI_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
            val storePassword = System.getenv("SPENDAI_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val keyAlias = System.getenv("SPENDAI_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val keyPassword = System.getenv("SPENDAI_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
            // Only wire the keystore when every secret is present AND the
            // keystore file actually exists on disk. Otherwise the release
            // variant builds unsigned (CI on a forked PR has no secrets).
            if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null &&
                file(storeFile).exists()
            ) {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
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
            // Sign with the env-driven release keystore when its secrets are
            // present; otherwise the variant is built unsigned (debug-signed
            // installs come from the `debug` variant).
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
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

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // AI / Inference — LiteRT-LM is the official on-device LLM runtime.
    // Provides Engine, EngineConfig, Conversation, Message, Backend, etc.
    // The artifact transitively includes the LiteRT (.tflite) runtime.
    implementation(libs.litertlm.android)

    // Play Services TFLite. The GPU variant gives LiteRT-LM a Google-maintained
    // OpenCL/Vulkan dispatch shim that loads dynamically on devices with
    // Play Services — this is the path Google AI Edge Gallery uses for its
    // GPU backend. Without it, LiteRT-LM falls back to the bundled shim
    // which silently fails on Mali GPUs (clEnqueueNDRangeKernel errors).
    implementation(libs.play.services.tflite.java)
    implementation(libs.play.services.tflite.gpu)
    implementation(libs.play.services.tflite.support)

    // Network — OkHttp for the in-app model downloader.
    implementation(libs.okhttp)

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
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.mockwebserver)

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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
