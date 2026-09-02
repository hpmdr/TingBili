pluginManagement {
    repositories {
        // 国内镜像优先，加速构建；保留官方源作为回退
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
rootProject.name = "TingBili"
include(":app", ":core:ui", ":core:data", ":core:media", ":data:bilibili", ":feature:home", ":feature:playlist", ":feature:history", ":feature:player", ":feature:settings")
