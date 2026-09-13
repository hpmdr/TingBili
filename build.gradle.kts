// root build.gradle.kts — only plugins alias, no subproject config
plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
}

// Hilt 注解处理器捆绑的 kotlin-metadata-jvm 若落后于 Kotlin 发出的元数据版本，
// hiltJavaCompile 会报 "Provided Metadata instance has version X"；强制对齐到当前 Kotlin 版本。
allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
        }
    }
}
