import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.healer.hyperos.swipegesture"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.healer.hyperos.swipegesture"
        minSdk = 33
        targetSdk = 35
        versionCode = 10001
        versionName = "1.0.0"
    }

    val signingProps = Properties().apply {
        val localSigningFile = rootProject.file("signing.local.properties")
        if (localSigningFile.isFile) {
            localSigningFile.inputStream().use { load(it) }
        }
    }
    val releaseStorePath = System.getenv("RELEASE_STORE_FILE")
        ?: signingProps.getProperty("storeFile")
    val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
        ?: signingProps.getProperty("storePassword")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
        ?: signingProps.getProperty("keyAlias")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        ?: signingProps.getProperty("keyPassword")
    val hasReleaseSigning = releaseStoreFile != null &&
        releaseStoreFile.isFile &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        disable += setOf(
            "PrivateApi",
            "DiscouragedPrivateApi",
            "DiscouragedApi",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.miuix.android)
    implementation(libs.miuix.icons.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.libxposed.api)
}
