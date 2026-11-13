
package com.prime.carromaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

class AutoPlayService : AccessibilityService() {

    companion object {
        @Volatile
        private var active: AutoPlayService? = null

        fun isConnected(): Boolean = active != null

        fun playBestShot(
            seg: Seg,
            screenW: Int,
            screenH: Int
        ): Boolean {
            val service = active ?: return false

            if (Build.VERSION.SDK_INT < 24 || service.busy) {
                return false
            }

            return service.runShot(seg, screenW, screenH)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this

        Toast.makeText(
            this,
            "Auto Play Connected - Full Power",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {}

    override fun onInterrupt() {
        busy = false
    }

    private fun runShot(
        seg: Seg,
        screenW: Int,
        screenH: Int
    ): Boolean {

        if (screenW <= 0 || screenH <= 0) return false
        if (!seg.best) return false

        if (seg.sliderX < 0f || seg.sliderY < 0f) {
            Toast.makeText(
                this,
                "Slider not detected",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val boardLeft = seg.boardLeft
        val boardRight = seg.boardRight

        if (boardRight <= boardLeft) return false

        // Desired shot direction
        val dx = seg.gx - seg.sx
        val dy = seg.gy - seg.sy
        val length = hypot(dx, dy)

        if (length < 5f || abs(dy) < 5f) return false

        val ux = dx / length
        val uy = dy / length

        // Required striker position
        val targetStrikerX =
            seg.gx - ((seg.gy - seg.sy) / uy) * ux

        if (
            targetStrikerX < boardLeft + 20f ||
            targetStrikerX > boardRight - 20f
        ) {
            return false
        }

        // Move slider
        val sliderMinX = screenW * 0.20f
        val sliderMaxX = screenW * 0.80f

        val fraction = (
            (targetStrikerX - boardLeft) /
            (boardRight - boardLeft)
        ).coerceIn(0f, 1f)

        val targetSliderX =
            sliderMinX + fraction *
            (sliderMaxX - sliderMinX)

        val sliderY =
            seg.sliderY.coerceIn(1f, screenH - 2f)

        val sliderStartX =
            seg.sliderX.coerceIn(1f, screenW - 2f)

        val sliderPath = Path().apply {
            moveTo(sliderStartX, sliderY)
            lineTo(targetSliderX, sliderY)
        }

        val sliderGesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        sliderPath,
                        0,
                        350
                    )
                )
                .build()

        busy = true

        val accepted = dispatchGesture(
            sliderGesture,
            object : GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    main.postDelayed({
                        pullAndRelease(
                            seg,
                            targetStrikerX,
                            screenW,
                            screenH
                        )
                    }, 300)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    busy = false
                }
            },
            main
        )

        if (!accepted) busy = false

        return accepted
    }

    private fun pullAndRelease(
        seg: Seg,
        strikerX: Float,
        screenW: Int,
        screenH: Int
    ) {

        if (Build.VERSION.SDK_INT < 24) {
            busy = false
            return
        }

        // Recalculate shot direction
        val dx = seg.gx - strikerX
        val dy = seg.gy - seg.sy

        val length = hypot(dx, dy)

        if (length < 5f) {
            busy = false
            return
        }

        val ux = dx / length
        val uy = dy / length

        // ALWAYS MAXIMUM PULL DISTANCE
        // Near target = Full Power
        // Far target = Full Power
        val power = 175f

        val startX =
            strikerX.coerceIn(2f, screenW - 2f)

        val startY =
            seg.sy.coerceIn(2f, screenH - 2f)

        // Pull opposite to shot direction
        val pullX =
            (startX - ux * power)
                .coerceIn(2f, screenW - 2f)

        val pullY =
            (startY - uy * power)
                .coerceIn(2f, screenH - 2f)

        val actualPull = hypot(
            pullX - startX,
            pullY - startY
        )

        if (actualPull < 25f) {
            busy = false
            return
        }

        val shotPath = Path().apply {
            moveTo(startX, startY)
            lineTo(pullX, pullY)
        }

        val shotGesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        shotPath,
                        0,
                        450
                    )
                )
                .build()

        val accepted = dispatchGesture(
            shotGesture,
            object : GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    main.postDelayed({
                        busy = false
                    }, 2600)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    busy = false
                }
            },
            main
        )

        if (!accepted) busy = false
    }

    override fun onDestroy() {
        busy = false
        main.removeCallbacksAndMessages(null)

        if (active === this) {
            active = null
        }

        super.onDestroy()
    }
}
