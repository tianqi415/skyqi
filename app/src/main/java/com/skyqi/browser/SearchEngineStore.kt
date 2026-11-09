package com.skyqi.browser

import android.content.Context
import java.io.File

/**
 * 搜索引擎固定存储。
 *
 * 单独一个文件（filesDir/search_engine.idx）记录用户选择的引擎，
 * 直到用户主动修改为止——退出浏览器、回主页、重启进程都不会丢。
 *
 * 原生菜单与导航页（navigation.html）共用这一份数据，保证两边始终一致。
 */
object SearchEngineStore {

    data class Engine(
        val name: String,
        val badge: String,
        val color: String,
        val url: String
    )

    /** 与 navigation.html 中的 engines 列表保持同样的顺序与地址 */
    val ENGINES = listOf(
        Engine("百度", "度", "#4E8EF7", "https://www.baidu.com/s?wd="),
        Engine("必应", "B", "#0A66C2", "https://www.bing.com/search?q="),
        Engine("Google", "G", "#4285F4", "https://www.google.com/search?q="),
        Engine("搜狗", "狗", "#FF7700", "https://www.sogou.com/web?query=")
    )

    private fun file(context: Context): File = File(context.filesDir, "search_engine.idx")

    /** 读取当前引擎下标，非法值回落到 0（百度） */
    fun getIndex(context: Context): Int {
        return try {
            val v = file(context).readText().trim().toIntOrNull() ?: 0
            if (v in ENGINES.indices) v else 0
        } catch (e: Exception) {
            0
        }
    }

    /** 写入当前引擎下标，非法值回落到 0（百度） */
    fun saveIndex(context: Context, index: Int) {
        try {
            val i = if (index in ENGINES.indices) index else 0
            file(context).writeText(i.toString())
        } catch (e: Exception) {
            // 存储失败时保持内存值，不崩溃
        }
    }

    fun getEngine(context: Context): Engine = ENGINES[getIndex(context)]
}
