import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val amapKey: String = localProperties.getProperty("AMAP_KEY", "")
val keystoreFile = rootProject.file(localProperties.getProperty("KEYSTORE_FILE", "navi.jks"))
val naviStorePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
val naviKeyAlias = localProperties.getProperty("KEY_ALIAS", "")
val naviKeyPassword = localProperties.getProperty("KEY_PASSWORD", naviStorePassword)
val hasReleaseKeystore = keystoreFile.isFile &&
    naviStorePassword.isNotBlank() &&
    naviKeyAlias.isNotBlank() &&
    naviKeyPassword.isNotBlank()

android {
    namespace = "com.hu.nav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hu.nav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
        manifestPlaceholders["AMAP_KEY"] = amapKey
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("navi") {
                storeFile = keystoreFile
                storePassword = naviStorePassword
                keyAlias = naviKeyAlias
                keyPassword = naviKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("navi")
            }
        }
        debug {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("navi")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // navi-3dmap 已含定位，不要再单独加 location，否则 class 重复。
    implementation("com.amap.api:navi-3dmap:10.0.800_3dmap10.0.800")
    implementation("com.amap.api:search:9.7.0")

    testImplementation("junit:junit:4.13.2")
}
