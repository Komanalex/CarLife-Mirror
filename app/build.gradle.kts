plugins {
    id("com.android.application")
}

android {
    namespace = "com.carlifemirror.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carlifemirror.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 49
        versionName = "2.4.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Build a release APK with: ./gradlew assembleRelease
            // It is unsigned until a signing config is added.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.register<Copy>("copyNamedDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug"))
    include("app-debug.apk")
    into(layout.buildDirectory.dir("outputs/named"))
    rename { "carlifemirror.2.4.1.apk" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
