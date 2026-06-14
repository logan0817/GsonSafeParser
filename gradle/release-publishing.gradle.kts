import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

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
    plugins.withId("maven-publish") {
        // 只有准备发布 Maven 产物的模块才应用 signing，demo-app 不参与开源库发布。
        plugins.apply("signing")
        configurePublishingRepositories()
    }

    plugins.withId("com.android.library") {
        plugins.withId("maven-publish") {
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
                            artifact(tasks.named("dokkaJavadocJar"))
                            configureCommonPom(project)
                        }
                    }
                }
                configurePublicationSigning()
            }
        }
    }
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
