plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.debubu.tingbili.core.data"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinx.serialization.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    testImplementation(libs.robolectric)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.arch.core.testing)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
