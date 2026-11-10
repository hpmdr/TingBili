plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.debubu.tingbili.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 更新检查基准：打包瞬间墙钟（秒）。每次 configuration 重新求值；
        // debug 包跳过自动检查，手动检查时本地永远最新，所以无影响。
        //
        // BUILD_TIME_OVERRIDE 只给真机验证用（debug 包被自动检查跳过、本地 versionName 又是
        // 占位符 "1.0"，两个条件都凑不出来，没法在测试机上复现「发现新版本」）。
        // 不设该变量时行为与原来完全一致；发版流水线不会设置它。
        val buildTimeSec = System.getenv("BUILD_TIME_OVERRIDE")?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000L)
        buildConfigField("long", "BUILD_TIME", "${buildTimeSec}L")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
