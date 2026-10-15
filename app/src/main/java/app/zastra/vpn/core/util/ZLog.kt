package app.zastra.vpn.core.util

/**
 * Минимальный журнал без зависимостей от Android. На Android в [sink] подставляется
 * запись в logcat и в кольцевой буфер для экрана «Журнал».
 */
object ZLog {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3

    @Volatile
    var sink: (level: Int, tag: String, message: String) -> Unit = { level, tag, message ->
        System.err.println("[" + "DIWE"[level.coerceIn(0, 3)] + "] " + tag + ": " + message)
    }

    @Volatile
    var debugEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (debugEnabled) sink(DEBUG, tag, message)
    }

    fun i(tag: String, message: String) = sink(INFO, tag, message)

    fun w(tag: String, message: String, t: Throwable? = null) =
        sink(WARN, tag, if (t == null) message else message + ": " + describe(t))

    fun e(tag: String, message: String, t: Throwable? = null) =
        sink(ERROR, tag, if (t == null) message else message + ": " + describe(t))

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { " (" + it + ")" } ?: "")
}
