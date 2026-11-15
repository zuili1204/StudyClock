pluginManagement {
    repositories {
        google()
        maven("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
    }
}
rootProject.name = "StudyClock"
include(":app")
