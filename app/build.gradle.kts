import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application") }

android {
    namespace = "com.mirror.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mirror.app"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("mirrorVersionCode").get().toInt()
        versionName = providers.gradleProperty("mirrorVersionName").get()
    }
    signingConfigs {
        create("mirror") {
            storeFile = rootProject.file("keystore/mirror.jks")
            storePassword = providers.gradleProperty("mirrorStorePassword").get()
            keyAlias = providers.gradleProperty("mirrorKeyAlias").get()
            keyPassword = providers.gradleProperty("mirrorKeyPassword").get()
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("mirror") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mirror")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // CameraX's optional native conversion is disabled in CameraService. We use
    // our stride-aware YUV converter and framework JPEG/rotation APIs instead.
    packaging { jniLibs.excludes += "**/*.so" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
