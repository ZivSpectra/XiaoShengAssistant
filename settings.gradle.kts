// 项目设置文件：声明从哪里下载依赖库，以及项目里包含哪些模块
pluginManagement {
    repositories {
        // 构建环境对 Maven Central 的证书链不完整，使用镜像先解析依赖。
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()            // Google 官方库
        mavenCentral()      // 中央仓库
        gradlePluginPortal() // Gradle 插件仓库
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "XueXiTongAssistant"  // 项目名
include(":app")                          // 只包含 app 这一个模块
