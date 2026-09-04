// 阿里云镜像仅用于本地（国内）加速；GitHub Actions 的 runner 在海外，
// 访问 aliyun 会超时/触发 CDN 鉴权，必须走官方源，否则插件阶段解析失败。
// CI 环境变量由 GitHub Actions 自动注入。
pluginManagement {
    repositories {
        if (System.getenv("CI") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "TingBili"
include(":app", ":core:ui", ":core:data", ":core:media", ":data:bilibili", ":feature:home", ":feature:playlist", ":feature:history", ":feature:player", ":feature:settings")
