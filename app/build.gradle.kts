plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ryosphysrockme.audiosync"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ryosphysrockme.audiosync"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    testImplementation("junit:junit:4.13.2")
}
