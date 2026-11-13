package com.prime.carromaim

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 1
    private var askedNotif = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val btn = Button(this)
        btn.text = "START"
        btn.textSize = 24f
        btn.setOnClickListener { start() }
        setContentView(btn)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            Toast.makeText(this, "Overlay permission dao, tarpor abar START chapo",
                Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == reqCapture && res == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}