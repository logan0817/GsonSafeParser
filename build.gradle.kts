import com.android.build.gradle.LibraryExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
    // 根工程保留 Kotlin JVM 插件版本，测试和脚本场景可继续按需使用。
    kotlin("jvm") version "2.0.21" apply false
    // demo-app 使用 Android Application 插件，库模块使用 Android Library 插件发布 AAR。
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // demo 和库模块都使用 Kotlin Android 插件，版本与 JVM Kotlin 保持一致。
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // 发布到公开 Maven 仓库时需要对产物签名；根工程声明核心插件，子模块按需应用。
    id("signing")
    // Kotlin 源码没有传统 Java javadoc，发布时用 Dokka 生成可阅读的 API 文档。
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

subprojects {
    group = "io.github.logan0817"
    // 开源发布版本，README 和发布产物保持一致。
    version = "1.0.3"

    plugins.withId("com.android.library") {
        val licenseResourcesDir = layout.buildDirectory.dir("generated/licenseResources/main")
        val generateLicenseResources = tasks.register<Copy>("generateLicenseResources") {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
            from(rootProject.file("NOTICE")) {
                into("META-INF")
            }
            into(licenseResourcesDir)
        }
        extensions.configure<LibraryExtension>("android") {
            sourceSets.getByName("main").resources.srcDir(licenseResourcesDir)
            packaging {
                // Android Gradle Plugin 默认会排除部分 META-INF 许可证文件；AAR 主产物需要显式保留。
                resources.excludes.remove("META-INF/LICENSE")
                resources.excludes.remove("META-INF/LICENSE.txt")
                resources.excludes.remove("META-INF/NOTICE")
                resources.excludes.remove("META-INF/NOTICE.txt")
            }
        }
        tasks.matching { task -> task.name.endsWith("JavaRes") || task.name.endsWith("JavaResource") }.configureEach {
            dependsOn(generateLicenseResources)
        }
        tasks.withType<Zip>().matching { task -> task.name.startsWith("bundle") && task.name.endsWith("Aar") }.configureEach {
            dependsOn(generateLicenseResources)
            // AAR 主产物根层带上许可证，便于发布后直接从二进制包确认。
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
            from(rootProject.file("NOTICE")) {
                into("META-INF")
            }
        }

        tasks.withType<Test>().configureEach {
            // Android Library 的本地单测继续使用 JUnit 5。
            useJUnitPlatform()
        }

        // Kotlin 源码发布前先接入 Dokka，避免 javadoc.jar 只有 MANIFEST。
        plugins.apply("org.jetbrains.dokka")
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension>("dokka") {
            dokkaPublications.configureEach {
                // 发布验证必须能在 CI 和本机离线稳定生成，不能依赖外部 package-list 可用性。
                offlineMode.set(true)
            }
            dokkaSourceSets.configureEach {
                enableAndroidDocumentationLink.set(false)
                enableJdkDocumentationLink.set(false)
                enableKotlinStdLibDocumentationLink.set(false)
            }
        }
        tasks.register<Jar>("dokkaJavadocJar") {
            archiveClassifier.set("javadoc")
            // Dokka HTML 是 Kotlin 官方推荐的 API 文档格式，这里仍按 Maven 约定打成 javadoc classifier。
            dependsOn("dokkaGeneratePublicationHtml")
            from(layout.buildDirectory.dir("dokka/html"))
        }
    }

    tasks.withType<Jar>().configureEach {
        // sources.jar 和 javadoc.jar 也带上许可证与 NOTICE；AAR 主产物通过 Android Java resources 单独打入。
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
    }
}

apply(from = "gradle/release-publishing.gradle.kts")
apply(from = "gradle/release-verification.gradle.kts")
