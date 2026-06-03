plugins {
    // 示例 App 是 Android application，负责把库能力放到真实页面里验证。
    id("com.android.application")
    // 页面和 demo 用例运行器使用 Kotlin 编写。
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.logan.gsonsafeparser.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.logan.gsonsafeparser.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // release 变体用于验证用户 App 接入混淆配置的方式；默认使用 Android debug 签名，便于本机直接构建和安装测试。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    // 和 core/retrofit 的 JVM 产物保持一致，避免 Android 单测出现 Java/Kotlin target 不一致。
    jvmToolchain(17)
}

dependencies {
    // demo 同时验证 core 和 retrofit 两个公开接入模块。
    implementation(project(":gson-safe-parser-core"))
    implementation(project(":gson-safe-parser-retrofit"))
    // demo 里会直接创建 Retrofit、OkHttp 请求体和 Buffer，所以这些类型要显式声明依赖。
    implementation("com.squareup.retrofit2:retrofit:2.8.1")
    implementation("com.squareup.okhttp3:okhttp:3.14.7")
    implementation("com.squareup.okio:okio:1.17.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
}
