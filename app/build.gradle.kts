import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

// 签名信息优先读环境变量（CI 用），其次读 local.properties（本机用）。
// 两者都不会提交：local.properties 与 keystore/ 均已写入 .gitignore。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(envKey: String, localKey: String): String? =
    (System.getenv(envKey) ?: localProperties.getProperty(localKey))?.takeIf { it.isNotBlank() }

// CI 发布 debug 包时，用固定的 release 签名，避免每次构建的 debug 签名不同
// 导致用户无法覆盖升级。本地不加这个环境变量，仍使用默认 debug 签名。
val signDebugWithRelease = System.getenv("SIGN_DEBUG_WITH_RELEASE") == "true"

val releaseStoreFile = signingValue("KEYSTORE_FILE", "RELEASE_STORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("KEY_ALIAS", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "RELEASE_KEY_PASSWORD")

android {
    // namespace 决定内部 R 类 / Kotlin 源码包名，跟 applicationId（用户和系统
    // 看到的真正的包名/应用 ID）是两回事。源码没有整体重命名（工作量太大、
    // 收益主要在"反编译才能看到"这一层），所以这里保持不变；应用真正对外的
    // 包名由下面 defaultConfig.applicationId 决定。
    namespace = "jarvay.workpaper"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.aksb.liveshift"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 261
        versionName = "2.61"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没有配置签名（本地没放 keystore、CI 没传 secrets）时保持未签名，
            // 保证 assembleDebug 等普通构建不受影响。
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["APP_NAME"] = "@string/app_name"
        }

        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["APP_NAME"] = "@string/app_name_debug"
            if (signDebugWithRelease) {
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("exportSchema", "true")
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.junit.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.androidx.hilt.common)
    implementation(libs.hilt.work)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.runtime.livedata)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.gson)
    implementation(libs.datastore.preferences)

    implementation(libs.androidx.glance.glance.appwidget2)
    implementation(libs.glance.material3)
    implementation(libs.androidx.glance.material)

    implementation(libs.utilcodex)

    implementation(fileTree("libs"))
}