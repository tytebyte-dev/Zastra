package app.zastra.vpn.util

import android.util.Log
import app.zastra.vpn.core.util.ZLog
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Кольцевой буфер журнала для экрана «Журнал» + дублирование в logcat. */
object LogBuffer {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun install() {
        ZLog.sink = { level, tag, message ->
            val prio = when (level) {
                ZLog.DEBUG -> Log.DEBUG
                ZLog.INFO -> Log.INFO
                ZLog.WARN -> Log.WARN
                else -> Log.ERROR
            }
            Log.println(prio, "Zastra/$tag", message)
            if (level >= ZLog.INFO) add(level, tag, message)
        }
    }

    @Synchronized
    private fun add(level: Int, tag: String, message: String) {
        val mark = when (level) {
            ZLog.WARN -> "W"
            ZLog.ERROR -> "E"
            else -> "I"
        }
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(fmt.format(Date()) + " " + mark + " " + tag + ": " + message)
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
