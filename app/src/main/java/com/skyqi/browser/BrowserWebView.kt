package com.skyqi.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import android.widget.Toast
import android.net.http.SslError

/**
 * 奇云浏览器核心 WebView
 * 基于系统 WebView（谷歌 Chromium 内核）
 */
class BrowserWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var isIncognito = false
    var isNightMode = false
    var isDesktopMode = false

    private var downloadCallback: ((url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) -> Unit)? = null
    private var titleCallback: ((String) -> Unit)? = null
    private var urlCallback: ((String) -> Unit)? = null
    private var progressCallback: ((Int) -> Unit)? = null
    private var fullscreenCallback: ((Boolean) -> Unit)? = null

    init {
        setup()
    }

    /**
     * 导航首页改为 loadDataWithBaseURL 加载：
     * file:// 直载在部分新版 WebView 中 input 无法聚焦（点不动），
     * 用 baseURL 方式加载可恢复完整交互。
     */
    override fun loadUrl(url: String) {
        if (url == "file:///android_asset/navigation.html") {
            loadNavigationPage()
            return
        }
        super.loadUrl(url)
    }

    private fun loadNavigationPage() {
        try {
            val html = context.assets.open("navigation.html").bufferedReader().use { it.readText() }
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        } catch (e: Exception) {
            super.loadUrl("file:///android_asset/navigation.html")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            // 桌面模式默认关闭，启用标准 UA
            userAgentString = WebSettings.getDefaultUserAgent(context)
        }

        // 导航页与原生共用的搜索引擎存储桥：
        // 页面加载时读取用户上次的选择，用户切换时写回，退出/回主页都不丢
        addJavascriptInterface(object {
            @JavascriptInterface
            fun getSearchEngine(): Int = SearchEngineStore.getIndex(context)

            @JavascriptInterface
            fun setSearchEngine(index: Int) = SearchEngineStore.saveIndex(context, index)
        }, "SkyqiEngine")

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // 拦截非 http/https 协议，避免 ERR_UNKNOWN_URL_SCHEME
                return handleCustomScheme(view, url)
            }

            @SuppressLint("Deprecated")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return false
                return handleCustomScheme(view, u)
            }

            private fun handleCustomScheme(view: WebView?, url: String): Boolean {
                // 正常 http/https 在 WebView 里打开
                if (url.startsWith("http://") || url.startsWith("https://")) return false

                // 其他协议：尝试交给系统处理（打开对应 App），没有则静默忽略
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pm = view?.context?.packageManager
                    if (pm != null && intent.resolveActivity(pm) != null) {
                        view?.context?.startActivity(intent)
                    }
                    true // 吞掉这个协议，不让 WebView 报错
                } catch (e: Exception) {
                    true // 无法处理也静默
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlCallback?.invoke(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                titleCallback?.invoke(title ?: url ?: "")
                applyNightMode()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 仅主框架错误时提示
                if (request?.isForMainFrame == true && error != null) {
                    Toast.makeText(context, R.string.network_error, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 生产环境应提示用户；这里保守：提示但允许继续（用户可手动取消）
                handler?.proceed()
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                titleCallback?.invoke(title ?: "")
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressCallback?.invoke(newProgress)
            }

            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                fullscreenCallback?.invoke(true)
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
                fullscreenCallback?.invoke(false)
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            downloadCallback?.invoke(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // 关键：允许 WebView 获取触摸焦点，否则页面内 input 点不动、弹不出键盘
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    // ---------- 夜间模式：注入 CSS 反色 ----------
    fun applyNightMode() {
        if (isNightMode) {
            val css = """
                if (typeof window.__qiyunNight === 'undefined') {
                    window.__qiyunNight = true;
                    var style = document.createElement('style');
                    style.id = 'qiyun-night-style';
                    style.innerHTML = 'html { filter: invert(90%) hue-rotate(180deg) !important; } img, video, canvas, iframe, [style*="background-image"] { filter: invert(100%) hue-rotate(180deg) !important; }';
                    document.head.appendChild(style);
                }
            """.trimIndent()
            evaluateJavascript(css, null)
        } else {
            evaluateJavascript(
                "var s=document.getElementById('qiyun-night-style'); if(s){s.remove(); window.__qiyunNight=false;}",
                null
            )
        }
    }

    fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        if (isDesktopMode) {
            // 常见的桌面 UA
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        } else {
            settings.userAgentString = WebSettings.getDefaultUserAgent(context)
        }
        reload()
    }

    fun setCallback(
        onDownload: ((String, String, String, String, Long) -> Unit)? = null,
        onTitle: ((String) -> Unit)? = null,
        onUrl: ((String) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null,
        onFullscreen: ((Boolean) -> Unit)? = null
    ) {
        downloadCallback = onDownload
        titleCallback = onTitle
        urlCallback = onUrl
        progressCallback = onProgress
        fullscreenCallback = onFullscreen
    }

    companion object {
        private const val TAG = "QiyunBrowser"
    }
}
