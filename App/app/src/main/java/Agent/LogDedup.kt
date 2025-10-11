package Agent

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 日志节流与合并工具：
 * - 针对每个 (tag,msg) 键进行时间窗节流；窗口内只打印首条，其余累计。
 * - 在窗口结束时输出一条汇总："msg (重复N次已合并)"，N为除首条外的次数。
 * - 支持不同消息的交替触发，分别独立统计并合并，避免日志爆炸。
 */
object LogDedup {
    private data class Entry(
        val tag: String,
        val msg: String,
        var count: Int = 0,
        var firstTs: Long = 0L,
        var lastTs: Long = 0L
    )

    private val entries = HashMap<String, Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnables = HashMap<String, Runnable>()

    private const val DEFAULT_THROTTLE_MS = 500L

    @Synchronized
    fun d(tag: String, msg: String, throttleMs: Long = DEFAULT_THROTTLE_MS) {
        val key = "$tag|$msg"
        val now = System.currentTimeMillis()

        val entry = entries.getOrPut(key) {
            Entry(tag, msg, 0, now, now)
        }

        if (entry.count == 0) {
            // 窗口内首条立即打印
            Log.d(tag, msg)
            entry.firstTs = now
        }

        entry.count++
        entry.lastTs = now

        scheduleFlush(key, throttleMs)
    }

    @Synchronized
    private fun scheduleFlush(key: String, delayMs: Long) {
        // 若已有计划，重置延迟以在最后一次触发后再汇总
        flushRunnables[key]?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            synchronized(this) {
                val e = entries.remove(key)
                flushRunnables.remove(key)
                if (e != null && e.count > 1) {
                    // 打印除首条外的累计次数
                    Log.d(e.tag, "${e.msg} (重复${e.count - 1}次已合并)")
                }
            }
        }
        flushRunnables[key] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /** 主动冲洗所有键的汇总输出 */
    @Synchronized
    fun flush() {
        // 取消所有定时并立即输出汇总
        flushRunnables.values.forEach { handler.removeCallbacks(it) }
        flushRunnables.clear()
        entries.values.forEach { e ->
            if (e.count > 1) {
                Log.d(e.tag, "${e.msg} (重复${e.count - 1}次已合并)")
            }
        }
        entries.clear()
    }
}