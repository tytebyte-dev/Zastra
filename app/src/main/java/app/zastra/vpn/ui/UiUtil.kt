package app.zastra.vpn.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Сквозной режим (edge-to-edge): отступы под строку состояния, панель навигации,
 * вырез экрана и клавиатуру.
 */
fun View.applySystemBarInsets() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val m = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        v.setPadding(l + m.left, t + m.top, r + m.right, b + m.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

fun formatDateTime(context: Context, millis: Long): String =
    DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
    )
