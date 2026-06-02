package io.github.logan.gsonsafeparser.internal

import io.github.logan.gsonsafeparser.SafeParserEvent

/**
 * 本次解析的事件收集上下文。
 *
 * Safe Adapter 创建时会持有当时的 SafeParserConfig。可复用 Parser 包装外部 Gson 时，不能再临时替换
 * 这些 Adapter 里的配置，所以这里用 ThreadLocal 做解析期事件桥。
 *
 * 这个桥只做一件事：在当前线程处于 `parseSafe` 解析期间时，把已经正常分发出来的事件顺手写进本次结果快照。
 * 它不改事件来源，不改事件顺序，不改事件内容；离开 `parseSafe` 以后就是空操作。
 *
 * 也就是说，只有解析链路内部派发的事件会透明经过这里。外部手动注入事件只触发观察回调，
 * 不会写进当前 `parseSafe` 快照。
 */
internal object SafeParseEventContext {
    /**
     * 当前线程的事件收集器。
     *
     * 使用函数类型而不是直接暴露 MutableList，是为了让后续实现可以替换存储方式，同时不影响分发入口。
     */
    private val current = ThreadLocal<((SafeParserEvent) -> Unit)?>()

    /**
     * 尝试把事件写入当前解析快照。
     *
     * 如果当前线程没有进入 `collectInto`，这里直接返回，不会留下任何副作用。
     *
     * @param event SafeParser 刚刚分发的事件。
     */
    fun emit(event: SafeParserEvent) {
        val collector = current.get() ?: return
        // 事件桥只服务观测结果，不能因为收集失败反向影响 JSON 解析。
        runRecovering {
            collector(event)
        }
    }

    /**
     * 在当前线程临时绑定事件收集器。
     *
     * @param events 本次 parseSafe 要返回的事件列表。
     * @param block 真正执行 Gson 解析的代码块。
     * @return block 的执行结果。
     */
    fun <T> collectInto(
        events: MutableList<SafeParserEvent>,
        block: () -> T
    ): T {
        val previous = current.get()
        current.set { event ->
            events += event
        }
        return try {
            block()
        } finally {
            // parseSafe 可能嵌套调用，退出时必须恢复旧收集器，避免事件串到外层或下一次解析。
            if (previous == null) {
                current.remove()
            } else {
                current.set(previous)
            }
        }
    }
}
