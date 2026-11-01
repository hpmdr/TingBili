plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.debubu.tingbili.core.biliui"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Robolectric 需要合并后的 manifest/资源才能起应用上下文（其余模块都有这行）
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":data:bilibili"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)

    // 该模块原本零测试：纯函数 + 两个 Compose 冒烟用例
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
