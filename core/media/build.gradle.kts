plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "cn.debubu.tingbili.core.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(project(":core:data"))
    implementation(project(":data:bilibili"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation("androidx.media3:media3-datasource:${libs.versions.media3.get()}")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
