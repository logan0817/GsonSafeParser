pluginManagement {
    repositories {
        // 插件仓库保持 Gradle 官方、Maven Central、Google 三个来源，兼容 Kotlin 和后续 Android 插件。
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // 依赖仓库统一放在 settings，避免子模块各自声明仓库导致构建来源不一致。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "GsonSafeParser"
// core 放 Gson 扩展能力，retrofit 只做接入层，不把 Retrofit 依赖压到 core。
include(":gson-safe-parser-core")
include(":gson-safe-parser-retrofit")
// demo-app 是实际 Android 示例应用，用来在设备或模拟器上手动验证每个公开功能入口。
include(":demo-app")
