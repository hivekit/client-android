plugins {
    id("com.android.application")
    kotlin("android")
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("v0.1.1").get()

android {
    namespace = "io.hivekit.consumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.hivekit.consumer"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = sdkVersion.removePrefix("v")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}

dependencies {
    implementation("com.github.hivekit:client-android:$sdkVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
