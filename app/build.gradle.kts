import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins { id("com.android.application") }

val mirrorReleaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.mirror.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mirror.app"
        minSdk = 26
        targetSdk = 36
        versionCode = mirrorReleaseVersion.getProperty("mirrorVersionCode").toInt()
        versionName = mirrorReleaseVersion.getProperty("mirrorVersionName")
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
    // our stride-aware YUV converter and framework JPEG APIs instead.
    packaging { jniLibs.excludes += "**/*.so" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.bouncycastle:bctls-jdk18on:1.81")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Use Mirror's existing BC family; PDFBox otherwise bundles duplicate legacy classes.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

