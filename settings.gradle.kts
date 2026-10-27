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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MilanMusic"
include(":app")
// 应用级 baseline profile 的采集端。不产出应用，只产出一个 instrument :app 的测试 APK，
// 所以它不参与发布，也不影响 dist/ 里的产物结构。
include(":baselineprofile")
