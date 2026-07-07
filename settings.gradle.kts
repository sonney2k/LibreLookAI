pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LibreLookAI"
include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:ml")
include(":core:sync")
include(":core:ai")
include(":core:designsystem")
include(":core:weather")
include(":core:service")
include(":core:session")
include(":core:outfit")
include(":feature:auth")
include(":feature:billing")
include(":feature:onboarding")
include(":feature:tryon")
