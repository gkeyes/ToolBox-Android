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

rootProject.name = "ToolBox"

include(
    ":app",
    ":core-ui",
    ":core-data",
    ":tool-package",
    ":tool-runtime",
    ":tool-api",
)
