package io.github.logan.gsonsafeparser.demo.support

import io.github.logan.gsonsafeparser.demo.R
import java.util.Locale

internal object DemoTextLocalizer {
    internal val pageTitleResIds = intArrayOf(
        R.string.demo_page_quick_check_title,
        R.string.demo_page_custom_json_title,
        R.string.demo_page_core_title,
        R.string.demo_page_config_title,
        R.string.demo_page_observability_title,
        R.string.demo_page_retrofit_title
    )

    internal val pageDescriptionResIds = intArrayOf(
        R.string.demo_page_quick_check_description,
        R.string.demo_page_custom_json_description,
        R.string.demo_page_core_description,
        R.string.demo_page_config_description,
        R.string.demo_page_observability_description,
        R.string.demo_page_retrofit_description
    )

    internal val pageActionResIds = arrayOf(
        intArrayOf(R.string.demo_action_run_full_check, R.string.demo_action_copy_report),
        intArrayOf(R.string.demo_action_fill_sample, R.string.demo_action_verify_json, R.string.demo_action_copy_result),
        intArrayOf(R.string.demo_action_run_case, R.string.demo_action_restore_json, R.string.demo_action_copy_result),
        intArrayOf(R.string.demo_action_run_case, R.string.demo_action_restore_json, R.string.demo_action_copy_result),
        intArrayOf(R.string.demo_action_run_case, R.string.demo_action_restore_json, R.string.demo_action_copy_result),
        intArrayOf(R.string.demo_action_run_case, R.string.demo_action_restore_json, R.string.demo_action_copy_result)
    )

    internal val entryTitleResIds = intArrayOf(
        R.string.demo_entry_core_title,
        R.string.demo_entry_retrofit_title
    )

    internal val entryDescriptionResIds = intArrayOf(
        R.string.demo_entry_core_description,
        R.string.demo_entry_retrofit_description
    )

    internal val targetTitleResIds = intArrayOf(
        R.string.demo_target_api_response_title,
        R.string.demo_target_nullable_api_response_title,
        R.string.demo_target_list_user_title,
        R.string.demo_target_collection_title,
        R.string.demo_target_nullable_collection_title,
        R.string.demo_target_primitive_title,
        R.string.demo_target_map_title,
        R.string.demo_target_map_edge_title,
        R.string.demo_target_any_title,
        R.string.demo_target_enum_title,
        R.string.demo_target_org_json_title
    )

    internal val targetDescriptionResIds = intArrayOf(
        R.string.demo_target_api_response_description,
        R.string.demo_target_nullable_api_response_description,
        R.string.demo_target_list_user_description,
        R.string.demo_target_collection_description,
        R.string.demo_target_nullable_collection_description,
        R.string.demo_target_primitive_description,
        R.string.demo_target_map_description,
        R.string.demo_target_map_edge_description,
        R.string.demo_target_any_description,
        R.string.demo_target_enum_description,
        R.string.demo_target_org_json_description
    )

    internal val policyTitleResIds = intArrayOf(
        R.string.demo_policy_default_title,
        R.string.demo_policy_low_interference_title,
        R.string.demo_policy_debug_raw_json_title,
        R.string.demo_policy_primitive_delegate_title,
        R.string.demo_policy_object_number_title,
        R.string.demo_policy_shape_coercion_title,
        R.string.demo_policy_raw_json_truncate_title
    )

    internal val policyDescriptionResIds = intArrayOf(
        R.string.demo_policy_default_description,
        R.string.demo_policy_low_interference_description,
        R.string.demo_policy_debug_raw_json_description,
        R.string.demo_policy_primitive_delegate_description,
        R.string.demo_policy_object_number_description,
        R.string.demo_policy_shape_coercion_description,
        R.string.demo_policy_raw_json_truncate_description
    )

    private val technicalEnglishReplacements = linkedMapOf(
        "类型错配（TypeMismatch）" to "Type mismatch (TypeMismatch)",
        "JSON 形态转换（ShapeCoercion）" to "JSON shape coercion (ShapeCoercion)",
        "Adapter 创建失败（AdapterCreationFailure）" to "Adapter creation failure (AdapterCreationFailure)",
        "空响应（EmptyResponse）" to "Empty response (EmptyResponse)",
        "跳过原始 JSON 捕获（RawJsonCaptureSkipped）" to "Raw JSON capture skipped (RawJsonCaptureSkipped)",
        "这次验证会把同一份 JSON 分别交给 SafeParser 和原生 Gson，用来判断当前接口返回是否适合接入 GsonSafeParser。" to "This check sends the same JSON to SafeParser and native Gson so you can judge whether the current API response is ready for GsonSafeParser.",
        "字段路径=" to "Path=",
        "字段名=" to "Field name=",
        "期望类型=" to "Expected type=",
        "实际 JSON 类型=" to "Actual JSON token=",
        "转换动作=" to "Coercion action=",
        "丢弃元素数量=" to "Discarded item count=",
        "问题位置=" to "Mismatch location=",
        "Map 条目 key=" to "Map item key=",
        "原始 JSON=" to "Raw JSON=",
        "原始 JSON 是否截断=" to "Raw JSON truncated=",
        "响应体大小=" to "Response body size=",
        "捕获上限=" to "Capture limit=",
        "处理结果=" to "Result=",
        "处理策略=" to "Policy=",
        "目标类型=" to "Target type=",
        "原因=" to "Reason=",
        "对象字段" to "Object field",
        "集合元素" to "List item",
        "Map 条目" to "Map item",
        "无事件" to "No events",
        "无异常" to "No exception",
        "无未捕获运行异常；场景内捕获异常请看解析输出。" to "No uncaught demo runtime exception. Scenario-captured exceptions are shown in parsed output.",
        "未生成契约报告" to "No contract report",
        "未生成观察者失败报告" to "No observer failure report",
        "未运行诊断" to "Diagnostics not run",
        "=是" to "=Yes",
        "=否" to "=No",
        "未捕获" to "Not captured",
        "问题描述：" to "Problem description:",
        "当前选择：" to "Current selection:",
        "验证入口：" to "Entry point:",
        "验证入口" to "Entry point",
        "入口说明：" to "Entry description:",
        "入口说明" to "Entry description",
        "目标类型：" to "Target type:",
        "目标类型" to "Target type",
        "目标说明：" to "Target description:",
        "目标说明" to "Target description",
        "解析策略：" to "Parse policy:",
        "解析策略" to "Parse policy",
        "策略说明：" to "Policy description:",
        "策略说明" to "Policy description",
        "SafeParser 结果：" to "SafeParser result:",
        "原生 Gson 对比：" to "Native Gson comparison:",
        "接入建议：" to "Adoption advice:",
        "期望表现：" to "Expected behavior:",
        "格式化结果：" to "Formatted result:",
        "格式化结果" to "Formatted result",
        "解析成功" to "parsed successfully",
        "解析失败" to "failed to parse",
        "异常类型：" to "Exception type:",
        "异常类型" to "Exception type",
        "异常信息：" to "Exception message:",
        "异常信息" to "Exception message",
        "异常详情：" to "Exception details:",
        "异常详情" to "Exception details",
        "运行异常：" to "Run error:",
        "通过" to "Pass",
        "失败" to "Fail",
        "功能分类：" to "Feature category:",
        "覆盖入口：" to "Covered API:",
        "输入 JSON：" to "Input JSON:",
        "预期结果：" to "Expected result:",
        "解析输出：" to "Parsed output:",
        "事件流：" to "Event stream:",
        "契约报告：" to "Contract report:",
        "观察者失败报告：" to "Observer failure report:",
        "诊断信息：" to "Diagnostics:",
        "异常信息：" to "Exception details:",
        "Core fromJson" to "Core fromJson",
        "Retrofit Converter" to "Retrofit Converter",
        "直接调用 GsonSafeParser.fromJson，适合验证普通 Gson 接入。" to "Calls GsonSafeParser.fromJson directly. Use this when integrating through plain Gson.",
        "通过 GsonSafeConverterFactory 模拟 Retrofit 响应体转换，适合验证 Retrofit 接入。" to "Runs through GsonSafeConverterFactory and simulates Retrofit response conversion. Use this when integrating through Retrofit.",
        "ApiResponse<User>" to "ApiResponse<User>",
        "NullableApiResponse<User>" to "NullableApiResponse<User>",
        "List<User>" to "List<User>",
        "CollectionResponse" to "CollectionResponse",
        "NullableCollectionMapResponse" to "NullableCollectionMapResponse",
        "PrimitiveResponse" to "PrimitiveResponse",
        "MapResponse" to "MapResponse",
        "MapEdgeResponse" to "MapEdgeResponse",
        "AnyResponse" to "AnyResponse",
        "EnumContainerResponse" to "EnumContainerResponse",
        "OrgJsonResponse" to "OrgJsonResponse",
        "默认契约优先" to "Contract-first default",
        "低误伤 NullOnly" to "Low-interference NullOnly",
        "调试 rawJson" to "Debug rawJson",
        "基础类型交回 Gson" to "Primitive delegate to Gson",
        "Object 数字 Long/Double" to "Object numbers Long/Double",
        "JSON 形态转换" to "JSON shape coercion",
        "rawJson 10 字节截断" to "Raw JSON 10-byte truncation",
        "常见接口壳：code + 非空 data 对象，适合验证 data 返回 []、{}、null 等情况。" to "Common API envelope: code + non-null data object. Good for testing data=[] , data={} , or data=null.",
        "data 可空的接口壳，适合验证 NullOnly 或低误伤策略下的表现。" to "API envelope with nullable data. Good for checking NullOnly or low-interference behavior.",
        "根节点就是用户列表，适合验证坏 item 是否会被跳过。" to "The root node is a user list. Good for checking whether invalid items can be skipped.",
        "同时包含 List、Set、Map 和数字列表，适合验证集合整体错形和 item 跳过。" to "Contains List, Set, Map, and number lists. Good for checking collection shape mismatches and item skipping.",
        "集合和 Map 都允许为 null，适合验证 NullOnly 策略下是否比默认空容器更符合业务预期。" to "Collections and maps can both be null. Good for checking whether NullOnly matches business expectations better than empty containers.",
        "包含 Int、Long、BigDecimal、Boolean 和 String，适合验证基础类型错形。" to "Contains Int, Long, BigDecimal, Boolean, and String. Good for checking primitive mismatches.",
        "包含对象字段和强类型 Map，适合验证 Map key/value 解析和局部跳过。" to "Contains an object field and a strongly typed Map. Good for checking Map key/value parsing and partial skipping.",
        "包含 Int key Map 和嵌套 Map，适合验证坏 key 跳过、mapItemKey 事件和数组 entry 形式。" to "Contains Int-key maps and nested Maps. Good for checking bad key skipping, mapItemKey events, and array entry format.",
        "包含 Any 和 Map<String, Any>，适合观察 Object 数字策略和动态字段。" to "Contains Any and Map<String, Any>. Good for observing Object number strategy and dynamic fields.",
        "同时包含 EnumSet 和 EnumMap，适合验证枚举容器是否能在真实 App 里正常构造。" to "Contains EnumSet and EnumMap. Good for checking whether enum containers can be built correctly in a real app.",
        "包含 JSONObject 和 JSONArray，适合验证 org.json 类型适配。" to "Contains JSONObject and JSONArray. Good for checking org.json adapter support.",
        "使用 SafeParser 1.0.4 默认配置，适合观察错配证据和默认返回边界。" to "Uses the SafeParser 1.0.4 default config. Good for observing mismatch evidence and default return boundaries.",
        "字段错形尽量给 null，基础类型交回 Gson，更接近原生 Gson。" to "Returns null for many mismatched fields and delegates primitives back to Gson, staying closer to native Gson.",
        "开启 rawJson 捕获，适合联调时把后端原始返回和错配事件一起带出来。" to "Enables rawJson capture so you can inspect the backend response together with mismatch events during integration testing.",
        "基础类型完全交回 Gson 原生 Adapter，适合验证低误伤接入时哪些异常会重新暴露出来。" to "Delegates primitive parsing completely to native Gson Adapter. Good for checking which errors reappear during low-interference rollout.",
        "Any/Object 数字使用 Gson 的 LONG_OR_DOUBLE 策略，适合验证动态字段类型是否符合项目预期。" to "Uses Gson's LONG_OR_DOUBLE policy for Any/Object numbers. Good for checking whether dynamic number types match project expectations.",
        "显式开启对象和集合形态转换，适合验证对象字段收到数组、集合字段收到对象的接口。" to "Explicitly enables object and collection shape coercion. Good for checking object fields returned as arrays and collection fields returned as objects.",
        "开启 rawJson 捕获但把上限降到 10 字节，适合验证日志截断和 rawJsonTruncated 标记。" to "Enables rawJson capture but lowers the limit to 10 bytes. Good for checking truncation and the rawJsonTruncated flag.",
        "SafeParser 解析失败" to "SafeParser failed to parse",
        "原生 Gson 解析失败" to "Native Gson failed to parse",
        "原生 Gson 会Fail，SafeParser 可以继续解析；重点看事件流里的字段路径，确认兜底值是否符合业务预期。" to "Native Gson fails here, but SafeParser can continue parsing. Check the field paths in the event stream and confirm whether the fallback value fits your business rules.",
        "原生 Gson 会失败，SafeParser 可以继续解析；重点看事件流里的字段路径，确认兜底值是否符合业务预期。" to "Native Gson fails here, but SafeParser can continue parsing. Check the field paths in the event stream and confirm whether the fallback value fits your business rules.",
        "两边都能返回结果，但 SafeParser 发现了契约问题；建议先在测试环境收集事件，再决定是否线上接入。" to "Both sides can return a result, but SafeParser found a contract issue. Collect events in test first before deciding on production rollout.",
        "当前 JSON 与目标类型匹配度较高，SafeParser 和原生 Gson 表现接近，可以继续用更异常的 JSON 做验证。" to "This JSON already matches the target type fairly well, so SafeParser and native Gson behave similarly. Try more malformed JSON next.",
        "SafeParser 也未能解析成功，当前场景应回归 Gson 默认失败策略；建议修正后端契约，或为该字段提供自定义 TypeAdapter。" to "SafeParser also failed here. Fall back to native Gson failure behavior for this scenario, or fix the backend contract / add a custom TypeAdapter.",
        "原生 Gson" to "Native Gson",
        "核心解析" to "Core parsing",
        "公开 API" to "Public API",
        "Kotlin API" to "Kotlin API",
        "配置" to "Configuration",
        "接入自检" to "Integration check",
        "安全回退" to "Safe fallback",
        "注解" to "Annotations",
        "可观测性" to "Observability",
        "兼容能力" to "Compatibility",
        "返回默认对象" to "Return default object",
        "返回 null" to "Return null",
        "交回 Gson 默认处理" to "Delegate to native Gson"
    )

    internal fun localizeTechnicalTextForDisplay(text: String?, isChineseDemo: Boolean): String {
        val cleaned = text.orEmpty().trimStart()
        if (isChineseDemo || cleaned.isBlank()) return cleaned
        return cleaned.lineSequence().joinToString(separator = "\n") { line ->
            localizeTechnicalLine(line)
        }
    }

    private fun localizeTechnicalLine(line: String): String {
        if (line.isBlank()) return line
        if (shouldPreserveLiteralLine(line)) return line
        val equalsIndex = line.indexOf('=')
        if (equalsIndex >= 0 && shouldPreserveLiteralSuffix(line.substring(equalsIndex + 1))) {
            return localizePrefix(line, equalsIndex, "=")
        }
        val colonIndex = line.indexOf('：')
        if (colonIndex >= 0 && shouldPreserveLiteralSuffix(line.substring(colonIndex + 1))) {
            return localizePrefix(line, colonIndex, "：")
        }
        return technicalEnglishReplacements.entries.fold(line) { value, (source, replacement) ->
            value.replace(source, replacement)
        }
    }

    private fun localizePrefix(line: String, separatorIndex: Int, separator: String): String {
        val prefix = line.substring(0, separatorIndex)
        val suffix = line.substring(separatorIndex + 1)
        val localizedPrefix = technicalEnglishReplacements.entries.fold(prefix) { value, (source, replacement) ->
            value.replace(source, replacement)
        }
        val localizedSuffix = technicalEnglishReplacements.entries.fold(suffix) { value, (source, replacement) ->
            value.replace(source, replacement)
        }
        val localizedSeparator = if (separator == "：") ":" else separator
        val suffixSpacing = if (localizedSeparator == ":" && localizedSuffix.isNotBlank()) " " else ""
        return localizedPrefix + localizedSeparator + suffixSpacing + localizedSuffix
    }

    private fun shouldPreserveLiteralLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("{") ||
            trimmed.startsWith("}") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith("]") ||
            trimmed.startsWith("\"") ||
            trimmed.startsWith("at ") ||
            trimmed.startsWith("Caused by:") ||
            trimmed.startsWith("See https://") ||
            trimmed.startsWith("java.") ||
            trimmed.startsWith("kotlin.") ||
            trimmed.startsWith("com.") ||
            trimmed.startsWith("io.") ||
            trimmed.startsWith("org.")
    }

    private fun shouldPreserveLiteralSuffix(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith("\"") ||
            trimmed.startsWith("at ") ||
            trimmed.startsWith("Caused by:") ||
            trimmed.startsWith("java.") ||
            trimmed.startsWith("kotlin.") ||
            trimmed.startsWith("com.") ||
            trimmed.startsWith("io.") ||
            trimmed.startsWith("org.") ||
            trimmed.contains("\"") ||
            trimmed.contains('{') ||
            trimmed.contains('[')
    }
}
