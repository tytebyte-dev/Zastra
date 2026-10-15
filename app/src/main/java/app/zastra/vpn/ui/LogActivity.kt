package app.zastra.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.zastra.vpn.R
import app.zastra.vpn.databinding.ActivityLogBinding
import app.zastra.vpn.util.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Журнал работы: помогает разобраться, почему что-то не подключается. */
class LogActivity : AppCompatActivity() {

    private lateinit var b: ActivityLogBinding
    private var lastText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarInsets()
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_copy -> {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zastra", LogBuffer.text()))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_clear -> {
            LogBuffer.clear()
            refresh()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val text = LogBuffer.text().ifEmpty { getString(R.string.log_empty) }
        if (text == lastText) return
        // Если пользователь у нижнего края — прокручиваем за новыми строками.
        val sv = b.scroll
        val child = sv.getChildAt(0)
        val atBottom = lastText == null || child == null ||
            child.bottom - (sv.height + sv.scrollY) < 80
        lastText = text
        b.logText.text = text
        if (atBottom) sv.post { sv.scrollTo(0, b.logText.bottom) }
    }
}
