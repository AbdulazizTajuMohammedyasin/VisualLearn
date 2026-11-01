plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.visuallearn.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.visuallearn.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("visuallearn.apiBaseUrl").getOrElse("")}\"")
        buildConfigField("String", "UI_LIBRARY_VERSION", "\"1.0.0\"")
        // OAuth web client ID of the Google Cloud project the server verifies tokens against.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${providers.gradleProperty("visuallearn.googleWebClientId").getOrElse("")}\"")
    }

    // Release signing comes from the environment so no secrets live in the repo:
    // VL_KEYSTORE (path), VL_KEYSTORE_PASSWORD, VL_KEY_ALIAS, VL_KEY_PASSWORD.
    val keystore = System.getenv("VL_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("VL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VL_KEY_ALIAS")
                keyPassword = System.getenv("VL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // proot must exist as a real file in nativeLibraryDir so it can be exec'd
            // (apps targeting 29+ may not exec files from their data dir).
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    // credentials-play-services-auth pulls an old fragment; ActivityResult APIs need 1.3+.
    implementation(libs.androidx.fragment)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.compose.material.icons.core)
}
