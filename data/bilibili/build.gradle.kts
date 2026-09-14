plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.debubu.tingbili.data.bilibili"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinx.serialization.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.androidx.test.core)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
