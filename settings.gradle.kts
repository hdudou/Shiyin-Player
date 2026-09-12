// 项目级 Gradle 配置
// 说明：本机网络环境下 dl.google.com / maven.google.com 不可达，
// 故将 google() / mavenCentral() / gradlePluginPortal() 统一替换为阿里云镜像。
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 兜底：万一镜像缺失个别构件，可保留原站点（当前被墙，默认注释）
        // google()
        // mavenCentral()
        // gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "MusicPlayer"
include(":app")
