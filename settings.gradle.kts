pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "TingBili"
include(":app", ":core:ui", ":core:data", ":core:media", ":data:bilibili", ":feature:home", ":feature:playlist", ":feature:history", ":feature:player", ":feature:settings")
