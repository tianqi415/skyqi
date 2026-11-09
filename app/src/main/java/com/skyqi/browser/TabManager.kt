package com.skyqi.browser

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 多标签页管理器
 */
class TabManager(private val context: Context, private val container: FrameLayout) {

    data class Tab(
        val id: Int,
        val webView: BrowserWebView,
        var title: String = "新标签页",
        var url: String = "",
        var isIncognito: Boolean = false
    )

    private val tabs = ArrayList<Tab>()
    private var currentTabId = -1
    private var nextId = 1

    val size: Int get() = tabs.size

    fun newTab(url: String = "", incognito: Boolean = false): Tab {
        val webView = BrowserWebView(context)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.isIncognito = incognito

        val tab = Tab(nextId++, webView, if (incognito) "无痕" else "新标签页", url, incognito)
        tabs.add(tab)
        container.addView(webView)
        switchTo(tab.id)
        // 创建后立即加载目标页（空 URL 则显示空白）
        if (url.isNotEmpty()) {
            webView.loadUrl(url)
        }
        return tab
    }

    fun switchTo(id: Int): Tab? {
        val tab = tabs.find { it.id == id } ?: return null
        currentTabId = id
        for (t in tabs) {
            t.webView.visibility = if (t.id == id) android.view.View.VISIBLE else android.view.View.GONE
        }
        return tab
    }

    fun closeTab(id: Int): Boolean {
        val tab = tabs.find { it.id == id } ?: return false
        val index = tabs.indexOf(tab)

        // 销毁 WebView 释放内存
        container.removeView(tab.webView)
        tab.webView.destroy()
        tabs.remove(tab)

        if (tabs.isEmpty()) {
            currentTabId = -1
            // 无标签时自动新建一个
            newTab()
            return true
        }

        // 切换到相邻标签
        if (currentTabId == id) {
            val newIndex = if (index >= tabs.size) tabs.size - 1 else index
            switchTo(tabs[newIndex].id)
        }
        return true
    }

    fun current(): Tab? = tabs.find { it.id == currentTabId }

    fun all(): List<Tab> = tabs.toList()

    fun closeAll() {
        for (t in tabs) {
            container.removeView(t.webView)
            t.webView.destroy()
        }
        tabs.clear()
        currentTabId = -1
    }

    /** 是否有至少一个无痕标签 */
    fun hasIncognito(): Boolean = tabs.any { it.isIncognito }
}
