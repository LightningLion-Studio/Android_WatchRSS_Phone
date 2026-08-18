import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("shot") version "6.1.0"
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreProperties = keystorePropertiesFile.exists()
if (hasKeystoreProperties) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun productionSetting(name: String): String =
    localProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: when (name) {
            "WATCHRSS_APP_ACCESS_PUBLIC_KEY" -> "config/app_access_public_key.pem"
            "WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY" -> "config/app_access_test_public_key.pem"
            else -> null
        }?.let { relativePath ->
            rootProject.file(relativePath)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
        }
        ?: ""

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")}\""

android {
    namespace = "com.lightningstudio.watchrss.phone"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.lightningstudio.watchrss.phone"
        minSdk = 30
        targetSdk = 36
        versionCode = 39
        versionName = "1.2.2-9"
        buildConfigField(
            "String",
            "WATCHRSS_PRODUCTION_BACKEND_BASE_URL",
            productionSetting("WATCHRSS_BACKEND_BASE_URL").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "WATCHRSS_PRODUCTION_SUPABASE_ANON_KEY",
            productionSetting("WATCHRSS_SUPABASE_ANON_KEY").asBuildConfigString()
        )
        buildConfigField("String", "WATCHRSS_TEST_SUPABASE_ANON_KEY", "\"\"")
        buildConfigField(
            "String",
            "WATCHRSS_APP_ACCESS_PUBLIC_KEY",
            productionSetting("WATCHRSS_APP_ACCESS_PUBLIC_KEY").asBuildConfigString()
        )
        buildConfigField("String", "WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY", "\"\"")
        buildConfigField(
            "String",
            "WATCHRSS_OPPO_PUSH_APP_KEY",
            productionSetting("WATCHRSS_OPPO_PUSH_APP_KEY").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "WATCHRSS_OPPO_PUSH_APP_SECRET",
            productionSetting("WATCHRSS_OPPO_PUSH_APP_SECRET").asBuildConfigString()
        )

        testInstrumentationRunner = "com.karumi.shot.ShotTestRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "WATCHRSS_TEST_SUPABASE_ANON_KEY",
                productionSetting("WATCHRSS_TEST_SUPABASE_ANON_KEY").asBuildConfigString()
            )
            buildConfigField(
                "String",
                "WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY",
                productionSetting("WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY").asBuildConfigString()
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycleRuntimeKtx.get()}")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.ui:ui-graphics")
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc10")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.okhttp)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(libs.jsoup)
    implementation(libs.reorderable)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // OPPO Push SDK 3.7.1 (committed aar; uses Android's built-in org.json, no extra deps).
    implementation(files("libs/com.heytap.msp_V3.7.1.aar"))
    ksp(libs.androidx.room.compiler)
    // Backdrop source copied locally — see app/src/main/java/com/kyant/backdrop

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.shot.android)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
