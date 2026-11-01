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

rootProject.name = "GkTeDPI"
include(":app", ":gktedpi-connect-sdk")
project(":gktedpi-connect-sdk").projectDir = file("sdk/gktedpi-connect-sdk")
