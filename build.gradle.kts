// AGP's built-in Kotlin uses this stable compiler/plugin version.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}
plugins { id("com.android.application") version "9.3.2" apply false }
