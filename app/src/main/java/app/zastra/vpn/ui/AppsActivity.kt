package app.zastra.vpn.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.zastra.vpn.R
import app.zastra.vpn.data.AppMode
import app.zastra.vpn.data.Settings
import app.zastra.vpn.databinding.ActivityAppsBinding
import app.zastra.vpn.databinding.ItemAppBinding
import app.zastra.vpn.vpn.VpnState
import app.zastra.vpn.vpn.VpnStatus
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Выбор приложений, для которых работает VPN (включая системные). */
class AppsActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppsBinding
    private lateinit var settings: Settings
    private lateinit var adapter: AppsAdapter
    private lateinit var iconExecutor: ExecutorService

    private val selected = HashSet<String>()
    private var allApps: List<AppEntry> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        b = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarInsets()
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        selected.addAll(settings.selectedApps)
        iconExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, "zastra-icons").also { it.isDaemon = true } }
        adapter = AppsAdapter(selected, IconLoader(packageManager, iconExecutor), ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)) { entry, checked ->
            if (checked) selected.add(entry.pkg) else selected.remove(entry.pkg)
            persistSelection()
        }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        // Режим работы
        val mode = settings.appMode
        b.modeGroup.check(buttonFor(mode))
        applyMode(mode)
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val m = modeFor(checkedId)
                settings.appMode = m
                applyMode(m)
            }
        }

        b.switchSystem.isChecked = settings.showSystemApps
        b.switchSystem.setOnCheckedChangeListener { _, checked ->
            settings.showSystemApps = checked
            rebuild()
        }
        b.searchInput.doAfterTextChanged {
            query = it?.toString().orEmpty()
            rebuild()
        }
        b.btnSelectAll.setOnClickListener { setVisibleSelected(true) }
        b.btnSelectNone.setOnClickListener { setVisibleSelected(false) }

        // Если VPN уже работает, новые правила применятся только после переподключения.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnState.snapshot.collect { s ->
                    b.restartNote.visibility = if (s.status == VpnStatus.DISCONNECTED) View.GONE else View.VISIBLE
                }
            }
        }

        loadApps()
    }

    override fun onDestroy() {
        iconExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ режим

    private fun buttonFor(m: AppMode) = when (m) {
        AppMode.ALL -> R.id.btnModeAll
        AppMode.INCLUDE -> R.id.btnModeInclude
        AppMode.EXCLUDE -> R.id.btnModeExclude
    }

    private fun modeFor(id: Int) = when (id) {
        R.id.btnModeInclude -> AppMode.INCLUDE
        R.id.btnModeExclude -> AppMode.EXCLUDE
        else -> AppMode.ALL
    }

    private fun applyMode(m: AppMode) {
        b.modeHint.setText(
            when (m) {
                AppMode.ALL -> R.string.apps_hint_all
                AppMode.INCLUDE -> R.string.apps_hint_include
                AppMode.EXCLUDE -> R.string.apps_hint_exclude
            },
        )
        b.selectionGroup.visibility = if (m == AppMode.ALL) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ список

    private fun loadApps() {
        b.loading.visibility = View.VISIBLE
        b.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.Default) { queryApps() }
            b.loading.visibility = View.GONE
            rebuild()
        }
    }

    /** Все приложения, которым вообще доступна сеть (запрашивают INTERNET), включая системные. */
    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        val collator = Collator.getInstance(Locale("ru", "RU"))
        val self = packageName
        return packages.asSequence()
            .filter { it.packageName != self }
            .filter { p -> p.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .map { p ->
                val ai = p.applicationInfo
                val label = try {
                    ai?.loadLabel(pm)?.toString()
                } catch (_: Exception) {
                    null
                }
                AppEntry(
                    pkg = p.packageName,
                    label = label?.takeIf { it.isNotBlank() } ?: p.packageName,
                    isSystem = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith { a, c -> collator.compare(a.label, c.label) }
            .toList()
    }

    private fun visibleApps(): List<AppEntry> {
        val q = query.trim().lowercase(Locale.getDefault())
        val showSystem = settings.showSystemApps
        return allApps.filter { app ->
            (showSystem || !app.isSystem) &&
                (q.isEmpty() || app.label.lowercase(Locale.getDefault()).contains(q) || app.pkg.contains(q))
        }
    }

    private fun rebuild() {
        // Отмеченные приложения показываем первыми (порядок внутри групп — по алфавиту).
        val list = visibleApps().sortedByDescending { it.pkg in selected }
        adapter.submit(list)
        b.emptyText.visibility = if (list.isEmpty() && b.loading.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        updateCounter()
    }

    private fun setVisibleSelected(value: Boolean) {
        for (app in visibleApps()) {
            if (value) selected.add(app.pkg) else selected.remove(app.pkg)
        }
        persistSelection()
        adapter.notifyDataSetChanged()
    }

    private fun persistSelection() {
        settings.selectedApps = selected
        updateCounter()
    }

    private fun updateCounter() {
        b.counterText.text = getString(R.string.apps_counter, selected.size)
    }
}

class AppEntry(val pkg: String, val label: String, val isSystem: Boolean)

/** Загружает значки приложений в фоне и кэширует их. */
private class IconLoader(private val pm: PackageManager, private val executor: ExecutorService) {
    private val cache = LruCache<String, Drawable.ConstantState>(256)
    private val main = Handler(Looper.getMainLooper())

    fun cached(pkg: String): Drawable? = cache.get(pkg)?.newDrawable()

    fun load(pkg: String, callback: (Drawable) -> Unit) {
        try {
            executor.execute {
                val drawable = try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Exception) {
                    return@execute
                }
                val state = drawable.constantState
                if (state != null) cache.put(pkg, state)
                main.post { callback(state?.newDrawable() ?: drawable) }
            }
        } catch (_: RejectedExecutionException) {
            // Экран уже закрыт.
        }
    }
}

private class AppsAdapter(
    private val selected: Set<String>,
    private val icons: IconLoader,
    private val placeholder: Drawable?,
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    class Holder(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val v = holder.b
        v.label.text = entry.label
        v.pkg.text = entry.pkg
        v.systemTag.visibility = if (entry.isSystem) View.VISIBLE else View.GONE
        v.check.isChecked = entry.pkg in selected
        bindIcon(v.icon, entry.pkg)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val item = items[pos]
            onToggle(item, item.pkg !in selected)
            notifyItemChanged(pos)
        }
    }

    private fun bindIcon(view: ImageView, pkg: String) {
        view.tag = pkg
        val cached = icons.cached(pkg)
        if (cached != null) {
            view.setImageDrawable(cached)
            return
        }
        view.setImageDrawable(placeholder)
        icons.load(pkg) { d -> if (view.tag == pkg) view.setImageDrawable(d) }
    }
}
