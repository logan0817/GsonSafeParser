package io.github.logan.gsonsafeparser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证接入样例文档不会和真实 API 脱节。
 *
 * 这个测试只检查文档里必须保留的关键片段和 CI 命令，避免文档被改得看起来还在，
 * 但已经缺少 production/debug/lowInterference、自检或报告示例。
 */
class SafeParserJvmCiDocumentationTest {
    /**
     * 测试方法说明：验证“jvm ci sample document keeps integration snippets available”这个具体行为。
     * 阅读时可以按准备数据、执行解析、断言结果的顺序跟下来。
     */
    @Test
    fun `jvm ci sample document keeps integration snippets available`() {
        val gettingStartedFile = projectRoot().resolve("docs/getting-started.md")
        val configurationFile = projectRoot().resolve("docs/configuration.md")
        val mismatchMatrixFile = projectRoot().resolve("docs/mismatch-capability-matrix.md")

        assertTrue(gettingStartedFile.exists(), "docs/getting-started.md should exist")
        assertTrue(configurationFile.exists(), "docs/configuration.md should exist")
        assertTrue(mismatchMatrixFile.exists(), "docs/mismatch-capability-matrix.md should exist")

        val content = gettingStartedFile.readText() + "\n" + configurationFile.readText() + "\n" + mismatchMatrixFile.readText()
        assertAll(
            { assertTrue(content.contains("# 快速开始")) },
            { assertTrue(content.contains("# 配置说明")) },
            { assertTrue(content.contains("SafeParserConfig.production")) },
            { assertTrue(content.contains("SafeParserConfig.debug")) },
            { assertTrue(content.contains("SafeParserConfig.lowInterference")) },
            { assertTrue(content.contains("GsonSafeParser.parser")) },
            { assertTrue(content.contains("GsonSafeParser.parserWithExternalGson")) },
            { assertTrue(content.contains("parser.parseSafe<ApiResponse>")) },
            { assertTrue(content.contains("GsonSafeParser.integrationCheck")) },
            { assertTrue(content.contains("val integrationCheck = GsonSafeParser.integrationCheck")) },
            { assertTrue(!content.contains("val check = GsonSafeParser.integrationCheck")) },
            { assertTrue(content.contains("GsonSafeParser.diagnostics")) },
            { assertTrue(content.contains("contractReport()")) },
            { assertTrue(content.contains("toBackendMarkdown()")) },
            { assertTrue(content.contains("toStructuredRows()")) },
            { assertTrue(content.contains("stableKey")) },
            { assertTrue(content.contains("captureSkipReason")) },
            { assertTrue(content.contains("skipReason")) },
            { assertTrue(content.contains("summary.warningCount")) },
            { assertTrue(content.contains("错形能力矩阵")) },
            { assertTrue(content.contains("后端修复建议")) },
            { assertTrue(content.contains("observerFailureReport()")) },
            { assertTrue(content.contains("字段级 Adapter 读取失败")) },
            { assertTrue(content.contains("根级解析失败")) },
            { assertTrue(content.contains("Java 调用")) },
            { assertTrue(content.contains("非 reified")) }
        )
    }

    /**
     * 测试方法说明：验证仓库首页、英文说明和关键接入文档里的约束信息没有漂移。
     * 这里重点盯住安装方式、JDK 要求、混淆文档入口、Retrofit 双参数入口和 Demo 文档入口。
     */
    @Test
    fun `repository documents keep release critical snippets available`() {
        val readmeFile = projectRoot().resolve("README.md")
        val readmeEnFile = projectRoot().resolve("README_EN.md")
        val gettingStartedFile = projectRoot().resolve("docs/getting-started.md")
        val englishGettingStartedFile = projectRoot().resolve("docs/en/getting-started.md")
        val englishConfigurationFile = projectRoot().resolve("docs/en/configuration.md")
        val mismatchMatrixFile = projectRoot().resolve("docs/mismatch-capability-matrix.md")
        val englishMismatchMatrixFile = projectRoot().resolve("docs/en/mismatch-capability-matrix.md")
        val androidProguardFile = projectRoot().resolve("docs/android-proguard.md")
        val englishAndroidProguardFile = projectRoot().resolve("docs/en/android-proguard.md")
        val demoAppFile = projectRoot().resolve("docs/demo-app.md")
        val englishDemoAppFile = projectRoot().resolve("docs/en/demo-app.md")
        val troubleshootingFile = projectRoot().resolve("docs/troubleshooting.md")
        val englishTroubleshootingFile = projectRoot().resolve("docs/en/troubleshooting.md")
        val releaseChecklistFile = projectRoot().resolve("docs/release-checklist.md")
        val englishReleaseChecklistFile = projectRoot().resolve("docs/en/release-checklist.md")
        val releaseNotesFile = projectRoot().resolve("docs/release-notes-1.0.2.md")
        val englishReleaseNotesFile = projectRoot().resolve("docs/en/release-notes-1.0.2.md")
        val previousReleaseNotesFile = projectRoot().resolve("docs/release-notes-1.0.1.md")
        val englishPreviousReleaseNotesFile = projectRoot().resolve("docs/en/release-notes-1.0.1.md")
        val historicalReleaseNotesFile = projectRoot().resolve("docs/release-notes-1.0.0.md")
        val englishHistoricalReleaseNotesFile = projectRoot().resolve("docs/en/release-notes-1.0.0.md")
        val compatibilityFile = projectRoot().resolve("docs/compatibility.md")
        val englishCompatibilityFile = projectRoot().resolve("docs/en/compatibility.md")
        val changelogFile = projectRoot().resolve("CHANGELOG.md")
        assertAll(
            { assertTrue(readmeFile.exists(), "README.md should exist") },
            { assertTrue(readmeEnFile.exists(), "README_EN.md should exist") },
            { assertTrue(gettingStartedFile.exists(), "docs/getting-started.md should exist") },
            { assertTrue(englishGettingStartedFile.exists(), "docs/en/getting-started.md should exist") },
            { assertTrue(englishConfigurationFile.exists(), "docs/en/configuration.md should exist") },
            { assertTrue(mismatchMatrixFile.exists(), "docs/mismatch-capability-matrix.md should exist") },
            { assertTrue(englishMismatchMatrixFile.exists(), "docs/en/mismatch-capability-matrix.md should exist") },
            { assertTrue(androidProguardFile.exists(), "docs/android-proguard.md should exist") },
            { assertTrue(englishAndroidProguardFile.exists(), "docs/en/android-proguard.md should exist") },
            { assertTrue(demoAppFile.exists(), "docs/demo-app.md should exist") },
            { assertTrue(englishDemoAppFile.exists(), "docs/en/demo-app.md should exist") },
            { assertTrue(troubleshootingFile.exists(), "docs/troubleshooting.md should exist") },
            { assertTrue(englishTroubleshootingFile.exists(), "docs/en/troubleshooting.md should exist") },
            { assertTrue(releaseChecklistFile.exists(), "docs/release-checklist.md should exist") },
            { assertTrue(englishReleaseChecklistFile.exists(), "docs/en/release-checklist.md should exist") },
            { assertTrue(releaseNotesFile.exists(), "docs/release-notes-1.0.2.md should exist") },
            { assertTrue(englishReleaseNotesFile.exists(), "docs/en/release-notes-1.0.2.md should exist") },
            { assertTrue(previousReleaseNotesFile.exists(), "docs/release-notes-1.0.1.md should exist") },
            { assertTrue(englishPreviousReleaseNotesFile.exists(), "docs/en/release-notes-1.0.1.md should exist") },
            { assertTrue(historicalReleaseNotesFile.exists(), "docs/release-notes-1.0.0.md should exist") },
            { assertTrue(englishHistoricalReleaseNotesFile.exists(), "docs/en/release-notes-1.0.0.md should exist") },
            { assertTrue(compatibilityFile.exists(), "docs/compatibility.md should exist") },
            { assertTrue(englishCompatibilityFile.exists(), "docs/en/compatibility.md should exist") },
            { assertTrue(changelogFile.exists(), "CHANGELOG.md should exist") }
        )

        val readmeContent = readmeFile.readText()
        val readmeEnContent = readmeEnFile.readText()
        val gettingStartedContent = gettingStartedFile.readText()
        val englishGettingStartedContent = englishGettingStartedFile.readText()
        val englishConfigurationContent = englishConfigurationFile.readText()
        val mismatchMatrixContent = mismatchMatrixFile.readText()
        val englishMismatchMatrixContent = englishMismatchMatrixFile.readText()
        val androidProguardContent = androidProguardFile.readText()
        val englishAndroidProguardContent = englishAndroidProguardFile.readText()
        val demoAppContent = demoAppFile.readText()
        val englishDemoAppContent = englishDemoAppFile.readText()
        val troubleshootingContent = troubleshootingFile.readText()
        val englishTroubleshootingContent = englishTroubleshootingFile.readText()
        val releaseChecklistContent = releaseChecklistFile.readText()
        val englishReleaseChecklistContent = englishReleaseChecklistFile.readText()
        val releaseNotesContent = releaseNotesFile.readText()
        val englishReleaseNotesContent = englishReleaseNotesFile.readText()
        val previousReleaseNotesContent = previousReleaseNotesFile.readText()
        val englishPreviousReleaseNotesContent = englishPreviousReleaseNotesFile.readText()
        val historicalReleaseNotesContent = historicalReleaseNotesFile.readText()
        val englishHistoricalReleaseNotesContent = englishHistoricalReleaseNotesFile.readText()
        val compatibilityContent = compatibilityFile.readText()
        val englishCompatibilityContent = englishCompatibilityFile.readText()
        val changelogContent = changelogFile.readText()
        val demoProguardContent = projectRoot().resolve("demo-app/proguard-rules.pro").readText()
        val currentMarkdownDocs = currentMarkdownDocFiles().joinToString("\n") { it.readText() }
        val publicBehaviorDocs = listOf(
            readmeContent,
            readmeEnContent,
            projectRoot().resolve("docs/configuration.md").readText(),
            englishConfigurationContent
        ).joinToString("\n")

        assertAll(
            { assertTrue(readmeContent.contains("[English](README_EN.md)")) },
            { assertTrue(readmeEnContent.contains("[中文](README.md)")) },
            { assertTrue(readmeContent.contains("JDK 17")) },
            { assertTrue(readmeEnContent.contains("JDK 17")) },
            { assertTrue(readmeContent.contains("gson-safe-parser-retrofit:1.0.2")) },
            { assertTrue(readmeEnContent.contains("gson-safe-parser-retrofit:1.0.2")) },
            { assertTrue(readmeContent.contains("docs/android-proguard.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/android-proguard.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/getting-started.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/configuration.md")) },
            { assertTrue(readmeContent.contains("docs/mismatch-capability-matrix.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/mismatch-capability-matrix.md")) },
            { assertTrue(readmeContent.contains("docs/release-notes-1.0.2.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/release-notes-1.0.2.md")) },
            { assertTrue(readmeContent.contains("docs/release-notes-1.0.1.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/release-notes-1.0.1.md")) },
            { assertTrue(readmeContent.contains("docs/release-notes-1.0.0.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/release-notes-1.0.0.md")) },
            { assertTrue(readmeContent.contains("toBackendMarkdown()")) },
            { assertTrue(readmeEnContent.contains("toBackendMarkdown()")) },
            { assertTrue(readmeContent.contains("toStructuredRows()")) },
            { assertTrue(readmeEnContent.contains("toStructuredRows()")) },
            { assertTrue(readmeContent.contains("summary.warningCount")) },
            { assertTrue(readmeEnContent.contains("summary.warningCount")) },
            { assertTrue(readmeEnContent.contains("docs/en/demo-app.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/troubleshooting.md")) },
            { assertTrue(readmeContent.contains("docs/release-checklist.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/release-checklist.md")) },
            { assertTrue(readmeContent.contains("docs/compatibility.md")) },
            { assertTrue(readmeEnContent.contains("docs/en/compatibility.md")) },
            { assertTrue(readmeContent.contains("GsonSafeConverterFactory.create(gson, config)")) },
            { assertTrue(readmeEnContent.contains("GsonSafeConverterFactory.create(gson, config)")) },
            { assertTrue(readmeContent.contains("Retrofit 网络或传输读流失败")) },
            { assertTrue(readmeEnContent.contains("Retrofit network or transport read failure")) },
            { assertTrue(readmeContent.contains("GsonSafeParser.parser(config)")) },
            { assertTrue(readmeEnContent.contains("GsonSafeParser.parser(config)")) },
            { assertTrue(publicBehaviorDocs.contains("FallbackPolicy.NullOnly")) },
            { assertTrue(publicBehaviorDocs.contains("PrimitiveParsingPolicy.DelegateToGson")) },
            { assertTrue(publicBehaviorDocs.contains("DefaultValueForUnitOrVoidOnly")) },
            { assertTrue(publicBehaviorDocs.contains("useJdkUnsafe = false")) },
            { assertTrue(publicBehaviorDocs.contains("SafeParser 自己默认不使用 Unsafe")) },
            { assertTrue(publicBehaviorDocs.contains("`Strict` has the highest priority")) },
            { assertTrue(publicBehaviorDocs.contains("`Strict` 优先级最高")) },
            { assertTrue(publicBehaviorDocs.contains("Gson fallback path")) },
            { assertTrue(publicBehaviorDocs.contains("RequiredConstructorParameterPolicy.GsonCompatible")) },
            { assertTrue(publicBehaviorDocs.contains("MapItemKeyPolicy.Hash")) },
            { assertTrue(!publicBehaviorDocs.contains("fallbackPolicy = FallbackPolicy.Default")) },
            { assertTrue(!publicBehaviorDocs.contains("primitiveParsingPolicy = PrimitiveParsingPolicy.Safe")) },
            { assertTrue(!publicBehaviorDocs.contains("emptyResponsePolicy = EmptyResponsePolicy.DefaultValue`")) },
            { assertTrue(!publicBehaviorDocs.contains("FallbackPolicy.Default`（默认）")) },
            { assertTrue(!publicBehaviorDocs.contains("FallbackPolicy.Default` (default)")) },
            { assertTrue(!publicBehaviorDocs.contains("PrimitiveParsingPolicy.Safe`（默认）")) },
            { assertTrue(!publicBehaviorDocs.contains("PrimitiveParsingPolicy.Safe` (default)")) },
            { assertTrue(!publicBehaviorDocs.contains("EmptyResponsePolicy.DefaultValue`（默认）")) },
            { assertTrue(!publicBehaviorDocs.contains("EmptyResponsePolicy.DefaultValue` (default)")) },
            { assertTrue(!publicBehaviorDocs.contains("useJdkUnsafe = true, // 默认允许")) },
            { assertTrue(!publicBehaviorDocs.contains("useJdkUnsafe = true, // Allows")) },
            { assertTrue(!currentMarkdownDocs.contains("默认基础类型会走安全解析")) },
            { assertTrue(!currentMarkdownDocs.contains("Primitive types use safe parsing by default")) },
            { assertTrue(!currentMarkdownDocs.contains("fallbackPolicy = FallbackPolicy.Default`、`primitiveParsingPolicy = PrimitiveParsingPolicy.Safe")) },
            { assertTrue(!currentMarkdownDocs.contains("fallbackPolicy = FallbackPolicy.Default`, `primitiveParsingPolicy = PrimitiveParsingPolicy.Safe")) },
            { assertTrue(!currentMarkdownDocs.contains("emptyResponsePolicy = EmptyResponsePolicy.DefaultValue`。")) },
            { assertTrue(!currentMarkdownDocs.contains("emptyResponsePolicy = EmptyResponsePolicy.DefaultValue`.")) },
            { assertTrue(readmeContent.contains("顶层 `Object`")) },
            { assertTrue(readmeEnContent.contains("Root object mismatch")) },
            { assertTrue(readmeContent.contains("字段有构造默认值时，会优先保留默认值")) },
            { assertTrue(readmeEnContent.contains("fields with constructed defaults keep those defaults")) },
            { assertTrue(readmeContent.contains("字段级 Adapter 读取失败")) },
            { assertTrue(readmeEnContent.contains("field-level adapter read failures")) },
            { assertTrue(!readmeContent.contains("自定义 TypeAdapter 主动抛出")) },
            { assertTrue(!readmeEnContent.contains("custom TypeAdapters are unrecoverable")) },
            { assertTrue(gettingStartedContent.contains("GsonSafeConverterFactory.create(gson, config)")) },
            { assertTrue(englishGettingStartedContent.contains("GsonSafeConverterFactory.create(gson, config)")) },
            { assertTrue(gettingStartedContent.contains("GsonSafeParser.parserWithExternalGson(gson, config)")) },
            { assertTrue(englishGettingStartedContent.contains("GsonSafeParser.parserWithExternalGson(gson, config)")) },
            { assertTrue(gettingStartedContent.contains("import io.github.logan.gsonsafeparser.GsonSafeParser")) },
            { assertTrue(englishGettingStartedContent.contains("import io.github.logan.gsonsafeparser.GsonSafeParser")) },
            { assertTrue(gettingStartedContent.contains("data class ApiResponse")) },
            { assertTrue(englishGettingStartedContent.contains("data class ApiResponse")) },
            { assertTrue(gettingStartedContent.contains("依赖坐标使用 `io.github.logan0817`，代码 import 使用 `io.github.logan.gsonsafeparser`")) },
            { assertTrue(englishGettingStartedContent.contains("Dependency coordinates use `io.github.logan0817`, while code imports use `io.github.logan.gsonsafeparser`")) },
            { assertTrue(gettingStartedContent.contains("不会自动给外部 Gson 注册 Safe Adapter")) },
            { assertTrue(englishGettingStartedContent.contains("does not automatically register Safe Adapter")) },
            { assertTrue(gettingStartedContent.contains("字段级 Adapter 的事件回调归属")) },
            { assertTrue(englishGettingStartedContent.contains("Field-level Adapter event callbacks")) },
            { assertTrue(gettingStartedContent.contains("check(integrationCheck.hasErrors.not())")) },
            { assertTrue(englishGettingStartedContent.contains("check(integrationCheck.hasErrors.not())")) },
            { assertTrue(!gettingStartedContent.contains("val check = GsonSafeParser.integrationCheck")) },
            { assertTrue(!englishGettingStartedContent.contains("val check = GsonSafeParser.integrationCheck")) },
            { assertTrue(gettingStartedContent.contains("Java 调用")) },
            { assertTrue(englishGettingStartedContent.contains("Java usage")) },
            { assertTrue(gettingStartedContent.contains("非 reified")) },
            { assertTrue(englishGettingStartedContent.contains("non-reified")) },
            { assertTrue(englishConfigurationContent.contains("# Configuration")) },
            { assertTrue(englishConfigurationContent.contains("Mismatch Capability Matrix")) },
            { assertTrue(englishConfigurationContent.contains("toStructuredRows()")) },
            { assertTrue(englishConfigurationContent.contains("stableKey")) },
            { assertTrue(englishConfigurationContent.contains("captureSkipReason")) },
            { assertTrue(englishMismatchMatrixContent.contains("skipReason")) },
            { assertTrue(mismatchMatrixContent.contains("# 错形能力矩阵")) },
            { assertTrue(mismatchMatrixContent.contains("后端修复建议")) },
            { assertTrue(mismatchMatrixContent.contains("GsonSafeParser.parseSafe<ApiResponse>")) },
            { assertTrue(mismatchMatrixContent.contains("fallbackPolicy = FallbackPolicy.NullOnly")) },
            { assertTrue(mismatchMatrixContent.contains("primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson")) },
            { assertTrue(mismatchMatrixContent.contains("emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly")) },
            { assertTrue(mismatchMatrixContent.contains("requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible")) },
            { assertTrue(mismatchMatrixContent.contains("显式切到 `FallbackPolicy.Default`")) },
            { assertTrue(mismatchMatrixContent.contains("sha256:")) },
            { assertTrue(mismatchMatrixContent.contains("`JSONObject` 收到数组、`JSONArray` 收到对象时返回 `null`，并产生 `TypeMismatch`")) },
            { assertTrue(mismatchMatrixContent.contains("path=$")) },
            { assertTrue(mismatchMatrixContent.contains("非空构造参数如果没有默认值，默认按 Gson 兼容处理")) },
            { assertTrue(mismatchMatrixContent.contains("把 `requiredConstructorParameterPolicy` 改成 `Strict`")) },
            { assertTrue(mismatchMatrixContent.contains("默认不捕获 raw JSON，也不会产生 `RawJsonCaptureSkipped`")) },
            { assertTrue(mismatchMatrixContent.contains("显式开启 `captureRawJsonInCallbacks` 后，超限才会产生 `RawJsonCaptureSkipped`")) },
            { assertTrue(!mismatchMatrixContent.contains("当前不产生 `TypeMismatch` 事件")) },
            { assertTrue(!mismatchMatrixContent.contains("返回空集合或按配置返回 `null`")) },
            { assertTrue(!mismatchMatrixContent.contains("返回空 Map、跳过坏 entry，或按配置返回 `null`")) },
            { assertTrue(!mismatchMatrixContent.contains("默认处理：跳过 raw JSON 捕获，继续普通 Converter 路径")) },
            { assertTrue(!mismatchMatrixContent.contains("保留字段默认值或使用安全基础值")) },
            { assertTrue(!mismatchMatrixContent.contains("保留字段默认值或使用安全布尔值")) },
            { assertTrue(englishMismatchMatrixContent.contains("# Mismatch Capability Matrix")) },
            { assertTrue(englishMismatchMatrixContent.contains("backend fix suggestion")) },
            { assertTrue(englishMismatchMatrixContent.contains("GsonSafeParser.parseSafe<ApiResponse>")) },
            { assertTrue(englishMismatchMatrixContent.contains("fallbackPolicy = FallbackPolicy.NullOnly")) },
            { assertTrue(englishMismatchMatrixContent.contains("primitiveParsingPolicy = PrimitiveParsingPolicy.DelegateToGson")) },
            { assertTrue(englishMismatchMatrixContent.contains("emptyResponsePolicy = EmptyResponsePolicy.DefaultValueForUnitOrVoidOnly")) },
            { assertTrue(englishMismatchMatrixContent.contains("requiredConstructorParameterPolicy = RequiredConstructorParameterPolicy.GsonCompatible")) },
            { assertTrue(englishMismatchMatrixContent.contains("explicitly choose `FallbackPolicy.Default`")) },
            { assertTrue(englishMismatchMatrixContent.contains("sha256:")) },
            { assertTrue(englishMismatchMatrixContent.contains("`JSONObject` receiving an array or `JSONArray` receiving an object returns `null` and emits `TypeMismatch`")) },
            { assertTrue(englishMismatchMatrixContent.contains("path=$")) },
            { assertTrue(englishMismatchMatrixContent.contains("A non-null constructor parameter without a default uses Gson-compatible handling by default")) },
            { assertTrue(englishMismatchMatrixContent.contains("switch `requiredConstructorParameterPolicy` to `Strict`")) },
            { assertTrue(englishMismatchMatrixContent.contains("does not capture raw JSON by default and does not emit `RawJsonCaptureSkipped`")) },
            { assertTrue(englishMismatchMatrixContent.contains("After `captureRawJsonInCallbacks` is enabled explicitly, oversized bodies emit `RawJsonCaptureSkipped`")) },
            { assertTrue(!englishMismatchMatrixContent.contains("currently does not emit a `TypeMismatch` event")) },
            { assertTrue(!englishMismatchMatrixContent.contains("returns an empty collection or `null` depending on config")) },
            { assertTrue(!englishMismatchMatrixContent.contains("returns an empty map, skips the bad entry, or returns `null` depending on config")) },
            { assertTrue(!englishMismatchMatrixContent.contains("Default handling: skips raw JSON capture and continues through the normal converter path")) },
            { assertTrue(!englishMismatchMatrixContent.contains("keeps the field default or uses a safe primitive value")) },
            { assertTrue(!englishMismatchMatrixContent.contains("keeps the field default or uses a safe boolean value")) },
            { assertTrue(englishConfigurationContent.contains("Do not add business model package prefixes here")) },
            { assertTrue(englishConfigurationContent.contains("Root object mismatch")) },
            { assertTrue(englishConfigurationContent.contains("dispatchEvent")) },
            { assertTrue(englishConfigurationContent.contains("does not write into the current `parseSafe` event snapshot")) },
            { assertTrue(englishConfigurationContent.contains("bounded probing for unknown-length gzip or chunked bodies")) },
            { assertTrue(androidProguardContent.contains("android.enableR8.fullMode=false")) },
            { assertTrue(androidProguardContent.contains("不要把业务模型包名前缀放这里")) },
            { assertTrue(androidProguardContent.contains("GsonBuilder 内部字段")) },
            { assertTrue(androidProguardContent.contains("java.util.ArrayDeque reflectionFilters")) },
            { assertTrue(androidProguardContent.contains("先选接入方案")) },
            { assertTrue(androidProguardContent.contains("新项目接入")) },
            { assertTrue(androidProguardContent.contains("老项目快速接入")) },
            { assertTrue(androidProguardContent.contains("老项目低成本接入")) },
            { assertTrue(androidProguardContent.contains("逐步收窄模板")) },
            { assertTrue(androidProguardContent.contains("不要求立刻全量补 `@SerializedName`")) },
            { assertTrue(androidProguardContent.contains("bean、model、entity、response、dto")) },
            { assertTrue(androidProguardContent.contains("Android AAR 会自动把框架自身 consumer ProGuard 规则合并进用户 App")) },
            { assertTrue(!androidProguardContent.contains("普通 JVM Jar")) },
            { assertTrue(androidProguardContent.contains("debug 包或未开启 minify 的包可以先零配置试用")) },
            { assertTrue(androidProguardContent.contains("`android.enableR8.fullMode=false` 不是零混淆配置开关")) },
            { assertTrue(androidProguardContent.contains("R8 fullMode 选择")) },
            { assertTrue(androidProguardContent.contains("release 验证清单")) },
            { assertTrue(androidProguardContent.contains("`android.enableR8.fullMode=true` 是长期推荐路径")) },
            { assertTrue(androidProguardContent.contains("`android.enableR8.fullMode=false` 是可选兼容策略")) },
            { assertTrue(gettingStartedContent.contains("零配置只适合 debug 或未开启 minify 的试用")) },
            { assertTrue(androidProguardContent.contains("@SerializedName` 只能固定 JSON 字段名")) },
            { assertTrue(androidProguardContent.contains("不能替代 Kotlin Metadata 和构造方法 keep")) },
            { assertTrue(englishAndroidProguardContent.contains("android.enableR8.fullMode=false")) },
            { assertTrue(englishAndroidProguardContent.contains("do not add business model package prefixes here")) },
            { assertTrue(englishAndroidProguardContent.contains("GsonBuilder internals")) },
            { assertTrue(englishAndroidProguardContent.contains("java.util.ArrayDeque reflectionFilters")) },
            { assertTrue(englishAndroidProguardContent.contains("Choose An Integration Path First")) },
            { assertTrue(englishAndroidProguardContent.contains("New Project Integration")) },
            { assertTrue(englishAndroidProguardContent.contains("Legacy Project Quick Integration")) },
            { assertTrue(englishAndroidProguardContent.contains("legacy project low-cost integration")) },
            { assertTrue(englishAndroidProguardContent.contains("Narrowing Template")) },
            { assertTrue(englishAndroidProguardContent.contains("annotate every field with `@SerializedName` on day one")) },
            { assertTrue(englishAndroidProguardContent.contains("bean, model, entity, response, dto")) },
            { assertTrue(englishAndroidProguardContent.contains("Android AAR automatically merges the framework consumer ProGuard rules into the user's App")) },
            { assertTrue(!englishAndroidProguardContent.contains("plain JVM jars")) },
            { assertTrue(englishAndroidProguardContent.contains("debug builds or non-minified builds can try the library with zero ProGuard config")) },
            { assertTrue(englishAndroidProguardContent.contains("`android.enableR8.fullMode=false` is not a zero-ProGuard-config switch")) },
            { assertTrue(englishAndroidProguardContent.contains("R8 fullMode Choice")) },
            { assertTrue(englishAndroidProguardContent.contains("Release Verification Checklist")) },
            { assertTrue(englishAndroidProguardContent.contains("`android.enableR8.fullMode=true` is the long-term recommended path")) },
            { assertTrue(englishAndroidProguardContent.contains("`android.enableR8.fullMode=false` is an optional compatibility strategy")) },
            { assertTrue(englishGettingStartedContent.contains("Zero config is only for debug or non-minified trials")) },
            { assertTrue(englishAndroidProguardContent.contains("@SerializedName` only fixes JSON field names")) },
            { assertTrue(englishAndroidProguardContent.contains("does not replace Kotlin Metadata and constructor keep rules")) },
            { assertTrue(!demoProguardContent.contains("-keepnames class io.github.logan.gsonsafeparser.demo.model")) },
            { assertTrue(demoProguardContent.contains("AAR consumer rules already keep GsonSafeParser framework internals")) },
            { assertTrue(!demoProguardContent.contains("-keepclassmembers class com.google.gson.GsonBuilder")) },
            { assertTrue(demoProguardContent.contains("-keep class io.github.logan.gsonsafeparser.demo.model")) },
            { assertTrue(demoProguardContent.contains("-keep class io.github.logan.gsonsafeparser.demo.model.** {\n    <fields>;\n    public <init>(...);\n}")) },
            { assertTrue(demoProguardContent.contains("old projects should start with package-level keep")) },
            { assertTrue(demoAppContent.contains("用户 JSON 验证")) },
            { assertTrue(demoAppContent.contains("疑似模型字段被混淆")) },
            { assertTrue(englishDemoAppContent.contains("Custom JSON validation")) },
            { assertTrue(demoAppContent.contains("stableKey")) },
            { assertTrue(englishDemoAppContent.contains("structured rows")) },
            { assertTrue(englishDemoAppContent.contains("suspected model field obfuscation")) },
            { assertTrue(englishTroubleshootingContent.contains("# Troubleshooting")) },
            { assertTrue(troubleshootingContent.contains("AGP 升级后 Kotlin data class 默认值失效")) },
            { assertTrue(troubleshootingContent.contains("@SerializedName` 不能替代构造方法和 Metadata keep")) },
            { assertTrue(englishTroubleshootingContent.contains("Kotlin data class defaults fail after an AGP upgrade")) },
            { assertTrue(englishTroubleshootingContent.contains("@SerializedName` does not replace constructor and Metadata keep rules")) },
            { assertTrue(englishTroubleshootingContent.contains("Business model packages should be protected through ProGuard keep rules")) },
            { assertTrue(englishTroubleshootingContent.contains("Gson version compatibility")) },
            { assertTrue(troubleshootingContent.contains("断网、请求取消、连接重置、TLS 失败")) },
            { assertTrue(englishTroubleshootingContent.contains("Offline state, request cancellation, connection reset, TLS failure")) },
            { assertTrue(englishTroubleshootingContent.contains("OkHttp may return `contentLength=-1`")) },
            { assertTrue(englishTroubleshootingContent.contains("skipReason=UnknownLengthExceedsLimit")) },
            { assertTrue(demoAppContent.contains("./gradlew :demo-app:assembleRelease")) },
            { assertTrue(englishDemoAppContent.contains("./gradlew :demo-app:assembleRelease")) },
            { assertTrue(releaseChecklistContent.contains("# 发布清单")) },
            { assertTrue(releaseChecklistContent.contains("1.0.2")) },
            { assertTrue(releaseChecklistContent.contains("publishToMavenLocal")) },
            { assertTrue(releaseChecklistContent.contains("Dokka javadoc.jar 离线生成")) },
            { assertTrue(releaseChecklistContent.contains("--warning-mode=fail")) },
            { assertTrue(releaseChecklistContent.contains("releaseToMavenCentral --dry-run")) },
            { assertTrue(releaseChecklistContent.contains(":demo-app:assembleDebug")) },
            { assertTrue(releaseChecklistContent.contains("gson-safe-parser-core-1.0.2.aar")) },
            { assertTrue(releaseChecklistContent.contains("docs/release-notes-1.0.2.md")) },
            { assertTrue(releaseChecklistContent.contains("docs/release-notes-1.0.1.md")) },
            { assertTrue(releaseChecklistContent.contains("docs/release-notes-1.0.0.md")) },
            { assertTrue(releaseChecklistContent.contains("releaseToMavenCentral")) },
            { assertTrue(englishReleaseChecklistContent.contains("# Release Checklist")) },
            { assertTrue(englishReleaseChecklistContent.contains("1.0.2")) },
            { assertTrue(englishReleaseChecklistContent.contains("Dokka javadoc.jar is generated offline")) },
            { assertTrue(englishReleaseChecklistContent.contains("--warning-mode=fail")) },
            { assertTrue(englishReleaseChecklistContent.contains("releaseToMavenCentral --dry-run")) },
            { assertTrue(englishReleaseChecklistContent.contains(":demo-app:assembleDebug")) },
            { assertTrue(englishReleaseChecklistContent.contains("gson-safe-parser-core-1.0.2.aar")) },
            { assertTrue(englishReleaseChecklistContent.contains("docs/en/release-notes-1.0.2.md")) },
            { assertTrue(englishReleaseChecklistContent.contains("docs/en/release-notes-1.0.1.md")) },
            { assertTrue(englishReleaseChecklistContent.contains("docs/en/release-notes-1.0.0.md")) },
            { assertTrue(englishReleaseChecklistContent.contains("releaseToMavenCentral")) },
            { assertTrue(releaseNotesContent.contains("# 1.0.2 发布说明")) },
            { assertTrue(releaseNotesContent.contains("传输异常边界修正")) },
            { assertTrue(releaseNotesContent.contains("InterruptedIOException")) },
            { assertTrue(releaseNotesContent.contains("EmptyResponse")) },
            { assertTrue(releaseNotesContent.contains("发布验证")) },
            { assertTrue(englishReleaseNotesContent.contains("# 1.0.2 Release Notes")) },
            { assertTrue(englishReleaseNotesContent.contains("transport exception boundary")) },
            { assertTrue(englishReleaseNotesContent.contains("InterruptedIOException")) },
            { assertTrue(englishReleaseNotesContent.contains("EmptyResponse")) },
            { assertTrue(englishReleaseNotesContent.contains("Release Verification")) },
            { assertTrue(compatibilityContent.contains("# 兼容性说明")) },
            { assertTrue(compatibilityContent.contains("minSdk 23")) },
            { assertTrue(compatibilityContent.contains("compileSdk 36")) },
            { assertTrue(compatibilityContent.contains("JDK 17")) },
            { assertTrue(compatibilityContent.contains("Kotlin 2.0.21")) },
            { assertTrue(compatibilityContent.contains("kotlin-reflect 2.0.21")) },
            { assertTrue(compatibilityContent.contains("Gson 2.13.2")) },
            { assertTrue(compatibilityContent.contains("Retrofit 2.8.1")) },
            { assertTrue(compatibilityContent.contains("Android AAR")) },
            { assertTrue(compatibilityContent.contains("R8 / ProGuard")) },
            { assertTrue(compatibilityContent.contains("强制降级")) },
            { assertTrue(englishCompatibilityContent.contains("# Compatibility")) },
            { assertTrue(englishCompatibilityContent.contains("minSdk 23")) },
            { assertTrue(englishCompatibilityContent.contains("compileSdk 36")) },
            { assertTrue(englishCompatibilityContent.contains("JDK 17")) },
            { assertTrue(englishCompatibilityContent.contains("Kotlin 2.0.21")) },
            { assertTrue(englishCompatibilityContent.contains("kotlin-reflect 2.0.21")) },
            { assertTrue(englishCompatibilityContent.contains("Gson 2.13.2")) },
            { assertTrue(englishCompatibilityContent.contains("Retrofit 2.8.1")) },
            { assertTrue(englishCompatibilityContent.contains("Android AAR")) },
            { assertTrue(englishCompatibilityContent.contains("R8 / ProGuard")) },
            { assertTrue(englishCompatibilityContent.contains("forced downgrade")) },
            { assertTrue(changelogContent.contains("# Changelog")) },
            { assertTrue(changelogContent.contains("## 1.0.2")) },
            { assertTrue(changelogContent.contains("## 1.0.1")) },
            { assertTrue(changelogContent.contains("## 1.0.0")) },
            { assertTrue(changelogContent.contains("docs/release-notes-1.0.2.md")) },
            { assertTrue(changelogContent.contains("docs/release-notes-1.0.1.md")) },
            { assertTrue(previousReleaseNotesContent.contains("# 1.0.1 发布说明")) },
            { assertTrue(previousReleaseNotesContent.contains("稳定性修正")) },
            { assertTrue(englishPreviousReleaseNotesContent.contains("# 1.0.1 Release Notes")) },
            { assertTrue(englishPreviousReleaseNotesContent.contains("stabilization release")) },
            { assertTrue(changelogContent.contains("First public release")) },
            { assertTrue(changelogContent.contains("docs/release-notes-1.0.0.md")) },
            { assertTrue(historicalReleaseNotesContent.contains("# 1.0.0 发布说明")) },
            { assertTrue(historicalReleaseNotesContent.contains("首个公开发布版本")) },
            { assertTrue(historicalReleaseNotesContent.contains("首发能力")) },
            { assertTrue(historicalReleaseNotesContent.contains("兼容边界")) },
            { assertTrue(englishHistoricalReleaseNotesContent.contains("# 1.0.0 Release Notes")) },
            { assertTrue(englishHistoricalReleaseNotesContent.contains("first public release")) },
            { assertTrue(englishHistoricalReleaseNotesContent.contains("Initial Capabilities")) },
            { assertTrue(englishHistoricalReleaseNotesContent.contains("Compatibility Boundaries")) }
        )
    }

    /**
     * 测试方法说明：验证公开安装版本号、Demo 版本号和 Gradle 发布版本不会漂移。
     * 当前发版前要同时锁住 core、retrofit、README、快速开始和 demo versionName。
     */
    @Test
    fun `public version snippets stay aligned with gradle publishing version`() {
        val rootBuildContent = projectRoot().resolve("build.gradle.kts").readText()
        val demoBuildContent = projectRoot().resolve("demo-app/build.gradle.kts").readText()
        val chineseReadme = projectRoot().resolve("README.md").readText()
        val englishReadme = projectRoot().resolve("README_EN.md").readText()
        val chineseGettingStarted = projectRoot().resolve("docs/getting-started.md").readText()
        val englishGettingStarted = projectRoot().resolve("docs/en/getting-started.md").readText()
        val version = Regex("""version = "([^"]+)"""")
            .find(rootBuildContent)
            ?.groupValues
            ?.get(1)

        val publicDocs = listOf(
            chineseReadme,
            englishReadme,
            chineseGettingStarted,
            englishGettingStarted
        )

        assertAll(
            { assertEquals("1.0.2", version) },
            { assertTrue(demoBuildContent.contains("versionName = \"$version\"")) },
            {
                publicDocs.forEach { content ->
                    assertTrue(content.contains("gson-safe-parser-core:$version"))
                    assertTrue(content.contains("gson-safe-parser-retrofit:$version"))
                }
            }
        )
    }

    private fun projectRoot(): Path {
        return generateSequence(Path.of("").toAbsolutePath()) { path -> path.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    private fun currentMarkdownDocFiles(): List<Path> {
        val root = projectRoot()
        val historicalReleaseNotes = emptySet<String>()
        val docsStream = Files.walk(root.resolve("docs"))
        val docs = try {
            docsStream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".md") }
                .filter { !historicalReleaseNotes.contains(it.fileName.toString()) }
                .toList()
        } finally {
            docsStream.close()
        }
        return listOf(root.resolve("README.md"), root.resolve("README_EN.md")) + docs
    }
}
