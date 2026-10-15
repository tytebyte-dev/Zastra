package app.zastra.vpn.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Сквозной режим (edge-to-edge): отступы под строку состояния, панель навигации,
 * вырез экрана и клавиатуру.
 */
private const val MAX_CONTENT_DP = 720

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
        // На планшетах и ТВ содержимое не растягиваем на весь экран: ограничиваем ширину и центрируем.
        val widthDp = v.resources.configuration.screenWidthDp
        val extra = if (widthDp > MAX_CONTENT_DP) {
            ((widthDp - MAX_CONTENT_DP) / 2f * v.resources.displayMetrics.density).toInt()
        } else {
            0
        }
        v.setPadding(l + maxOf(m.left, extra), t + m.top, r + maxOf(m.right, extra), b + m.bottom)
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

/** Android TV и другие телевизоры: управление пультом, без касаний. */
fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    return pm.hasSystemFeature("android.software.leanback") ||
        pm.hasSystemFeature("android.hardware.type.television")
}
