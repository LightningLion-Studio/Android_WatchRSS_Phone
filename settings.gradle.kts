pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "shot") {
                useModule("com.karumi:shot:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        // OPPO Push SDK public release repo (credentials are the documented public nexus account).
        maven("https://maven.columbus.heytapmobi.com/repository/releases/") {
            credentials {
                username = "nexus"
                password = "c0b08da17e3ec36c3870fed674a0bcb36abc2e23"
            }
        }
    }
}

rootProject.name = "WatchRSS Phone"
include(":app")
