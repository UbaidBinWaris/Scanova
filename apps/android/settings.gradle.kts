pluginManagement {
    includeBuild("build-logic")
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

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "scanova-android"

include(":app")

include(":core:common")
include(":core:designsystem")
include(":core:security")
include(":core:database")
include(":core:network")
include(":core:data")

include(":feature:pairing")
include(":feature:auth")
include(":feature:scan")
include(":feature:documents")
include(":feature:settings")
include(":feature:tools")
