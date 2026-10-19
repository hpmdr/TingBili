plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.debubu.tingbili"
    compileSdk = 37

    // 多电脑共用 debug 签名（app/debug.keystore，维护者已明确批准入库）；
    // release 签名严禁入库。统一签名后测试机换电脑装包无需卸载重装。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release 签名只认环境变量（CI Secrets 解码后传入），keystore 永不入库。
        // 本地没有 keystore 文件时保持未签名：CI 侧缺 Secrets 会直接失败，见 release.yml。
        create("release") {
            System.getenv("RELEASE_STORE_FILE")?.let { storePath ->
                storeFile = file(storePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("release") {
            // 有 keystore 才挂签名：本地直接 assembleRelease 不会炸，CI 一定带签名出包。
            // （minify 暂不开：先保证发版链路跑通，瘦身另起一事。）
            System.getenv("RELEASE_STORE_FILE")?.let { storePath ->
                if (file(storePath).exists()) signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    defaultConfig {
        applicationId = "cn.debubu.tingbili"
        minSdk = 31
        targetSdk = 37
        // 发版时由 release.yml 传入：APP_VERSION_NAME 取自 tag（如 v1.1.0→1.1.0），
        // APP_VERSION_CODE 取 CI run_number（单调递增即可）；本地默认 1/1.0。
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2048m"
                it.jvmArgs("-Xmx2048m", "-XX:MaxMetaspaceSize=512m")
            }
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:media"))
    implementation(project(":core:data"))
    implementation(project(":data:bilibili"))
    implementation(project(":feature:home"))
    implementation(project(":feature:playlist"))
    implementation(project(":feature:history"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:search"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.adaptive.navigation.suite)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinx.serialization.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.hilt.android)
    implementation(libs.security.crypto)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.material3)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.navigation:navigation-testing:${libs.versions.navigation.get()}")
    testImplementation(libs.hilt.navigation.compose)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.arch.core.testing)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
