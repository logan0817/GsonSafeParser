import com.android.build.gradle.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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

val centralRepositoryUrl = providers.gradleProperty("releaseRepositoryUrl")
    .orElse(providers.environmentVariable("RELEASE_REPOSITORY_URL"))
    .orElse("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")

val centralPortalBaseUrl = providers.gradleProperty("centralPortalBaseUrl")
    .orElse(providers.environmentVariable("CENTRAL_PORTAL_BASE_URL"))
    .orElse("https://ossrh-staging-api.central.sonatype.com")

val centralPortalNamespace = providers.gradleProperty("mavenCentralNamespace")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_NAMESPACE"))
    .orElse("io.github.logan0817")

val centralPublishingType = providers.gradleProperty("centralPublishingType")
    .orElse(providers.environmentVariable("CENTRAL_PUBLISHING_TYPE"))
    .orElse("user_managed")

fun sanitizeMavenCentralResponseBody(responseBody: String): String {
    if (responseBody.isBlank()) {
        return "Central Portal deployment response redacted: <empty>"
    }
    val compactBody = responseBody.lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .joinToString(separator = " ")
    val sensitiveKeyPattern = "(?:password|token|secret|authorization|credential|api[_-]?key|x-api-key|cookie|set-cookie)"
    val longSensitiveKeyPattern = "(?:authorization|credential|api[_-]?key|x-api-key|cookie|set-cookie)"
    val bearerRedacted = Regex("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+")
        .replace(compactBody) { match -> "${match.groupValues[1]}[redacted]" }
    val doubleQuotedValueRedacted = Regex(
        "(?i)(\"?$sensitiveKeyPattern\"?\\s*[:=]\\s*\")((?:\\\\.|[^\"\\\\])*)(\")"
    ).replace(bearerRedacted) { match ->
        "${match.groupValues[1]}[redacted]${match.groupValues[3]}"
    }
    val singleQuotedValueRedacted = Regex(
        "(?i)(\"?$sensitiveKeyPattern\"?\\s*[:=]\\s*')((?:\\\\.|[^'\\\\])*)(')"
    ).replace(doubleQuotedValueRedacted) { match ->
        "${match.groupValues[1]}[redacted]${match.groupValues[3]}"
    }
    val longUnquotedValueRedacted = Regex(
        "(?i)(\"?$longSensitiveKeyPattern\"?\\s*[:=]\\s*)([^\"'\\n{}]+)"
    ).replace(singleQuotedValueRedacted) { match ->
        "${match.groupValues[1]}[redacted]"
    }
    val keyValueRedacted = Regex(
        "(?i)(\"?$sensitiveKeyPattern\"?\\s*[:=]\\s*)([^\"'\\s,;{}]+)"
    ).replace(longUnquotedValueRedacted) { match ->
        "${match.groupValues[1]}[redacted]"
    }
    val maxExcerptChars = 1024
    val excerpt = if (keyValueRedacted.length > maxExcerptChars) {
        keyValueRedacted.take(maxExcerptChars) + "...<truncated>"
    } else {
        keyValueRedacted
    }
    return "Central Portal deployment response redacted. Sanitized excerpt: $excerpt"
}

fun validateCentralPortalBaseUrl(
    baseUrl: String
): String {
    val trimmedBaseUrl = baseUrl.trim().trimEnd('/')
    val uri = URI(trimmedBaseUrl)
    if (!uri.scheme.equals("https", ignoreCase = true)) {
        throw GradleException("Central Portal base URL must use HTTPS.")
    }
    val host = uri.host?.lowercase().orEmpty()
    if (host != "central.sonatype.com" && !host.endsWith(".central.sonatype.com")) {
        throw GradleException("Central Portal base URL must use a Sonatype Central host.")
    }
    return trimmedBaseUrl
}

gradle.taskGraph.whenReady {
    val createsCentralDeployment = allTasks.any { task ->
        task.path == ":uploadMavenCentralDeployment" ||
            task.path == ":publishToMavenCentral" ||
            task.path == ":releaseToMavenCentral"
    }
    if (createsCentralDeployment) {
        validateCentralPortalBaseUrl(centralPortalBaseUrl.get())
    }
}

fun Project.remoteMavenPublicationRequested(): Boolean =
    gradle.taskGraph.allTasks.any { task ->
        task.name.startsWith("publish") &&
            task.name != "publishToMavenLocal" &&
            !task.name.endsWith("ToMavenLocal")
    }

fun MavenPublication.configureCommonPom(project: Project) {
    artifactId = project.name
    pom {
        name.set(project.name)
        description.set("Android-first Gson defensive parser extension.")
        url.set("https://github.com/logan0817/GsonSafeParser")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("logan0817")
                name.set("logan")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/logan0817/GsonSafeParser.git")
            developerConnection.set("scm:git:ssh://github.com/logan0817/GsonSafeParser.git")
            url.set("https://github.com/logan0817/GsonSafeParser")
        }
    }
}

fun Project.configurePublishingRepositories() {
    extensions.configure<PublishingExtension>("publishing") {
        repositories {
            maven {
                // 默认直接指向 Central Portal 的 OSSRH 兼容发布地址；需要切换仓库时再用属性覆盖。
                name = "ReleaseRepository"
                url = uri(validateCentralPortalBaseUrl(centralRepositoryUrl.get()))
                credentials {
                    username = providers.gradleProperty("mavenCentralUsername")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                        .orNull
                        .orEmpty()
                    password = providers.gradleProperty("mavenCentralPassword")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                        .orNull
                        .orEmpty()
                }
            }
        }
    }
}

fun Project.configurePublicationSigning() {
    val publishingProject = this
    extensions.configure<SigningExtension>("signing") {
        val signingKey = providers.gradleProperty("signingInMemoryKey")
            .orElse(providers.environmentVariable("SIGNING_IN_MEMORY_KEY"))
            .orNull
        val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
            .orElse(providers.environmentVariable("SIGNING_IN_MEMORY_KEY_PASSWORD"))
            .orNull
            .orEmpty()
        // 本机优先走 GPG Keychain 和 gpg-agent；如果显式提供内存私钥，再回退到内存签名。
        if (signingKey.isNullOrBlank()) {
            useGpgCmd()
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        // 只有远程发布任务才强制签名；publishToMavenLocal 继续保持零配置可用。
        setRequired {
            publishingProject.remoteMavenPublicationRequested()
        }
        sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
    }
    tasks.withType<Sign>().configureEach {
        onlyIf("remote Maven publication requires signatures") {
            publishingProject.remoteMavenPublicationRequested()
        }
    }
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
        val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
            archiveClassifier.set("javadoc")
            // Dokka HTML 是 Kotlin 官方推荐的 API 文档格式，这里仍按 Maven 约定打成 javadoc classifier。
            dependsOn("dokkaGeneratePublicationHtml")
            from(layout.buildDirectory.dir("dokka/html"))
        }
        plugins.withId("maven-publish") {
            plugins.apply("signing")
            components.withType<SoftwareComponent>().configureEach {
                if (name != "release") {
                    return@configureEach
                }
                val releaseComponent = this
                extensions.configure<PublishingExtension>("publishing") {
                    publications {
                        create<MavenPublication>("mavenJava") {
                            // Android 用户只消费 AAR；release component 会携带 AAR、sources 和依赖元数据。
                            from(releaseComponent)
                            artifact(dokkaJavadocJar)
                            configureCommonPom(project)
                        }
                    }
                }
                configurePublicationSigning()
            }
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

    plugins.withId("maven-publish") {
        // 只有准备发布 Maven 产物的模块才应用 signing，demo-app 不参与开源库发布。
        plugins.apply("signing")
        configurePublishingRepositories()
    }
}

tasks.register("verifyMavenLocalPublicationArtifacts") {
    group = "verification"
    description = "验证 Maven local 中的 AAR、POM、sources、javadoc 和 demo release 混淆合并配置。"
    dependsOn(
        ":gson-safe-parser-core:publishToMavenLocal",
        ":gson-safe-parser-retrofit:publishToMavenLocal",
        ":demo-app:assembleRelease"
    )

    doLast {
        val versionValue = project(":gson-safe-parser-core").version.toString()
        val artifactIds = listOf("gson-safe-parser-core", "gson-safe-parser-retrofit")
        artifactIds.forEach { artifactId ->
            val base = mavenLocalArtifactBase(artifactId, versionValue)
            val aar = base.resolveSibling("${base.name}.aar")
            val jar = base.resolveSibling("${base.name}.jar")
            val sourcesJar = base.resolveSibling("${base.name}-sources.jar")
            val javadocJar = base.resolveSibling("${base.name}-javadoc.jar")
            val pom = base.resolveSibling("${base.name}.pom")

            requireFile(aar)
            require(!jar.exists()) { "Unexpected plain jar artifact exists: ${jar.absolutePath}" }
            requireFile(sourcesJar)
            requireFile(javadocJar)
            requireFile(pom)
            require(pomPackaging(pom) == "aar") { "POM packaging must be aar: ${pom.absolutePath}" }

            requireZipEntries(
                aar,
                listOf("classes.jar", "proguard.txt", "META-INF/LICENSE", "META-INF/NOTICE")
            )
            val aarRules = readZipEntry(aar, "proguard.txt")
            listOf(
                "kotlin.Metadata",
                "com.google.gson.GsonBuilder",
                "@com.google.gson.annotations.SerializedName <fields>"
            ).forEach { requiredRule ->
                require(aarRules.contains(requiredRule)) {
                    "${aar.absolutePath} proguard.txt misses $requiredRule"
                }
            }
            requireZipEntryMatching(sourcesJar) { entryName -> entryName.endsWith(".kt") }
            requireZipEntries(javadocJar, listOf("index.html"))
        }

        val retrofitPom = mavenLocalArtifactBase("gson-safe-parser-retrofit", versionValue)
            .resolveSibling("gson-safe-parser-retrofit-$versionValue.pom")
        require(pomDependencyVersion(retrofitPom, "gson-safe-parser-core") == versionValue) {
            "Retrofit POM must depend on gson-safe-parser-core $versionValue"
        }
        require(pomDependencyVersion(retrofitPom, "okhttp") == "4.12.0") {
            "Retrofit POM must depend on okhttp 4.12.0"
        }
        require(pomDependencyVersion(retrofitPom, "okio") == "3.6.0") {
            "Retrofit POM must depend on okio 3.6.0"
        }

        val demoConfig = file("demo-app/build/outputs/mapping/release/configuration.txt")
        requireFile(demoConfig)
        val demoConfiguration = demoConfig.readText()
        listOf(
            "io.github.logan.gsonsafeparser.demo.model.**",
            "kotlin.Metadata",
            "com.google.gson.GsonBuilder",
            "@com.google.gson.annotations.SerializedName <fields>"
        ).forEach { requiredRule ->
            require(demoConfiguration.contains(requiredRule)) {
                "${demoConfig.absolutePath} misses $requiredRule"
            }
        }
    }
}

fun Project.mavenLocalArtifactBase(artifactId: String, versionValue: String) =
    file("${System.getProperty("user.home")}/.m2/repository/io/github/logan0817/$artifactId/$versionValue/$artifactId-$versionValue")

fun requireFile(file: java.io.File) {
    require(file.isFile) { "Missing file: ${file.absolutePath}" }
}

fun requireZipEntries(file: java.io.File, entries: List<String>) {
    val entryNames = zipEntryNames(file)
    entries.forEach { entry ->
        require(entryNames.contains(entry)) { "${file.absolutePath} misses $entry" }
    }
}

fun requireZipEntryMatching(file: java.io.File, predicate: (String) -> Boolean) {
    require(zipEntryNames(file).any(predicate)) { "${file.absolutePath} misses expected entry" }
}

fun zipEntryNames(file: java.io.File): List<String> {
    return ZipFile(file).use { zipFile ->
        zipFile.entries().asSequence().map { entry -> entry.name }.toList()
    }
}

fun readZipEntry(file: java.io.File, entryName: String): String {
    return ZipFile(file).use { zipFile ->
        val entry = zipFile.getEntry(entryName)
            ?: error("${file.absolutePath} misses $entryName")
        zipFile.getInputStream(entry).bufferedReader().use { reader -> reader.readText() }
    }
}

fun parsePom(file: java.io.File) =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).also { document ->
        document.documentElement.normalize()
    }

fun pomPackaging(file: java.io.File): String? {
    return parsePom(file).documentElement.childText("packaging")
}

fun pomDependencyVersion(file: java.io.File, artifactId: String): String? {
    val dependencies = parsePom(file).getElementsByTagName("dependency")
    for (index in 0 until dependencies.length) {
        val dependency = dependencies.item(index) as? Element ?: continue
        if (dependency.childText("artifactId") == artifactId) {
            return dependency.childText("version")
        }
    }
    return null
}

fun Element.childText(tagName: String): String? {
    return getElementsByTagName(tagName).item(0)?.textContent
}

tasks.register("uploadMavenCentralDeployment") {
    group = "publishing"
    description = "内部任务：发布产物上传完成后，创建 Central Portal deployment；一般不要手动点击。"
    // 这个任务只做最后一步：把 OSSRH 兼容 staging 仓库提交成 Central Portal deployment。
    // 真正的 AAR、sources.jar、javadoc.jar、pom 和签名文件上传，仍然由两个模块自己的 publish 任务完成。
    dependsOn(":gson-safe-parser-core:publish", ":gson-safe-parser-retrofit:publish")

    doLast {
        // 账号和密码只从本机 Gradle 配置或环境变量读取，不写进仓库，也不会打印到日志。
        val username = providers.gradleProperty("mavenCentralUsername")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
            .orNull
            .orEmpty()
        val password = providers.gradleProperty("mavenCentralPassword")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
            .orNull
            .orEmpty()
        if (username.isBlank() || password.isBlank()) {
            throw GradleException(
                "Missing Maven Central credentials. Configure mavenCentralUsername and mavenCentralPassword " +
                    "in local Gradle properties or environment variables before running publishToMavenCentral."
            )
        }

        // Gradle maven-publish 上传后，还需要调用 manual upload，Central Portal 页面才会出现 deployment。
        val uploadUrl = validateCentralPortalBaseUrl(
            centralPortalBaseUrl.get()
        ).let { safeCentralPortalBaseUrl ->
            URI(
                "$safeCentralPortalBaseUrl/manual/upload/defaultRepository/" +
                    "${centralPortalNamespace.get()}?publishing_type=${centralPublishingType.get()}"
            ).toURL()
        }
        // Sonatype 这里使用 Bearer + base64(username:password)，不要改成 Basic 认证。
        val bearerToken = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

        logger.lifecycle("Creating Central Portal deployment for namespace {}", centralPortalNamespace.get())

        val connection = uploadUrl.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        // manual upload 接口不需要业务请求体，这里显式写入空 body，避免不同 JDK 对 POST 的默认行为不一致。
        connection.outputStream.use { output ->
            output.write(ByteArray(0))
        }

        val responseCode = connection.responseCode
        // 成功和失败都读取响应内容；失败时带出服务端原因，方便排查 token、namespace 或 staging 状态问题。
        val responseBody = runCatching {
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            responseStream.bufferedReader().use { reader -> reader.readText() }
        }.getOrDefault("")

        if (responseCode !in 200..299) {
            throw GradleException(
                "Central Portal deployment creation failed with HTTP $responseCode. " +
                    sanitizeMavenCentralResponseBody(responseBody)
            )
        }

        logger.lifecycle(
            "Central Portal deployment created. Check https://central.sonatype.com/publishing/deployments " +
                "and publish the validated deployment manually."
        )
    }
}

tasks.register("publishToMavenCentral") {
    group = "publishing"
    description = "命令行发布入口：发布 core 和 retrofit，并创建 Central Portal deployment。"
    // 命令行发布入口：负责触发两个模块发布，并在上传完成后创建 Central Portal deployment。
    dependsOn("uploadMavenCentralDeployment")
}

tasks.register("releaseToMavenCentral") {
    group = "publishing"
    description = "IDE 一键发布入口：在 Android Studio Gradle 面板里发布时点这个任务。"
    // IDE 一键发布入口：给 Android Studio / IntelliJ Gradle 面板点击使用。
    // clean 先清理旧产物，publishToMavenCentral 会间接触发两个模块的 assemble、签名、上传和 deployment 创建。
    dependsOn(
        "clean",
        "publishToMavenCentral"
    )
}

fun Project.remotePublishTasksRunAfterClean() {
    tasks.matching { task ->
        task.name.startsWith("publish") &&
            task.name != "publishToMavenLocal" &&
            !task.name.endsWith("ToMavenLocal")
    }.configureEach {
        mustRunAfter(rootProject.tasks.named("clean"))
    }
}

allprojects {
    remotePublishTasksRunAfterClean()
}

tasks.named("uploadMavenCentralDeployment") {
    mustRunAfter(tasks.named("clean"))
}

tasks.named("publishToMavenCentral") {
    // releaseToMavenCentral 同时依赖 clean 和 publishToMavenCentral 时，显式保证先清理再发布。
    mustRunAfter("clean")
}
