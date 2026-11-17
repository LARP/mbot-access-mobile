plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mbot.access.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mbot.access.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    sourceSets {
        // el script del asistente es el MISMO que usa `phone advanced start` (helper/mbot-helper.sh): la forma B lo ejecuta por adb
        getByName("main").assets.srcDir("../../helper")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Solo pruebas JVM (no se empaqueta en el APK). JUnit 4: licencia EPL-1.0. Ver NOTICE.
    testImplementation("junit:junit:4.13.2")
}
