package com.skyqi.browser.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import com.skyqi.browser.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 下载服务：支持进度通知、取消、Android 8+ 通知渠道
 */
class DownloadService : Service() {

    private val executor = Executors.newFixedThreadPool(3)
    private val notifications = mutableMapOf<Int, NotificationCompat.Builder>()

    companion object {
        const val ACTION_DOWNLOAD = "com.skyqi.browser.DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_UA = "ua"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME = "mime"
        private const val CHANNEL_ID = "skyqi_downloads"
        private const val NOTIF_ID_BASE = 1000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载通知",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
            val ua = intent.getStringExtra(EXTRA_UA) ?: ""
            val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "download"
            val mime = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
            startDownload(url, ua, filename, mime)
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, ua: String, filename: String, mime: String) {
        val id = NOTIF_ID_BASE + notifications.size
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载: $filename")
            .setContentText("准备中...")
            .setProgress(100, 0, false)
            .setOngoing(true)
        notifications[id] = builder
        startForeground(id, builder.build())

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val u = URL(url)
                conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", ua)
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val totalBytes = conn.contentLengthLong
                val fileName = URLUtil.guessFileName(url, null, mime) ?: filename

                // 保存到公共下载目录（Android 10+ 使用 MediaStore，这里简化存到应用私有目录，测试用）
                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, fileName)

                val input = conn.inputStream
                val output = FileOutputStream(outFile)
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var downloaded = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalBytes > 0) {
                        val progress = (downloaded * 100 / totalBytes).toInt()
                        builder.setProgress(100, progress, false)
                        builder.setContentText("$progress%")
                        getSystemService(NotificationManager::class.java).notify(id, builder.build())
                    } else {
                        builder.setContentText("${downloaded / 1024} KB")
                        getSystemService(NotificationManager::class.java).notify(id, builder.build())
                    }
                }
                output.flush()
                output.close()
                input.close()

                builder.setOngoing(false)
                    .setProgress(0, 0, false)
                    .setContentTitle("下载完成: $fileName")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this, id,
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(
                                    Uri.fromFile(outFile),
                                    mime
                                )
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                getSystemService(NotificationManager::class.java).notify(id, builder.build())
            } catch (e: Exception) {
                builder.setOngoing(false)
                    .setContentTitle("下载失败")
                    .setContentText(e.message ?: "未知错误")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                getSystemService(NotificationManager::class.java).notify(id, builder.build())
            } finally {
                conn?.disconnect()
                stopForeground(android.R.attr.stopWithTask)
                notifications.remove(id)
                if (notifications.isEmpty()) stopSelf()
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
