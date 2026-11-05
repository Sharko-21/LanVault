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

rootProject.name = "LanVault"

include(
    ":app",
    ":core:model",
    ":core:database",
    ":core:media",
    ":core:network",
    ":core:security",
    ":core:sync",
    ":core:designsystem",
    ":core:transport",
)
