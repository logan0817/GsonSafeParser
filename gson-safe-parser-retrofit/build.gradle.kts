plugins {
    // retrofit 面向 Android 项目发布 AAR，依赖 core AAR 并自动传递框架 consumer rules。
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "io.github.logan.gsonsafeparser.retrofit"
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
    // 本地构建使用项目依赖，发布到 Maven 时 Gradle 会写出 core 的正式坐标。
    api(project(":gson-safe-parser-core"))
    // 公开 API 返回 retrofit2.Converter.Factory，消费者编译期需要 Retrofit 本体类型。
    api("com.squareup.retrofit2:retrofit:2.8.1")
    // converter-gson 只在模块内部实现里使用，不暴露给消费者编译期。
    implementation("com.squareup.retrofit2:converter-gson:2.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
