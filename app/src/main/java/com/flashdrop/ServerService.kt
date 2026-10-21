package com.flashdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.SecureRandom

class ServerService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("fd", "FlashDrop", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, "fd")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("FlashDrop is sharing")
            .setContentText("Tap to see the link. No mobile data used.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (ServerState.server == null) {
            val prefs = getSharedPreferences("fd", MODE_PRIVATE)
            var token = prefs.getString("token", null)
            if (token == null) {
                val chars = "abcdefghjkmnpqrstuvwxyz23456789"
                val r = SecureRandom()
                token = (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
                prefs.edit().putString("token", token).apply()
            }
            val root: File = Environment.getExternalStorageDirectory()
            File(root, "FlashDrop").mkdirs()
            try {
                val srv = FlashServer(this, root, token, ServerState.PORT)
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                ServerState.server = srv
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
            ServerState.refreshUrls(token)

            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flashdrop").also { it.acquire() }
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flashdrop:srv").also { it.acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ServerState.server?.stop()
        ServerState.server = null
        ServerState.urls = emptyList()
        try { wifi?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
