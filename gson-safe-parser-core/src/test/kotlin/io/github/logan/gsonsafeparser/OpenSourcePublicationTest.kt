package io.github.logan.gsonsafeparser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 开源发布信息测试。
 *
 * 这类测试不验证解析行为，而是锁住第一版发布前不能随意改动的公开命名。
 */
class OpenSourcePublicationTest {
    /**
     * Maven 坐标需要匹配 Central Portal 已验证的 namespace，公开 API 包名则保持无数字。
     *
     * 这样发布能通过 `io.github.logan0817` namespace 校验，用户代码 import 仍然使用 `io.github.logan.gsonsafeparser`。
     */
    @Test
    fun `published group matches central namespace while public api package stays clean`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("group = \"io.github.logan0817\""))
        assertEquals("io.github.logan.gsonsafeparser", GsonSafeParser::class.java.packageName)
    }

    /**
     * 发布配置需要包含远程仓库入口和签名钩子。
     *
     * `publishToMavenLocal` 只能证明本地产物能生成，不能证明第一版具备公开发布所需的签名和仓库配置。
     */
    @Test
    fun `root publishing config includes release repository and signing hooks`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("id(\"signing\")"))
        assertTrue(rootBuildFile.contains("releaseRepositoryUrl"))
        assertTrue(rootBuildFile.contains("mavenCentralUsername"))
        assertTrue(rootBuildFile.contains("mavenCentralPassword"))
        assertTrue(!rootBuildFile.contains("releaseRepositoryUsername"))
        assertTrue(!rootBuildFile.contains("releaseRepositoryPassword"))
        assertTrue(rootBuildFile.contains("ossrh-staging-api.central.sonatype.com"))
        assertTrue(rootBuildFile.contains("tasks.register(\"publishToMavenCentral\")"))
        assertTrue(rootBuildFile.contains("tasks.register(\"releaseToMavenCentral\")"))
        assertTrue(rootBuildFile.contains("tasks.register(\"uploadMavenCentralDeployment\")"))
        assertTrue(rootBuildFile.contains("remotePublishTasksRunAfterClean"))
        assertTrue(rootBuildFile.contains("task.name.startsWith(\"publish\")"))
        assertTrue(rootBuildFile.contains("tasks.named(\"uploadMavenCentralDeployment\")"))
        assertTrue(rootBuildFile.contains("manual/upload/defaultRepository"))
        assertTrue(rootBuildFile.contains("publishing_type=") && rootBuildFile.contains("user_managed"))
        assertTrue(rootBuildFile.contains("useGpgCmd()"))
        assertTrue(rootBuildFile.contains("useInMemoryPgpKeys("))
        assertTrue(rootBuildFile.contains("sign(") && rootBuildFile.contains("mavenJava"))
    }

    /**
     * CI 里的本地 Maven 发布只验证产物能生成，不应该要求 GitHub runner 准备 GPG 私钥。
     */
    @Test
    fun `local maven publication skips signing requirement`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("fun Project.remoteMavenPublicationRequested()"))
        assertTrue(rootBuildFile.contains("tasks.withType<Sign>().configureEach"))
        assertTrue(rootBuildFile.contains("onlyIf(\"remote Maven publication requires signatures\")"))
        assertTrue(rootBuildFile.contains("!task.name.endsWith(\"ToMavenLocal\")"))
    }

    /**
     * GitHub Actions 的 JavaScript action 需要使用 Node 24 兼容版本，避免 runner 升级后继续报警。
     */
    @Test
    fun `ci workflow uses node 24 compatible actions`() {
        val ciWorkflow = File("../.github/workflows/ci.yml").readText()

        assertTrue(ciWorkflow.contains("actions/checkout@v6.0.2"))
        assertTrue(ciWorkflow.contains("actions/setup-java@v5.2.0"))
        assertTrue(ciWorkflow.contains("gradle/actions/setup-gradle@v6.1.0"))
        assertTrue(ciWorkflow.contains("android-actions/setup-android@v4.0.1"))
        assertTrue(ciWorkflow.contains("--warning-mode=fail"))
        assertTrue(ciWorkflow.contains("Run Android release lint"))
        assertTrue(ciWorkflow.contains(":gson-safe-parser-core:lintRelease"))
        assertTrue(ciWorkflow.contains(":gson-safe-parser-retrofit:lintRelease"))
        assertTrue(ciWorkflow.contains(":demo-app:lintRelease"))
        assertTrue(ciWorkflow.contains("Verify Maven local publication artifacts"))
        assertTrue(ciWorkflow.contains("./gradlew verifyMavenLocalPublicationArtifacts --warning-mode=fail"))
    }

    /**
     * CI 必须有依赖漏洞扫描门禁。
     *
     * 单元测试、lint 和 assemble 只能证明当前源码能工作，不能发现 Maven 依赖后来披露的 CVE。
     */
    @Test
    fun `ci workflow includes dependency vulnerability scan gate`() {
        val ciWorkflow = File("../.github/workflows/ci.yml").readText()

        assertTrue(ciWorkflow.contains("Run dependency vulnerability scan"))
        assertTrue(ciWorkflow.contains("google/osv-scanner-action@v2.3.8"))
        assertTrue(!ciWorkflow.contains("google/osv-scanner-action@v2\n"))
        assertTrue(ciWorkflow.contains("scan-args:"))
        assertTrue(ciWorkflow.contains("--recursive"))
    }

    /**
     * Gradle Wrapper 是所有构建的第一层供应链入口，必须固定分发包 SHA。
     */
    @Test
    fun `gradle wrapper distribution is checksum pinned`() {
        val wrapperProperties = File("../gradle/wrapper/gradle-wrapper.properties").readText()
        val checksum = wrapperProperties
            .lineSequence()
            .firstOrNull { line -> line.startsWith("distributionSha256Sum=") }
            ?.substringAfter("=")
            .orEmpty()

        assertTrue(wrapperProperties.contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip"))
        assertEquals("bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531", checksum)
        assertTrue(Regex("[0-9a-f]{64}").matches(checksum))
    }

    /**
     * Demo App 使用 Android 默认 debug 签名构建 release 测试包，避免仓库保留任何本地签名模板。
     *
     * 签名材料可以留在开发者本机，但不能提交到 Git，也不能成为 Demo 的默认构建前提。
     */
    @Test
    fun `demo app uses default debug signing without local signing material`() {
        val repoRoot = File("..").canonicalFile
        val gitIgnore = File("../.gitignore").readText()
        val demoBuildFile = File("../demo-app/build.gradle.kts").readText()
        val gitIgnorePatterns = gitIgnore.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .toSet()
        val trackedFiles = trackedRepositoryFiles(repoRoot)
        val forbiddenTrackedSigningFiles = trackedFiles.filter { path ->
            path.startsWith("signing/") ||
                path == ".env" ||
                path.startsWith(".env.") ||
                path.endsWith(".jks") ||
                path.endsWith(".keystore") ||
                path.endsWith(".p12") ||
                path.endsWith(".pem") ||
                path.endsWith("private-key.asc")
        }
        val activeDemoBuildFile = demoBuildFile.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString(separator = "\n")

        assertTrue("signing/" in gitIgnorePatterns)
        assertTrue("*.jks" in gitIgnorePatterns)
        assertTrue("*.keystore" in gitIgnorePatterns)
        assertTrue("*.p12" in gitIgnorePatterns)
        assertTrue("*.pem" in gitIgnorePatterns)
        assertFalse(gitIgnorePatterns.any { pattern -> pattern.startsWith("!") && pattern.contains("signing") })
        assertTrue(forbiddenTrackedSigningFiles.isEmpty(), "Git must not track local signing files: $forbiddenTrackedSigningFiles")
        val commonTestPassword = "12" + "3456"
        val legacyTestKeyAlias = "shared" + "TestKey"
        val legacyDebugKeyFileName = "debugKey" + ".jks"
        val legacyLocalSigningConfigName = "local" + "Test"
        val legacyDemoSigningPropertyPrefix = "demo" + "Signing"
        assertFalse(demoBuildFile.contains(commonTestPassword), "demo-app must not keep fixed signing passwords, even in comments.")
        assertFalse(demoBuildFile.contains(legacyTestKeyAlias), "demo-app must not keep fixed signing aliases, even in comments.")
        assertFalse(demoBuildFile.contains(legacyDebugKeyFileName), "demo-app must not mention the legacy local debug key file.")
        assertFalse(demoBuildFile.contains(legacyLocalSigningConfigName), "demo-app must not keep a legacy local signing template.")
        assertFalse(demoBuildFile.contains(legacyDemoSigningPropertyPrefix), "demo-app must not keep legacy demo signing Gradle properties.")
        assertTrue(
            activeDemoBuildFile.contains("""signingConfig = signingConfigs.getByName("debug")"""),
            "demo-app release builds must use the default Android debug signing config."
        )
        listOf(
            legacyLocalSigningConfigName,
            "storeFile",
            "storePassword",
            "keyAlias",
            "keyPassword",
            legacyDemoSigningPropertyPrefix
        ).forEach { keyword ->
            assertFalse(activeDemoBuildFile.contains(keyword), "demo-app must not enable local signing config keyword: $keyword")
        }
    }

    private fun trackedRepositoryFiles(repoRoot: File): List<String> {
        val process = ProcessBuilder("git", "ls-files")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, output)
        return output.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
    }

    /**
     * CI 和人工发布清单必须复用同一个 Gradle 产物校验入口，避免 shell 片段和文档清单漂移。
     */
    @Test
    fun `local publication artifact verification uses shared gradle task`() {
        val rootBuildFile = File("../build.gradle.kts").readText()
        val ciWorkflow = File("../.github/workflows/ci.yml").readText()
        val releaseChecklist = File("../docs/release-checklist.md").readText()
        val englishReleaseChecklist = File("../docs/en/release-checklist.md").readText()

        assertTrue(rootBuildFile.contains("tasks.register(\"verifyMavenLocalPublicationArtifacts\")"))
        assertTrue(rootBuildFile.contains("ZipFile("))
        assertTrue(rootBuildFile.contains("DocumentBuilderFactory"))
        assertTrue(ciWorkflow.contains("./gradlew verifyMavenLocalPublicationArtifacts --warning-mode=fail"))
        listOf(releaseChecklist, englishReleaseChecklist).forEach { content ->
            assertTrue(content.contains("verifyMavenLocalPublicationArtifacts"))
        }
    }

    /**
     * 发布到公开仓库的 javadoc.jar 不能继续使用空的 Java javadoc 输出。
     *
     * Kotlin 源码需要先由 Dokka 生成 HTML，再按 Maven 约定打进 javadoc classifier。
     */
    @Test
    fun `root publishing config packages dokka html into javadoc jar`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("id(\"org.jetbrains.dokka\")"))
        assertTrue(rootBuildFile.contains("dokkaGeneratePublicationHtml"))
        assertTrue(rootBuildFile.contains("from(layout.buildDirectory.dir(\"dokka/html\"))"))
        assertTrue(rootBuildFile.contains("offlineMode.set(true)"))
        assertTrue(rootBuildFile.contains("enableAndroidDocumentationLink.set(false)"))
        assertTrue(rootBuildFile.contains("enableJdkDocumentationLink.set(false)"))
        assertTrue(rootBuildFile.contains("enableKotlinStdLibDocumentationLink.set(false)"))
    }

    /**
     * 发布清单必须把 Android release lint 纳入门禁。
     *
     * assembleRelease 只能证明 APK 能构建，不能证明 AAR 内没有低 API 调用这类 lint 能发现的问题。
     */
    @Test
    fun `release checklist includes android release lint gate`() {
        val releaseChecklist = File("../docs/release-checklist.md").readText()
        val englishReleaseChecklist = File("../docs/en/release-checklist.md").readText()

        listOf(releaseChecklist, englishReleaseChecklist).forEach { content ->
            assertTrue(content.contains(":gson-safe-parser-core:lintRelease"))
            assertTrue(content.contains(":gson-safe-parser-retrofit:lintRelease"))
            assertTrue(content.contains(":demo-app:lintRelease"))
        }
    }

    /**
     * Android 是唯一目标平台，库模块必须发布 AAR，而不是普通 JVM Jar。
     *
     * AAR 可以携带 consumer ProGuard 规则，减少 Android 用户手动配置框架自身规则的成本。
     */
    @Test
    fun `library modules publish android aar release variants`() {
        val rootBuildFile = File("../build.gradle.kts").readText()
        val coreBuildFile = File("build.gradle.kts").readText()
        val retrofitBuildFile = File("../gson-safe-parser-retrofit/build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("id(\"com.android.library\")"))
        assertTrue(rootBuildFile.contains("components.withType<SoftwareComponent>().configureEach"))
        assertTrue(rootBuildFile.contains("from(releaseComponent)"))
        assertTrue(!rootBuildFile.contains("afterEvaluate"))
        assertTrue(rootBuildFile.contains("dokkaJavadocJar"))
        assertTrue(coreBuildFile.contains("id(\"com.android.library\")"))
        assertTrue(coreBuildFile.contains("org.jetbrains.kotlin.android"))
        assertTrue(coreBuildFile.contains("consumerProguardFiles(\"consumer-proguard-rules.pro\")"))
        assertTrue(coreBuildFile.contains("singleVariant(\"release\")"))
        assertTrue(retrofitBuildFile.contains("id(\"com.android.library\")"))
        assertTrue(retrofitBuildFile.contains("org.jetbrains.kotlin.android"))
        assertTrue(retrofitBuildFile.contains("consumerProguardFiles(\"consumer-proguard-rules.pro\")"))
        assertTrue(retrofitBuildFile.contains("singleVariant(\"release\")"))
        assertTrue(!coreBuildFile.contains("kotlin(\"jvm\")"))
        assertTrue(!retrofitBuildFile.contains("kotlin(\"jvm\")"))
        assertTrue(!coreBuildFile.contains("`java-library`"))
        assertTrue(!retrofitBuildFile.contains("`java-library`"))
    }

    /**
     * 开源发布需要保留必要的来源、版权和透明说明。
     *
     * README 只放简短入口，详细信息放在 NOTICE，避免项目介绍被第三方来源说明占据。
     */
    @Test
    fun `open source notice records required credits and transparency`() {
        val noticeFile = File("../NOTICE")
        val chineseReadme = File("../README.md").readText()
        val englishReadme = File("../README_EN.md").readText()
        val notice = noticeFile.readText()

        assertTrue(noticeFile.exists())
        assertTrue(chineseReadme.contains("[NOTICE](NOTICE)"))
        assertTrue(englishReadme.contains("[NOTICE](NOTICE)"))
        assertTrue(notice.contains("getActivity/GsonFactory"))
        assertTrue(notice.contains("https://github.com/getActivity/GsonFactory"))
        assertTrue(notice.contains("Apache License, Version 2.0"))
        assertTrue(notice.contains("Copyright 2020 Huang JinQun"))
        assertTrue(notice.contains("AI assistance"))
    }

    /**
     * Maven 发布产物需要携带许可证文件。
     *
     * 这样使用者从 Maven 只下载 AAR，也不会丢失 LICENSE 和 NOTICE。
     */
    @Test
    fun `published aar artifacts include license and notice files`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("tasks.withType<Jar>().configureEach"))
        assertTrue(rootBuildFile.contains("tasks.withType<Zip>()"))
        assertTrue(rootBuildFile.contains("resources.excludes.remove(\"META-INF/LICENSE\")"))
        assertTrue(rootBuildFile.contains("resources.excludes.remove(\"META-INF/NOTICE\")"))
        assertTrue(rootBuildFile.contains("rootProject.file(\"LICENSE\")"))
        assertTrue(rootBuildFile.contains("rootProject.file(\"NOTICE\")"))
        assertTrue(rootBuildFile.contains("into(\"META-INF\")"))
    }

    /**
     * Gradle 9 兼容性不能只靠“现在能构建”判断。
     *
     * retrofit 本地构建使用 project dependency，避免 dependencySubstitution 触发配置期 classpath 解析。
     * 发布后的 POM 坐标由 AAR 产物检查兜住，JUnit 5 也显式声明运行时 engine，减少 Gradle 9 升级风险。
     */
    @Test
    fun `gradle config avoids known gradle 9 deprecation warnings`() {
        val coreBuildFile = File("build.gradle.kts").readText()
        val retrofitBuildFile = File("../gson-safe-parser-retrofit/build.gradle.kts").readText()
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(!rootBuildFile.contains("dependencySubstitution"))
        assertTrue(!coreBuildFile.contains("api(\"org.json:json"))
        assertTrue(!coreBuildFile.contains("implementation(\"org.json:json"))
        assertTrue(coreBuildFile.contains("compileOnly(\"org.json:json"))
        assertTrue(coreBuildFile.contains("testImplementation(\"org.json:json"))
        assertTrue(retrofitBuildFile.contains("project(\":gson-safe-parser-core\")"))
        assertTrue(!retrofitBuildFile.contains("\${project.group}:gson-safe-parser-core:\${project.version}"))
        assertTrue(retrofitBuildFile.contains("implementation(\"com.squareup.retrofit2:converter-gson:2.8.1\")"))
        assertTrue(!retrofitBuildFile.contains("api(\"com.squareup.retrofit2:converter-gson:2.8.1\")"))
        assertTrue(coreBuildFile.contains("testRuntimeOnly(\"org.junit.jupiter:junit-jupiter-engine"))
        assertTrue(retrofitBuildFile.contains("testRuntimeOnly(\"org.junit.jupiter:junit-jupiter-engine"))
    }

    /**
     * Retrofit 兼容层可以继续保持 Retrofit 2.x API，但不能把已知高风险 OkHttp/Okio 基线传给消费者。
     */
    @Test
    fun `retrofit and demo dependencies override legacy okhttp and okio baselines`() {
        val rootBuildFile = File("../build.gradle.kts").readText()
        val retrofitBuildFile = File("../gson-safe-parser-retrofit/build.gradle.kts").readText()
        val demoBuildFile = File("../demo-app/build.gradle.kts").readText()
        val dependencyFiles = retrofitBuildFile + "\n" + demoBuildFile

        assertTrue(retrofitBuildFile.contains("api(\"com.squareup.okhttp3:okhttp:4.12.0\")"))
        assertTrue(retrofitBuildFile.contains("api(\"com.squareup.okio:okio:3.6.0\")"))
        assertTrue(demoBuildFile.contains("implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")"))
        assertTrue(demoBuildFile.contains("implementation(\"com.squareup.okio:okio:3.6.0\")"))
        assertTrue(!dependencyFiles.contains("okhttp:3.14.7"))
        assertTrue(!dependencyFiles.contains("okio:1.17.2"))
        assertTrue(rootBuildFile.contains("pomDependencyVersion(retrofitPom, \"okhttp\") == \"4.12.0\""))
        assertTrue(rootBuildFile.contains("pomDependencyVersion(retrofitPom, \"okio\") == \"3.6.0\""))
    }

    /**
     * Retrofit 模块显式携带 OkHttp / Okio 安全基线，文档必须提前告知使用者网络栈解析变化。
     */
    @Test
    fun `retrofit dependency safety baseline is documented for consumers`() {
        val docs = listOf(
            File("../README.md").readText(),
            File("../README_EN.md").readText(),
            File("../docs/compatibility.md").readText(),
            File("../docs/en/compatibility.md").readText(),
            File("../docs/release-notes-1.0.3.md").readText(),
            File("../docs/en/release-notes-1.0.3.md").readText()
        ).joinToString(separator = "\n")

        assertTrue(docs.contains("OkHttp 4.12.0"))
        assertTrue(docs.contains("Okio 3.6.0"))
        assertTrue(docs.contains("dependencyInsight"))
        assertTrue(docs.contains("依赖解析") || docs.contains("dependency resolution"))
    }

    /**
     * 1.0.3 发布文档必须覆盖本次安全与可靠性加固，避免用户只看到 shape coercion。
     */
    @Test
    fun `release documentation includes security and reliability hardening notes`() {
        val changelog = File("../CHANGELOG.md").readText()
        val releaseNotes = File("../docs/release-notes-1.0.3.md").readText()
        val releaseNotesEn = File("../docs/en/release-notes-1.0.3.md").readText()
        val releaseChecklist = File("../docs/release-checklist.md").readText()
        val releaseChecklistEn = File("../docs/en/release-checklist.md").readText()
        val releaseDocs = listOf(changelog, releaseNotes, releaseNotesEn, releaseChecklist, releaseChecklistEn)
            .joinToString(separator = "\n")

        assertTrue(releaseDocs.contains("OSV"))
        assertTrue(releaseDocs.contains("Maven Central"))
        assertTrue(releaseDocs.contains("剪贴板") || releaseDocs.contains("clipboard"))
        assertTrue(releaseDocs.contains("脱敏") || releaseDocs.contains("redaction"))
        assertTrue(releaseDocs.contains("maxRawJsonCaptureBytesTooLarge"))
        assertTrue(releaseDocs.contains("OkHttp 4.12.0"))
        assertTrue(releaseDocs.contains("Okio 3.6.0"))
        assertTrue(releaseChecklist.contains("osv-scanner") || releaseChecklist.contains("OSV"))
        assertTrue(releaseChecklistEn.contains("osv-scanner") || releaseChecklistEn.contains("OSV"))
        assertTrue(releaseChecklist.contains("okhttp 4.12.0") || releaseChecklist.contains("OkHttp 4.12.0"))
        assertTrue(releaseChecklistEn.contains("okhttp 4.12.0") || releaseChecklistEn.contains("OkHttp 4.12.0"))
    }

    /**
     * AAR 的 consumer ProGuard 规则必须自动带上框架自身规则。
     *
     * 业务模型包名仍由用户配置，但 GsonBuilder 字段、Kotlin Metadata 和 @SerializedName 字段规则不应再让用户手抄。
     */
    @Test
    fun `android aar consumer rules include framework keep rules`() {
        val coreRules = File("consumer-proguard-rules.pro").readText()
        val retrofitRules = File("../gson-safe-parser-retrofit/consumer-proguard-rules.pro").readText()
        val combinedRules = coreRules + "\n" + retrofitRules

        assertTrue(combinedRules.contains("-keep class kotlin.Metadata"))
        assertTrue(combinedRules.contains("-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault"))
        assertTrue(combinedRules.contains("-keepclassmembers class com.google.gson.GsonBuilder"))
        assertTrue(combinedRules.contains("java.util.Map instanceCreators"))
        assertTrue(combinedRules.contains("java.util.List factories"))
        assertTrue(combinedRules.contains("java.util.ArrayDeque reflectionFilters"))
        assertTrue(combinedRules.contains("com.google.gson.ToNumberStrategy objectToNumberStrategy"))
        assertTrue(combinedRules.contains("boolean useJdkUnsafe"))
        assertTrue(combinedRules.contains("boolean complexMapKeySerialization"))
        assertTrue(combinedRules.contains("@com.google.gson.annotations.SerializedName <fields>"))
        assertTrue(!combinedRules.contains("com.yourcompany"))
        assertTrue(!combinedRules.contains("io.github.logan.gsonsafeparser.demo.model"))
        assertTrue(!combinedRules.contains("**.bean.**"))
        assertTrue(!combinedRules.contains("**.model.**"))
        assertTrue(!combinedRules.contains("**.entity.**"))
        assertTrue(!combinedRules.contains("**.response.**"))
        assertTrue(!combinedRules.contains("**.dto.**"))
    }

    /**
     * Retrofit 模块公开 API 返回 retrofit2 类型，发布元数据必须把 Retrofit 本体暴露给消费者编译期。
     *
     * converter-gson 只是内部实现细节，不能升级成 api 依赖。
     */
    @Test
    fun `retrofit module exposes retrofit api dependency without exposing converter implementation`() {
        val retrofitBuildFile = File("../gson-safe-parser-retrofit/build.gradle.kts").readText()

        assertTrue(retrofitBuildFile.contains("api(\"com.squareup.retrofit2:retrofit:2.8.1\")"))
        assertTrue(retrofitBuildFile.contains("implementation(\"com.squareup.retrofit2:converter-gson:2.8.1\")"))
        assertTrue(!retrofitBuildFile.contains("api(\"com.squareup.retrofit2:converter-gson:2.8.1\")"))
    }

    /**
     * 远程发布失败时可以输出状态码和摘要，但不能把服务端响应体原样写进 CI 日志。
     */
    @Test
    fun `maven central deployment failure output redacts raw response body`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(rootBuildFile.contains("fun sanitizeMavenCentralResponseBody("))
        assertTrue(rootBuildFile.contains("Central Portal deployment response redacted"))
        assertTrue(!rootBuildFile.contains("Response body: \${responseBody"))
    }

    /**
     * 发布响应脱敏不能按空白截断敏感值，否则 password="alpha beta" 会留下 beta。
     */
    @Test
    fun `maven central response redaction does not use whitespace truncated secret values`() {
        val rootBuildFile = File("../build.gradle.kts").readText()

        assertTrue(!rootBuildFile.contains("([^\\\"\\\\s,;{}]+)"))
        assertTrue(rootBuildFile.contains("api[_-]?key"))
        assertTrue(rootBuildFile.contains("set-cookie"))
        assertTrue(rootBuildFile.contains("Basic"))
    }

    /**
     * internal 包名不是 Kotlin 可见性边界，发布前不能把内部 raw JSON 上下文暴露成公开 ABI。
     */
    @Test
    fun `internal raw json context is not part of public kotlin api`() {
        val rawJsonContext = File("src/main/kotlin/io/github/logan/gsonsafeparser/internal/RawJsonContext.kt").readText()

        assertTrue(rawJsonContext.contains("internal object RawJsonContext"))
    }

    /**
     * 公开入口文档必须说明安全解析不会吞掉不可恢复的 Gson 原生异常。
     */
    @Test
    fun `public parsing kdoc states unrecoverable gson exceptions are thrown`() {
        val parserEntry = File("src/main/kotlin/io/github/logan/gsonsafeparser/GsonSafeParser.kt").readText()
        val kotlinEntry = File("src/main/kotlin/io/github/logan/gsonsafeparser/SafeParserKotlinApi.kt").readText()
        val publicDocs = parserEntry + "\n" + kotlinEntry

        assertTrue(publicDocs.contains("不可恢复") && publicDocs.contains("继续抛出"))
        assertTrue(!publicDocs.contains("Gson 原生失败时可能为 null"))
        assertTrue(!publicDocs.contains("解析失败或顶层 Object 错形时可能为 null"))
    }

    /**
     * 配置对象包含回调 lambda，不应该用 data class 自动生成基于函数实例的 equals/hashCode。
     */
    @Test
    fun `config objects with callbacks are not data classes`() {
        assertFalse(SafeParserConfig::class.isData)
        assertFalse(SafeObserverPolicy::class.isData)
    }

    /**
     * 默认入口是普通 Android 接入方最常用路径，不能为了继承用户 Builder 配置而依赖 GsonBuilder 私有字段。
     */
    @Test
    fun `default create entry does not depend on gson builder compatibility snapshot`() {
        val parserEntry = File("src/main/kotlin/io/github/logan/gsonsafeparser/GsonSafeParser.kt").readText()
        val createEntry = parserEntry.substringAfter("fun create(config: SafeParserConfig = SafeParserConfig()): Gson {")
            .substringBefore("    /**\n     * 检查当前运行环境和配置是否适合接入。")

        assertTrue(createEntry.contains("registerSafeParserDirect(config)"))
        assertFalse(createEntry.contains(".enableSafeParser(config)"))
        assertFalse(createEntry.contains("compatibilitySnapshot()"))

        val directRegisterEntry = parserEntry.substringAfter("private fun GsonBuilder.registerSafeParserDirect(")
            .substringBefore("private fun GsonBuilder.hasSafeTypeAdapterFactory()")
        assertFalse(directRegisterEntry.contains("hasSafeTypeAdapterFactory()"))
        assertFalse(directRegisterEntry.contains("snapshotField("))
    }

    /**
     * 公开 API 的长期兼容边界必须明确：外部 Gson 包装入口显式命名，事件流可扩展，低层手动事件注入口需要 opt-in。
     */
    @Test
    fun `public api keeps explicit external gson and extensible event boundaries`() {
        val parserEntry = File("src/main/kotlin/io/github/logan/gsonsafeparser/GsonSafeParser.kt").readText()
        val configEntry = File("src/main/kotlin/io/github/logan/gsonsafeparser/SafeParserConfig.kt").readText()
        val retrofitEntry = File("../gson-safe-parser-retrofit/src/main/kotlin/io/github/logan/gsonsafeparser/retrofit/GsonSafeConverterFactory.kt").readText()

        assertTrue(parserEntry.contains("fun parserWithExternalGson("))
        assertTrue(parserEntry.contains("fun diagnostics(\n        gson: Gson"))
        assertTrue(retrofitEntry.contains("fun create(\n        builder: GsonBuilder"))
        assertFalse(parserEntry.contains("@Deprecated("))
        assertFalse(parserEntry.contains("fun parser(\n        gson: Gson"))
        assertTrue(configEntry.contains("interface SafeParserEvent"))
        assertTrue(!configEntry.contains("sealed interface SafeParserEvent"))
        assertTrue(configEntry.contains("override val eventName: String = \"TypeMismatch\""))
        assertTrue(configEntry.contains("override val eventName: String = \"AdapterCreationFailure\""))
        assertTrue(configEntry.contains("override val eventName: String = \"EmptyResponse\""))
        assertTrue(configEntry.contains("override val eventName: String = \"RawJsonCaptureSkipped\""))
        assertTrue(configEntry.contains("annotation class GsonSafeParserLowLevelApi"))
        assertTrue(configEntry.contains("level = RequiresOptIn.Level.ERROR"))
        assertTrue(configEntry.contains("@GsonSafeParserLowLevelApi\nfun SafeParserConfig.dispatchEvent"))
    }

    /**
     * `SafeParseContractIssue` 是 1.0.0 首发公开 data class。
     *
     * 发布后新增报告字段应优先做成计算属性，避免破坏已经接入 1.0.0 的使用方。
     */
    @Test
    fun `contract issue keeps first public release constructor signature`() {
        val expectedTypes = listOf(
            SafeParseContractIssueCategory::class.java,
            SafeParseContractIssueSeverity::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            ParseExceptionKind::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            EmptyResponsePolicy::class.java,
            java.lang.Long::class.java,
            Integer::class.java,
            Boolean::class.javaPrimitiveType
        )

        val matchingConstructor = SafeParseContractIssue::class.java.constructors.firstOrNull { constructor ->
            constructor.parameterTypes.toList() == expectedTypes
        }

        assertTrue(
            matchingConstructor != null,
            "SafeParseContractIssue must keep its 1.0.0 public constructor signature."
        )
        assertTrue(
            SafeParseContractIssue::class.java.constructors.none { constructor ->
                ShapeCoercionAction::class.java in constructor.parameterTypes
            },
            "Shape coercion report metadata must not alter SafeParseContractIssue constructor ABI."
        )
    }

    /**
     * 1.0.3 新增 shape coercion 不能插入旧配置对象的公开构造函数。
     *
     * 这能降低补丁版本升级时已编译调用方遇到 `NoSuchMethodError` 的风险。
     */
    @Test
    fun `shape coercion keeps existing config constructor signatures`() {
        val function1Type = kotlin.jvm.functions.Function1::class.java
        val configConstructorTypes = listOf(
            FallbackPolicy::class.java,
            EmptyResponsePolicy::class.java,
            java.util.Map::class.java,
            com.google.gson.ToNumberStrategy::class.java,
            PrimitiveParsingPolicy::class.java,
            java.util.List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            java.util.Set::class.java,
            NullValuePolicy::class.java,
            RequiredConstructorParameterPolicy::class.java,
            MapItemKeyPolicy::class.java,
            Boolean::class.javaPrimitiveType,
            Integer::class.javaPrimitiveType,
            function1Type,
            function1Type,
            function1Type,
            function1Type
        )
        val readPolicyConstructorTypes = listOf(
            FallbackPolicy::class.java,
            PrimitiveParsingPolicy::class.java,
            java.util.List::class.java,
            java.util.Set::class.java,
            Boolean::class.javaPrimitiveType,
            NullValuePolicy::class.java,
            RequiredConstructorParameterPolicy::class.java
        )

        assertTrue(
            SafeParserConfig::class.java.constructors.any { constructor ->
                constructor.parameterTypes.toList() == configConstructorTypes
            },
            "SafeParserConfig must keep its pre-1.0.3 public constructor signature."
        )
        assertTrue(
            SafeReadPolicy::class.java.constructors.any { constructor ->
                constructor.parameterTypes.toList() == readPolicyConstructorTypes
            },
            "SafeReadPolicy must keep its pre-1.0.3 public constructor signature."
        )
        assertTrue(
            SafeParserConfig::class.java.constructors.none { constructor ->
                ShapeCoercionPolicy::class.java in constructor.parameterTypes ||
                    ShapeCoercionOptions::class.java in constructor.parameterTypes
            },
            "Shape coercion must be enabled through a separate option API, not constructor insertion."
        )
        assertTrue(
            SafeReadPolicy::class.java.constructors.none { constructor ->
                ShapeCoercionPolicy::class.java in constructor.parameterTypes ||
                    ShapeCoercionOptions::class.java in constructor.parameterTypes
            },
            "Shape coercion must not alter SafeReadPolicy constructor ABI."
        )
    }

    @Test
    fun `shape coercion does not extend existing public enum value sets`() {
        assertTrue(SafeTypeHandling.values().none { value -> value.name == "SafeArray" })
        assertTrue(SafeParseContractIssueCategory.values().none { value -> value.name == "ShapeCoercion" })
        assertTrue(SafeParserEventCategory.values().none { value -> value.name == "ShapeCoercion" })
    }

    /**
     * Android 低版本不能直接调用 java.lang.reflect.Type#getTypeName。
     *
     * demo 的 minSdk 是 23，而 Android Lint 会把 Type.typeName 标成 API 28 风险。
     * 主源码和 Retrofit 源码必须走兼容工具方法，不能直接访问 Kotlin 的 typeName 属性。
     */
    @Test
    fun `android compatible source avoids direct type name api`() {
        val sourceRoots = listOf(
            File("src/main/kotlin"),
            File("../gson-safe-parser-retrofit/src/main/kotlin")
        )
        val forbiddenPatterns = listOf(
            ".type.typeName",
            " type.typeName",
            "::class.java.typeName",
            ".javaObjectType.typeName"
        )
        val violations = sourceRoots
            .flatMap { root -> root.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .flatMap { file ->
                forbiddenPatterns
                    .filter { pattern -> file.readText().contains(pattern) }
                    .map { pattern -> "${file.path} contains $pattern" }
            }

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    /**
     * 公开库输出必须保持英文，中文本地化只允许出现在 demo 层。
     *
     * 这样即使 demo 为了照顾新手加入中文说明，也不会把 lib 的公共报告、诊断和观察报告带偏。
     */
    @Test
    fun `public runtime reports stay english only`() {
        val contractReport = SafeParseResult<Unit>(
            value = Unit,
            events = listOf(
                SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.User",
                        actualToken = com.google.gson.stream.JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = "data"
                    )
                )
            )
        ).contractReport()
        val contractMarkdown = contractReport.toMarkdown()
        val backendMarkdown = contractReport.toBackendMarkdown()
        val structuredRows = contractReport.toStructuredRows().joinToString("\n") { row ->
            "${row.stableKey}:${row.fields}"
        }
        val observerMarkdown = listOf(
            ObserverFailureEvent(
                callbackName = "onEvent",
                eventName = "TypeMismatch",
                sourceEvent = SafeParserEvent.TypeMismatch(
                    TypeMismatchEvent(
                        expectedType = "com.example.User",
                        actualToken = com.google.gson.stream.JsonToken.BEGIN_ARRAY,
                        path = "$.data",
                        reason = "Unexpected JSON token",
                        kind = ParseExceptionKind.OBJECT,
                        fieldName = "data"
                    )
                ),
                reason = "logger failed",
                error = IllegalStateException("logger failed")
            )
        ).observerFailureReport().toMarkdown()
        val diagnosticsMessages = GsonSafeParser.diagnostics(
            SafeParserConfig(
                skippedPlatformTypePrefixes = emptySet(),
                captureRawJsonInCallbacks = true,
                maxRawJsonCaptureBytes = 0
            )
        ).checks.joinToString("\n") { check -> "${check.name}:${check.message}" }

        assertFalse(Regex("\\p{IsHan}").containsMatchIn(contractMarkdown))
        assertFalse(Regex("\\p{IsHan}").containsMatchIn(backendMarkdown))
        assertFalse(Regex("\\p{IsHan}").containsMatchIn(structuredRows))
        assertFalse(Regex("\\p{IsHan}").containsMatchIn(observerMarkdown))
        assertFalse(Regex("\\p{IsHan}").containsMatchIn(diagnosticsMessages))
        assertTrue(contractMarkdown.contains("Type mismatch"))
        assertTrue(backendMarkdown.contains("Backend JSON Contract Report"))
        assertTrue(structuredRows.contains("category=TypeMismatch"))
        assertTrue(observerMarkdown.contains("No safe parser observer failures").not())
        assertTrue(diagnosticsMessages.contains("Platform type skipping is disabled."))
    }
}
