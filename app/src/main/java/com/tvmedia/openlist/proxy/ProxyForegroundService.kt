package com.tvmedia.openlist.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tvmedia.openlist.R
import com.tvmedia.openlist.log.AppLog

/**
 * Keeps the process alive while an external player is in the foreground.
 *
 * During external playback this app sits in the background, where it can be reclaimed — which
 * would kill the loopback proxy mid-stream. A foreground service pins it.
 */
class ProxyForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // `startForeground` 在 Android 12+ 会抛（`ForegroundServiceStartNotAllowedException`），
        // 被系统按 START_STICKY 从后台重建时尤其容易 —— 不接住就是**整个进程崩溃**，
        // 表现成"app 突然退回列表页"。这里接住并停掉自己：代理进程已经没了，
        // 重建一个只有通知的服务也没有意义。
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            START_NOT_STICKY
        } catch (t: Throwable) {
            AppLog.w(TAG, "could not enter the foreground; stopping", t)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(getString(R.string.proxy_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "ProxyForegroundService"
        private const val CHANNEL_ID = "local_proxy"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ProxyForegroundService::class.java),
                )
            }
        }

        /**
         * 撤掉前台保活与它的通知。
         *
         * 用于**回退到内置播放器**的场景：那时本 app 已经在前台，不需要保活，
         * 而通知上写的是"正在为外部播放器转发数据" —— 留着就是一条错的通知。
         * 注意这**不会**停掉预读代理本身（内置播放器仍然要用它）。
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ProxyForegroundService::class.java)) }
        }
    }
}
