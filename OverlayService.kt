package com.prime.carromaim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private lateinit var aimView: AimView
    private lateinit var logo: TextView
    private lateinit var panel: LinearLayout
    private lateinit var logoParams: WindowManager.LayoutParams

    private val detector = Detector()
    private val ui = Handler(Looper.getMainLooper())

    private val detectThread =
        HandlerThread("prime-aim-detection").apply { start() }

    private val worker = Handler(detectThread.looper)

    private var aimOn = false

    @Volatile
    private var autoPlayOn = false

    private var lastAutoShot = 0L

    private val captureScale = 0.5f
    private var captureW = 0
    private var captureH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForegroundNotification()

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        val resultCode = intent?.getIntExtra("code", 0) ?: 0

        if (data != null && projection == null) {
            val manager =
                getSystemService(MediaProjectionManager::class.java)

            projection = manager.getMediaProjection(resultCode, data)

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                ui
            )

            setupCaptureAndOverlay()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                "prime_aim",
                "Prime Aim",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(this, "prime_aim")
            .setContentTitle("Prime Carrom Aim is running")
            .setContentText("Screen analysis and overlay are active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeLayoutParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        fullScreen: Boolean = false
    ): WindowManager.LayoutParams {

        var flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (fullScreen) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setupCaptureAndOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        captureW = (metrics.widthPixels * captureScale).toInt()
        captureH = (metrics.heightPixels * captureScale).toInt()

        reader = ImageReader.newInstance(
            captureW,
            captureH,
            PixelFormat.RGBA_8888,
            3
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "PrimeCarromAim",
            captureW,
            captureH,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            worker
        )

        aimView = AimView(this)

        wm.addView(
            aimView,
            makeLayoutParams(-1, -1, false, true)
        )

        createFloatingControls()
    }

    private fun createFloatingControls() {

        logo = TextView(this).apply {
            text = "M"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(21, 101, 192))
            }
        }

        logoParams = makeLayoutParams(
            dp(56),
            dp(56),
            true
        ).apply {
            x = dp(8)
            y = dp(200)
        }

        logo.setOnTouchListener(
            object : View.OnTouchListener {

                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false

                override fun onTouch(
                    view: View,
                    event: MotionEvent
                ): Boolean {

                    when (event.action) {

                        MotionEvent.ACTION_DOWN -> {
                            startX = logoParams.x
                            startY = logoParams.y
                            touchX = event.rawX
                            touchY = event.rawY
                            moved = false
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()

                            if (abs(dx) > 8 || abs(dy) > 8) {
                                moved = true
                            }

                            if (moved) {
                                logoParams.x = startX + dx
                                logoParams.y = startY + dy

                                try {
                                    wm.updateViewLayout(logo, logoParams)
                                } catch (_: Exception) {
                                }
                            }
                        }

                        MotionEvent.ACTION_UP -> {
                            if (!moved) togglePanel()
                        }
                    }

                    return true
                }
            }
        )

        wm.addView(logo, logoParams)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(230, 30, 30, 30))
        }

        val aimSwitch = Switch(this).apply {
            text = "Aim"
            setTextColor(Color.WHITE)

            setOnCheckedChangeListener { _, checked ->
                setAimEnabled(checked)
            }
        }

        panel.addView(aimSwitch)

        val autoSwitch = Switch(this).apply {
            text = "Auto Play"
            setTextColor(Color.WHITE)

            setOnCheckedChangeListener { _, checked ->

                if (checked && !AutoPlayService.isConnected()) {
                    autoPlayOn = false

                    Toast.makeText(
                        this@OverlayService,
                        "Accessibility permission enable kore abar Auto Play ON korun",
                        Toast.LENGTH_LONG
                    ).show()

                    try {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }

                    isChecked = false

                } else {
                    autoPlayOn = checked

                    if (checked && !aimOn) {
                        aimSwitch.isChecked = true
                    }
                }
            }
        }

        panel.addView(autoSwitch)

        val closeButton = Button(this).apply {
            text = "Close app"
            setOnClickListener {
                stopSelf()
            }
        }

        panel.addView(closeButton)
    }

    private fun togglePanel() {
        if (!::panel.isInitialized) return

        if (panel.parent != null) {
            try {
                wm.removeView(panel)
            } catch (_: Exception) {
            }
        } else {
            val params = makeLayoutParams(-2, -2, true).apply {
                x = logoParams.x + dp(64)
                y = logoParams.y
            }

            wm.addView(panel, params)
        }
    }

    private fun setAimEnabled(enabled: Boolean) {
        aimOn = enabled

        if (enabled) {
            worker.removeCallbacks(detectionLoop)
            worker.post(detectionLoop)
        } else {
            worker.removeCallbacks(detectionLoop)
            aimView.segs = emptyList()
            aimView.postInvalidate()
        }
    }

    private val detectionLoop = object : Runnable {

        override fun run() {
            if (!aimOn) return

            val image = try {
                reader?.acquireLatestImage()
            } catch (_: Exception) {
                null
            }

            if (image != null) {
                try {
                    val plane = image.planes[0]

                    val bytes = ByteArray(
                        plane.buffer.remaining()
                    )

                    plane.buffer.get(bytes)

                    val raw = detector.process(
                        bytes,
                        plane.rowStride,
                        plane.pixelStride,
                        captureW,
                        captureH
                    )

                    val screenSegments = raw.map {
                        it.scaled(1f / captureScale)
                    }

                    aimView.segs = screenSegments
                    aimView.postInvalidate()

                    val now = SystemClock.elapsedRealtime()

                    val best = screenSegments.firstOrNull {
                        it.best &&
                        it.sliderX >= 0f &&
                        it.sliderY >= 0f
                    }

                    if (
                        autoPlayOn &&
                        AutoPlayService.isConnected() &&
                        best != null &&
                        now - lastAutoShot >= 3500L
                    ) {
                        val metrics = DisplayMetrics()

                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealMetrics(metrics)

                        if (
                            AutoPlayService.playBestShot(
                                best,
                                metrics.widthPixels,
                                metrics.heightPixels
                            )
                        ) {
                            lastAutoShot = now
                        }
                    }

                } catch (_: Exception) {
                    // A dropped frame will not stop the detection loop.
                } finally {
                    image.close()
                }
            }

            worker.postDelayed(this, 150L)
        }
    }

    override fun onDestroy() {
        aimOn = false
        autoPlayOn = false

        worker.removeCallbacksAndMessages(null)

        try {
            if (::panel.isInitialized && panel.parent != null) {
                wm.removeView(panel)
            }
        } catch (_: Exception) {
        }

        try {
            if (::logo.isInitialized) wm.removeView(logo)
        } catch (_: Exception) {
        }

        try {
            if (::aimView.isInitialized) wm.removeView(aimView)
        } catch (_: Exception) {
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        detectThread.quitSafely()
        super.onDestroy()
    }
}