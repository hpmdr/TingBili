import java.util.Properties
plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Release 签名取值顺序：项目根目录 keystore.properties（本地 AS 惯例，不入库）
// → 环境变量（CI Secrets 解码后传入）；两边都没有则保持未签名，CI 缺 Secrets 直接失败。
// 发版文件名：TingBili_{version}_android.apk（对标 PiliPlus，版本号进文件名）。
// defaultConfig.versionName 与这里同源（CI 由 tag 传入 APP_VERSION_NAME）。
val releaseApkName = "TingBili_${System.getenv("APP_VERSION_NAME") ?: "1.0"}_android.apk"

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
}
fun signingProp(name: String, envName: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(envName)

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
        create("release") {
            signingProp("storeFile", "RELEASE_STORE_FILE")?.let { storePath ->
                storeFile = file(storePath)
                storePassword = signingProp("storePassword", "RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = signingProp("keyAlias", "RELEASE_KEY_ALIAS") ?: ""
                keyPassword = signingProp("keyPassword", "RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("release") {
            // 生产包：R8 全量混淆 + 资源收缩（61M→6M，2026-10-20 实测；真机冷启动/播放/字幕回归通过）。
            // 规则只有保底三条（proguard-rules.pro），各库自带 consumer rules 已 cover。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore 才挂签名：本地直接 assembleRelease 不会炸，CI 一定带签名出包。
            signingProp("storeFile", "RELEASE_STORE_FILE")?.let { storePath ->
                if (file(storePath).exists()) signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            // kotlinx-coroutines 的调试探针元数据，运行时用不到
            excludes += "DebugProbesKt.bin"
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
    implementation(project(":feature:mine"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:search"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

// AGP 9 已移除 applicationVariants 老写法：release 的 APK 在这里改名。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(releaseApkName)
        }
    }
}
