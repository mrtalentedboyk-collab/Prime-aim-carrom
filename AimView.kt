package com.prime.carromaim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class AimView(c: Context) : View(c) {

    @Volatile
    var segs: List<Seg> = emptyList()

    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 255, 65)
        strokeWidth = 7f
        style = Paint.Style.STROKE
    }

    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 220, 245)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val snapshot = segs

        for (s in snapshot) {
            val p = if (s.best) bestPaint else otherPaint

            // Striker to ghost-ball contact point
            canvas.drawLine(s.sx, s.sy, s.gx, s.gy, p)
            canvas.drawCircle(s.gx, s.gy, 13f, ghostPaint)

            // Coin route to pocket, including bank point
            if (s.route.size >= 2) {
                val path = Path().apply {
                    moveTo(s.route[0].x, s.route[0].y)

                    for (i in 1 until s.route.size) {
                        lineTo(s.route[i].x, s.route[i].y)
                    }
                }

                canvas.drawPath(path, p)

                for (i in 1 until s.route.size - 1) {
                    canvas.drawCircle(
                        s.route[i].x,
                        s.route[i].y,
                        9f,
                        ghostPaint
                    )
                }
            } else {
                canvas.drawLine(s.cx, s.cy, s.px, s.py, p)
            }

            canvas.drawCircle(s.cx, s.cy, 10f, ghostPaint)
            canvas.drawCircle(s.px, s.py, 15f, pocketPaint)

            if (s.best) {
                canvas.drawText(
                    if (s.bankShot) "BEST BANK SHOT" else "BEST SHOT",
                    s.sx + 12f,
                    s.sy - 20f,
                    textPaint
                )
            }
        }
    }
}