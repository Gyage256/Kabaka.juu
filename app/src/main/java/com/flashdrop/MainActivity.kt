package com.flashdrop

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var links: TextView
    private lateinit var qr: ImageView
    private lateinit var btn: Button
    private lateinit var help: TextView
    private val h = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
        }
        col.addView(TextView(this).apply { text = "⚡ FlashDrop"; textSize = 30f; setTextColor(Color.parseColor("#0b1220")) })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(10), 0, dp(10)); gravity = Gravity.CENTER }
        col.addView(status)
        btn = Button(this).apply { textSize = 18f; setOnClickListener { onToggle() } }
        col.addView(btn, LinearLayout.LayoutParams(-1, dp(56)))
        qr = ImageView(this)
        col.addView(qr, LinearLayout.LayoutParams(dp(240), dp(240)).apply { topMargin = dp(16) })
        links = TextView(this).apply { textSize = 15f; setTextIsSelectable(true); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) }
        col.addView(links)
        help = TextView(this).apply {
            textSize = 14f
            text = "How to use:\n1. Connect your PC to the same WiFi, or to this phone's hotspot.\n" +
                "2. On the PC open Chrome and type the link above (or scan the QR with another phone).\n" +
                "3. Send and receive files. It uses no mobile data.\n\nTip: hotspot on 5 GHz is fastest."
        }
        col.addView(help)
        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun onToggle() {
        if (ServerState.server != null) {
            startService(Intent(this, ServerService::class.java).setAction("STOP"))
            h.postDelayed({ refresh() }, 400)
            return
        }
        if (!hasStorage()) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 2)
            }
            return
        }
        val i = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        h.postDelayed({ refresh() }, 800)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        h.postDelayed(object : Runnable {
            override fun run() { refresh(); h.postDelayed(this, 2000) }
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        val running = ServerState.server != null
        if (running) {
            val token = getSharedPreferences("fd", MODE_PRIVATE).getString("token", "") ?: ""
            ServerState.refreshUrls(token)
        }
        btn.text = if (running) "Stop sharing" else if (!hasStorage()) "Allow file access" else "Start sharing"
        if (!running) {
            status.text = if (!hasStorage()) "Step 1: tap the button and allow access to all files, then come back and tap Start."
            else "Tap Start sharing"
            links.text = ""; qr.setImageBitmap(null); qr.visibility = View.GONE
            return
        }
        val urls = ServerState.urls
        if (urls.isEmpty()) {
            status.text = "Sharing is on, but no WiFi or hotspot found. Connect to WiFi or turn on hotspot."
            links.text = ""; qr.visibility = View.GONE
            return
        }
        status.text = "Sharing is ON. Open this link on your PC browser:"
        links.text = urls.joinToString("\n\n")
        try {
            val m = QRCodeWriter().encode(urls[0], BarcodeFormat.QR_CODE, 480, 480)
            val bmp = Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565)
            for (x in 0 until 480) for (y in 0 until 480) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
            qr.setImageBitmap(bmp); qr.visibility = View.VISIBLE
        } catch (_: Exception) {
        }
    }
}
