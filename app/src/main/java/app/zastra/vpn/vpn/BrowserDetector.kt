package app.zastra.vpn.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

/** Установленный браузер. */
data class Browser(val packageName: String, val label: String)

/**
 * Находит установленные браузеры: именно для них (и только для них) работает VPN.
 *
 * Критерий. Браузер — приложение, которое
 *  - объявлено системе как браузер (категория APP_BROWSER), либо
 *  - принимает «пустые» ссылки http:// и https:// (то есть любые адреса, а не только свои сайты)
 *    и умеет открывать HTML-файлы. Второе условие отсеивает менеджеры загрузок и подобные
 *    программы, которые тоже перехватывают ссылки.
 *
 * Видимость пакетов обеспечена блоком <queries> в манифесте, разрешение QUERY_ALL_PACKAGES не нужно.
 */
object BrowserDetector {

    fun find(context: Context): List<Browser> {
        val pm = context.packageManager
        val self = context.packageName

        val declared = query(pm, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
            .map { it.activityInfo.packageName }
            .toSet()

        val http = handlersOf(pm, viewIntent("http://"))
        val https = handlersOf(pm, viewIntent("https://"))
        val html = query(
            pm,
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/page.html"), "text/html"),
        ).map { it.activityInfo.packageName }.toSet()
        val generic = http.intersect(https).filter { it in html }.toSet()

        return (declared + generic)
            .filter { it != self }
            .mapNotNull { pkg ->
                try {
                    Browser(pkg, pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString())
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun viewIntent(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun handlersOf(pm: PackageManager, intent: Intent): Set<String> =
        query(pm, intent).map { it.activityInfo.packageName }.toSet()

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
}
