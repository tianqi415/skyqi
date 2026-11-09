package com.skyqi.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.webkit.URLUtil
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.skyqi.browser.databinding.ActivityMainBinding
import java.io.File

/**
 * 奇云浏览器主界面
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager

    private var isIncognitoMode = false
    private var isNightMode = false
    private var bookmarks: MutableList<Bookmark> = mutableListOf()
    private var tabButtons = mutableListOf<View>()

    companion object {
        private const val REQUEST_STORAGE = 1001
        private const val NAVIGATION_URL = "file:///android_asset/navigation.html"
        private const val NAVIGATION_BASE = "file:///android_asset/"
    }

    // 当前搜索引擎下标：实例字段，从 SearchEngineStore 恢复，不再用静态变量（静态会随进程重建归零）
    private var searchEngineIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局异常捕获：写崩溃日志到文件
        installCrashHandler()

        bookmarks = SharedPreferencesManager.getBookmarks(this)
        // 恢复上次选择的搜索引擎（默认百度）
        searchEngineIndex = SearchEngineStore.getIndex(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tabManager = TabManager(this, binding.webViewContainer)

        setupToolbar()
        setupBottomBar()
        wireWebViewFocus()
        refreshTabBar()
        setupIncognitoBar()

        // 启动时：总是新建并显示导航首页标签（Via 式默认首页）
        handleIntent(intent)
        if (tabManager.size == 0) {
            val tab = tabManager.newTab(getHomePage())
            setupTabCallbacks(tab)
        }
        refreshTabBar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString()
        if (!url.isNullOrEmpty()) {
            val tab = tabManager.newTab(url)
            setupTabCallbacks(tab)
            refreshTabBar()
        }
    }

    // ---------- 工具栏 ----------
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 24.dpToPx()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(filesDir, "crash_log.txt")
                file.appendText("=== ${System.currentTimeMillis()} ===\n")
                file.appendText("Thread: ${thread.name}\n")
                file.appendText(android.util.Log.getStackTraceString(throwable))
                file.appendText("\n")
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            tabManager.current()?.webView?.goBack()
        }
        binding.btnForward.setOnClickListener {
            tabManager.current()?.webView?.goForward()
        }
        binding.btnRefresh.setOnClickListener {
            tabManager.current()?.webView?.reload()
        }
        binding.btnMenu.setOnClickListener { showMenu() }

        // 小锁 ▶ 查看连接安全信息
        binding.btnLock.setOnClickListener { showSecurityInfo() }

        // 地址栏回车 → 加载
        binding.addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                navigateTo(binding.addressBar.text.toString())
                binding.addressBar.clearFocus()
                true
            } else false
        }

        // 清除地址栏按钮
        binding.btnClear.setOnClickListener {
            binding.addressBar.setText("")
            binding.addressBar.requestFocus()
        }
        // 地址栏有文字时显示清除按钮
        binding.addressBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClear.visibility = if (!s.isNullOrEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 点击地址栏 → 全选 URL，方便直接删除/替换
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.addressBar.post {
                    binding.addressBar.selectAll()
                }
            }
        }
        binding.addressBar.setOnClickListener {
            binding.addressBar.selectAll()
        }
    }

    // 返回键双保险：dispatchKeyEvent 优先拦截（兼容所有系统版本）
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (handleBackPressed()) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackPressed(): Boolean {
        val webView = tabManager.current()?.webView
        return when {
            webView != null && webView.canGoBack() -> {
                webView.goBack()
                true
            }
            tabManager.all().size > 1 -> {
                // 多标签：关闭当前标签回上一个
                tabManager.current()?.let { tabManager.closeTab(it.id) }
                refreshTabBar()
                true
            }
            webView != null && !webView.url.isNullOrEmpty() -> {
                // 单标签且无历史：回主页，不退出
                webView.loadUrl(getHomePage())
                true
            }
            else -> false
        }
    }

    // ---------- HTTPS 安全锁 ----------
    private fun updateLockIcon(url: String) {
        val lock = binding.btnLock
        when {
            url.startsWith("https://") -> {
                lock.visibility = View.VISIBLE
                lock.setImageResource(R.drawable.ic_lock)
                lock.setColorFilter(Color.parseColor("#2E7D32")) // 绿色 = 安全
                lock.contentDescription = "连接安全（HTTPS）"
            }
            url.startsWith("http://") -> {
                lock.visibility = View.VISIBLE
                lock.setImageResource(R.drawable.ic_warning)
                lock.setColorFilter(Color.parseColor("#F57C00")) // 橙色 = 提醒
                lock.contentDescription = "连接不安全（HTTP）"
            }
            else -> lock.visibility = View.GONE
        }
    }

    private fun showSecurityInfo() {
        val url = tabManager.current()?.webView?.url ?: return
        val isHttps = url.startsWith("https://")
        val host = try {
            Uri.parse(url).host ?: "未知"
        } catch (e: Exception) { "未知" }

        if (!isHttps) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ 连接不安全")
                .setMessage("网站：$host\n\n数据未加密（HTTP）\n访客与网站之间的信息可能被窃取或篡改。\n\n完整地址：\n$url")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        // HTTPS：异步获取 SSL 证书详情
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("连接安全信息")
            setMessage("正在获取证书…")
            setCancelable(false)
            show()
        }

        Thread {
            var certInfo = "获取失败，请检查网络"
            try {
                val conn = (java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    // 不发送真实请求体，只握手
                    useCaches = false
                }
                try {
                    conn.connect()
                    val certs = conn.serverCertificates
                    val sb = StringBuilder()
                    sb.append("网站：$host\n")
                    sb.append("✅ HTTPS 加密连接\n\n")
                    for ((i, c) in certs.withIndex()) {
                        val cert = c as java.security.cert.X509Certificate
                        val subject = cert.subjectX500Principal.name
                        val issuer = cert.issuerX500Principal.name
                        val notBefore = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(cert.notBefore)
                        val notAfter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(cert.notAfter)
                        val alg = cert.sigAlgName
                        val cn = subject.split(",").firstOrNull { it.trim().startsWith("CN=") }?.trim()?.removePrefix("CN=") ?: subject
                        sb.append(if (i == 0) "📄 服务器证书\n" else "\n🔗 证书链层级 ${i + 1}\n")
                        sb.append("颁发给：$cn\n")
                        if (cert.subjectX500Principal.name != cert.issuerX500Principal.name) {
                            val issuerCn = issuer.split(",").firstOrNull { it.trim().startsWith("CN=") }?.trim()?.removePrefix("CN=") ?: issuer
                            sb.append("颁发者：$issuerCn\n")
                        } else {
                            sb.append("颁发者：自签名（根证书）\n")
                        }
                        sb.append("有效期：$notBefore 至 $notAfter\n")
                        sb.append("签名算法：$alg\n")
                    }
                    certInfo = sb.toString()
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // 握手失败（可能连接被拒）：仍然显示基本安全信息
                certInfo = "网站：$host\n✅ HTTPS 加密连接\n\n（无法获取证书详情：${e.message ?: "连接失败"}）"
            }
            runOnUiThread {
                progress.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🔒 连接安全（SSL 证书）")
                    .setMessage(certInfo)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.start()
    }

    private fun setupBottomBar() {
        // Via 风格底部工具栏
        binding.bottomHome.setOnClickListener {
            // 主页：切换到主页
            tabManager.current()?.webView?.loadUrl(getHomePage())
        }
        binding.bottomBack.setOnClickListener {
            tabManager.current()?.webView?.goBack()
        }
        binding.bottomForward.setOnClickListener {
            tabManager.current()?.webView?.goForward()
        }
        binding.bottomTabs.setOnClickListener { showTabsPanel() }
        binding.bottomMore.setOnClickListener { showMenu() }
    }

    private fun setupTabBar() {
        // 标签动态创建，无需初始化 XML 按钮
    }

    private fun setupIncognitoBar() {
        binding.incognitoBar.visibility = View.GONE
    }

    /** 页面内输入框点击时确保 WebView 有焦点，键盘才会弹出（Via 式体验） */
    private fun wireWebViewFocus() {
        binding.webViewContainer.setOnTouchListener { _, event ->
            val tab = tabManager.current()
            val wv = tab?.webView
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                wv?.let {
                    if (!it.hasFocus()) it.requestFocus()
                }
            }
            false // 不消费，继续传递给 WebView
        }
    }

    private fun createTab() {
        val tab = tabManager.newTab(
            if (isIncognitoMode) "https://www.baidu.com" else getHomePage(),
            isIncognitoMode
        )
        setupTabCallbacks(tab)
        updateIncognitoBar()
        refreshTabBar()
    }

    private fun setupTabCallbacks(tab: TabManager.Tab) {
        tab.webView.setCallback(
            onDownload = { url, ua, cd, mime, len ->
                startDownload(url, ua, cd, mime, len)
            },
            onUrl = { url ->
                if (tabManager.current()?.id == tab.id) {
                    // 导航页不显示 file:// 长地址
                    if (url.startsWith(NAVIGATION_BASE)) {
                        binding.addressBar.setText("")
                    } else {
                        binding.addressBar.setText(url)
                    }
                    tab.url = url
                    updateTabButton(tab)
                    updateLockIcon(url)
                    // 保存历史（无痕模式不保存）
                    if (!tab.isIncognito && !url.startsWith("file://")) {
                        SharedPreferencesManager.addHistory(this, tab.title, url)
                    }
                }
            },
            onTitle = { title ->
                tab.title = title
                updateTabButton(tab)
                // 用完整标题更新历史记录
                if (!tab.isIncognito && tab.url.isNotEmpty()) {
                    SharedPreferencesManager.addHistory(this, title, tab.url)
                }
            },
            onProgress = { progress ->
                if (tabManager.current()?.id == tab.id) {
                    // 进度可显示在刷新按钮上（简化略）
                }
            }
        )
    }

    private fun refreshTabBar() {
        // Via 风格：底部标签按钮只显示数量
        binding.bottomTabsCount.text = tabManager.size.toString()
    }

    private fun showTabsPanel() {
        // Via 风格标签面板：对话框列表展示所有标签，可切换/关闭/新建
        val tabs = tabManager.all()
        val names = tabs.mapIndexed { i, tab ->
            val icon = if (tab.isIncognito) "🕶 " else ""
            val title = tab.title.ifEmpty { "新标签页" }
            val cur = if (tabManager.current()?.id == tab.id) "● " else ""
            cur + icon + title
        }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle("标签页（${tabs.size}）")
            .setItems(names) { _, which ->
                tabManager.switchTo(tabs[which].id)
                binding.addressBar.setText(if (tabs[which].url.startsWith(NAVIGATION_BASE)) "" else tabs[which].url)
                refreshTabBar()
                updateIncognitoBar()
            }
            .setPositiveButton("新建标签页") { _, _ -> createTab() }
            .setNegativeButton("取消", null)

        // 长按某标签关闭？列表模式无法长按。改为：长按项在 ListAdapter 中处理，这里保持简单
        builder.show()
    }

    private fun updateTabButton(tab: TabManager.Tab) {
        // 更新对应标签按钮文字（简单做法：全部刷新）
        refreshTabBar()
    }

    private fun updateIncognitoBar() {
        val anyIncognito = tabManager.all().any { it.isIncognito }
        binding.incognitoBar.visibility = if (anyIncognito) View.VISIBLE else View.GONE
    }

    // ---------- 导航 ----------
    private fun navigateTo(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val url = parseUrl(trimmed)
        tabManager.current()?.webView?.loadUrl(url)
    }

    private fun parseUrl(input: String): String {
        // 已经是 URL
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        // 域名形式
        val isDomain = input.contains(".") && !input.contains(" ")
        if (isDomain) return "https://$input"
        // 否则搜索
        val engine = SearchEngineStore.ENGINES[searchEngineIndex].url
        return engine + Uri.encode(input)
    }

    // ---------- 菜单 ----------
    private fun showMenu() {
        val currentTab = tabManager.current()
        val isBookmarked = currentTab?.url?.let { bookmarks.any { b -> b.url == it } } ?: false

        val items = mutableListOf(
            "书签",
            "历史记录",
            if (isIncognitoMode) "退出无痕模式" else "无痕模式",
            if (isNightMode) "关闭夜间模式" else "夜间模式",
            "搜索引擎：${SearchEngineStore.ENGINES[searchEngineIndex].name}",
            "分享当前页",
            "设置"
        )
        // 书签操作
        if (currentTab?.url?.isNotEmpty() == true) {
            items.add(if (isBookmarked) "取消书签" else "添加书签")
        }

        AlertDialog.Builder(this)
            .setTitle("菜单")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showBookmarks()
                    1 -> showHistory()
                    2 -> toggleIncognito()
                    3 -> toggleNightMode()
                    4 -> showSearchEnginePicker()
                    5 -> shareCurrentPage()
                    6 -> showSettings()
                    else -> toggleBookmark()
                }
            }
            .show()
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    private fun showSearchEnginePicker() {
        val names = SearchEngineStore.ENGINES.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择搜索引擎")
            .setSingleChoiceItems(names, searchEngineIndex) { dialog, which ->
                searchEngineIndex = which
                SearchEngineStore.saveIndex(this, which)
                dialog.dismiss()
                Toast.makeText(this, "已切换到${SearchEngineStore.ENGINES[which].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleIncognito() {
        isIncognitoMode = !isIncognitoMode
        updateIncognitoBar()
        Toast.makeText(this, if (isIncognitoMode) "已开启无痕模式" else "已退出无痕模式", Toast.LENGTH_SHORT).show()
    }

    private fun toggleNightMode() {
        isNightMode = !isNightMode
        for (tab in tabManager.all()) {
            tab.webView.isNightMode = isNightMode
            tab.webView.applyNightMode()
        }
        Toast.makeText(this, if (isNightMode) "夜间模式已开启" else "夜间模式已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun toggleBookmark() {
        val tab = tabManager.current() ?: return
        val url = tab.url
        if (url.isEmpty()) return
        val title = tab.title
        val existing = bookmarks.find { it.url == url }
        if (existing != null) {
            bookmarks.remove(existing)
            SharedPreferencesManager.saveBookmarks(this, bookmarks)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            bookmarks.add(Bookmark(title, url))
            SharedPreferencesManager.saveBookmarks(this, bookmarks)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPage() {
        val tab = tabManager.current() ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${tab.title}\n${tab.url}")
        }
        startActivity(Intent.createChooser(sendIntent, "分享到"))
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(arrayOf("主页设置", "清除缓存", "关于 Skyqi")) { _, which ->
                when (which) {
                    0 -> showHomepageSetting()
                    1 -> clearCache()
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Skyqi")
                        .setMessage("版本 ${appVersionName()}\n基于谷歌 Chromium 内核\n\n开发：天奇\n\n本项目为开源项目，遵循 AGPL-3.0 协议\nhttps://github.com/tianqi415/skyqi")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
            .show()
    }

    private fun showHomepageSetting() {
        val input = EditText(this).apply {
            setText(getHomePage())
            hint = "https://www.example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("主页设置")
            .setMessage("输入主页网址：")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    getSharedPreferences("skyqi_browser", Context.MODE_PRIVATE)
                        .edit().putString("homepage", url).apply()
                    Toast.makeText(this, "主页已设置", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getHomePage(): String {
        // 用户自定义主页优先，否则用内置导航页
        val custom = getSharedPreferences("skyqi_browser", Context.MODE_PRIVATE)
            .getString("homepage", "") ?: ""
        return if (custom.isNotEmpty()) custom else NAVIGATION_URL
    }

    private fun clearCache() {
        tabManager.current()?.webView?.clearCache(true)
        // 全局缓存清除
        val cacheDir = cacheDir
        cacheDir.deleteRecursively()
        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "暂无书签", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = bookmarks.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("书签")
            .setItems(titles) { _, i ->
                val b = bookmarks[i]
                navigateTo(b.url)
            }
            .show()
    }

    private fun showHistory() {
        val history = SharedPreferencesManager.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }

        // 自定义列表：每行标题 + URL + 删除叉
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }
        var dialog: AlertDialog? = null

        fun rebuildList() {
            listContainer.removeAllViews()
            val items = SharedPreferencesManager.getHistory(this)
            for (h in items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(2.dpToPx(), 6.dpToPx(), 2.dpToPx(), 6.dpToPx())
                }

                // 文本区：标题 + URL
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val titleTv = TextView(this).apply {
                    text = h.title.ifEmpty { "无标题" }
                    textSize = 15f
                    setTextColor(0xFF212121.toInt())
                    maxLines = 1
                }
                val urlTv = TextView(this).apply {
                    text = h.url
                    textSize = 12f
                    setTextColor(0xFF9E9E9E.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                textCol.addView(titleTv)
                textCol.addView(urlTv)

                // 删除叉按钮
                val delBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
                    layoutParams = LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx())
                    contentDescription = "删除"
                }
                delBtn.setOnClickListener {
                    SharedPreferencesManager.removeHistory(this, h.url)
                    rebuildList()
                    if (SharedPreferencesManager.getHistory(this).isEmpty()) {
                        dialog?.dismiss()
                    }
                }

                // 点击行 → 打开
                row.setOnClickListener { _ ->
                    navigateTo(h.url)
                    dialog?.dismiss()
                }

                row.addView(textCol)
                row.addView(delBtn)
                listContainer.addView(row)
            }
        }

        rebuildList()

        val scroll = ScrollView(this).apply {
            addView(listContainer)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("历史记录 (${history.size})")
            .setView(scroll)
            .setPositiveButton("清空全部") { _, _ ->
                SharedPreferencesManager.clearHistory(this)
                Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 下载 ----------
    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 生命周期 ----------
    override fun onPause() {
        super.onPause()
        // 保存会话：当前所有标签的 URL
        try {
            val urls = tabManager.all().filter { !it.isIncognito }.map { it.url }
            val current = tabManager.current()?.url ?: ""
            if (urls.any { it.isNotEmpty() }) {
                SharedPreferencesManager.saveSession(this, urls, current)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        tabManager.closeAll()
        super.onDestroy()
    }
}

data class Bookmark(val title: String, val url: String)

object SharedPreferencesManager {
    private const val PREFS_NAME = "skyqi_browser"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"

    fun getBookmarks(context: Context): MutableList<Bookmark> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val obj = arr.getJSONObject(i)
                Bookmark(obj.getString("title"), obj.getString("url"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
        val arr = org.json.JSONArray()
        for (b in bookmarks) {
            arr.put(org.json.JSONObject().put("title", b.title).put("url", b.url))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    fun getHistory(context: Context): List<Bookmark> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val obj = arr.getJSONObject(i)
                Bookmark(obj.getString("title"), obj.getString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addHistory(context: Context, title: String, url: String) {
        val history = getHistory(context).toMutableList()
        history.removeAll { it.url == url }
        history.add(0, Bookmark(title, url))
        if (history.size > 100) history.removeAt(history.size - 1)
        val arr = org.json.JSONArray()
        for (b in history) {
            arr.put(org.json.JSONObject().put("title", b.title).put("url", b.url))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun removeHistory(context: Context, url: String) {
        val history = getHistory(context).toMutableList()
        history.removeAll { it.url == url }
        val arr = org.json.JSONArray()
        for (b in history) {
            arr.put(org.json.JSONObject().put("title", b.title).put("url", b.url))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, "[]").apply()
    }

    // ---------- 会话恢复 ----------
    fun saveSession(context: Context, urls: List<String>, currentUrl: String) {
        val arr = org.json.JSONArray()
        for (u in urls) arr.put(u)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("session_urls", arr.toString())
            .putString("session_current", currentUrl)
            .apply()
    }

    fun loadSession(context: Context): Pair<List<String>, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("session_urls", "[]") ?: "[]"
        val current = prefs.getString("session_current", "") ?: ""
        return try {
            val arr = org.json.JSONArray(json)
            val urls = (0 until arr.length()).map { arr.getString(it) }
            urls to current
        } catch (e: Exception) {
            emptyList<String>() to current
        }
    }
}
