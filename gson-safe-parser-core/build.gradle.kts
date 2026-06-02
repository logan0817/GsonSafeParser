plugins {
    // core 面向 Android 项目发布 AAR，consumer ProGuard 规则会随依赖自动合并进用户 App。
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "io.github.logan.gsonsafeparser.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Gson 是对外 API 的一部分，调用方需要直接看到 Gson 类型。
    api("com.google.code.gson:gson:2.13.2")
    // org.json Adapter 只在 core 内部实现使用，不暴露给 Android 接入方，避免和 Android 内置 org.json 形成传递依赖冲突。
    compileOnly("org.json:json:20251224")
    // Kotlin data class 默认值构造依赖 kotlin-reflect。
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.json:json:20251224")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
