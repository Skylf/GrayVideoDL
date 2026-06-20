pluginManagement {
    repositories {
        // 阿里云镜像（国内访问加速，推荐）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 腾讯云镜像（阿里云找不到时备选）
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        // 官方源作为最终备选（仅在阿里云和腾讯云都没有时使用，速度可能较慢）
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像（国内访问加速，推荐）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 腾讯云镜像（阿里云找不到时备选）
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        // 官方源作为备选
        google()
        mavenCentral()
    }
}

rootProject.name = "GrayVideoDL"
include(":app")
 