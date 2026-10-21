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

rootProject.name = "Trex_kotlin"
if (!providers.gradleProperty("iosOnly").isPresent) include(":app")
include(":posture-core")
// iPhone 화면: Android 정본 Compose 파일을 그대로 컴파일한다(§108). iOS 빌드에서만 켠다.
if (providers.gradleProperty("iosOnly").isPresent) include(":ios-ui")
