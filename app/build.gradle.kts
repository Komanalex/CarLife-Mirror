import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.carlifemirror.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carlifemirror.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 62
        versionName = "2.6.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    rename { "carlifemirror.2.6.0.apk" }
}

tasks.register<Copy>("copyNamedReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("app-release.apk")
    into(layout.buildDirectory.dir("outputs/named"))
    rename { "carlifemirror.2.6.0-release.apk" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
