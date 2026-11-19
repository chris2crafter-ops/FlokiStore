import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chris2crafter.thorstore"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chris2crafter.thorstore"
        minSdk = 30
        targetSdk = 35
        versionCode = 12
        versionName = "1.10"
    }

    // Every build must carry the same signature, or Android refuses to install it over the
    // installed store (and the self-update stops working). The key lives outside the repo;
    // local.properties points to it: flokistore.keystore=/path/to/flokistore.keystore
    val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    val keystore = local.getProperty("flokistore.keystore")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("store") {
                storeFile = keystore
                storePassword = local.getProperty("flokistore.storePassword", "android")
                keyAlias = local.getProperty("flokistore.keyAlias", "androiddebugkey")
                keyPassword = local.getProperty("flokistore.keyPassword", "android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("store")
        }
        debug {
            signingConfigs.findByName("store")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
