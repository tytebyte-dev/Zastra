package app.zastra.vpn.core.pac

/**
 * JNI-мост к libzastra-pac.so (QuickJS). Контекст QuickJS не потокобезопасен:
 * все вызовы делаются из одного потока, см. [PacEngine].
 */
object PacNative {
    /** Подменяется в JVM-тестах (там библиотека загружается по абсолютному пути). */
    @Volatile
    var libraryLoader: () -> Unit = { System.loadLibrary("zastra-pac") }

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            libraryLoader()
            loaded = true
        }
    }

    @JvmStatic
    external fun create(): Long

    @JvmStatic
    external fun destroy(handle: Long)

    /** Выполняет JS, возвращает результат как строку; бросает RuntimeException при ошибке JS. */
    @JvmStatic
    external fun eval(handle: Long, code: String, name: String): String?
}
