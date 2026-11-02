plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.example.sleeptrackersimulator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sleeptrackersimulator"
        minSdk = 31
        targetSdk = 37
        versionCode = 2000001
        versionName = "2.0-RC1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val requireProduction = System.getenv("REQUIRE_PRODUCTION_SIGNING")?.toBoolean() == true ||
                project.findProperty("requireProductionSigning")?.toString()?.toBoolean() == true
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: project.findProperty("releaseKeystorePath")?.toString()
            val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("releaseKeystorePassword")?.toString()
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("releaseKeyAlias")?.toString()
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("releaseKeyPassword")?.toString()

            val hasKeystorePath = !keystorePath.isNullOrBlank()
            if (hasKeystorePath) {
                val store = file(keystorePath!!)
                if (!store.exists()) {
                    throw org.gradle.api.InvalidUserDataException("Configured RELEASE_KEYSTORE_PATH does not exist: $keystorePath")
                }
                if (keystorePassword.isNullOrBlank() || keyAlias.isNullOrBlank() || keyPassword.isNullOrBlank()) {
                    throw org.gradle.api.InvalidUserDataException("Incomplete production signing credentials: RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD must all be specified.")
                }
                storeFile = store
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                logger.lifecycle("PRODUCTION-SIGNING: Release build configured with explicit release keystore ($keystorePath).")
            } else if (requireProduction) {
                throw org.gradle.api.InvalidUserDataException("Production signing credentials required (REQUIRE_PRODUCTION_SIGNING=true), but RELEASE_KEYSTORE_PATH was not set.")
            } else {
                val debugConfig = getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                this.keyAlias = debugConfig.keyAlias
                this.keyPassword = debugConfig.keyPassword
                logger.warn("WARNING: Production signing credentials not provided. Release artifact will be DEBUG-SIGNED for internal testing only.")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

dependencies {
    implementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    implementation(libs.firebase.auth)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.tensorflow.lite)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
