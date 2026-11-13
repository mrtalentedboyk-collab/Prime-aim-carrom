package com.prime.carromaim

import kotlin.math.*

data class Pt(val x: Float, val y: Float)
data class Blob(val x: Float, val y: Float, val d: Float, val color: Int)

data class Seg(
    val sx: Float, val sy: Float,
    val gx: Float, val gy: Float,
    val cx: Float, val cy: Float,
    val px: Float, val py: Float,
    val score: Float,
    val best: Boolean = false,
    val sliderX: Float = -1f,
    val sliderY: Float = -1f,
    val boardLeft: Float = 0f,
    val boardRight: Float = 0f,
    val route: List<Pt> = emptyList(),
    val bankShot: Boolean = false
) {
    fun scaled(k: Float): Seg = copy(
        sx = sx * k, sy = sy * k,
        gx = gx * k, gy = gy * k,
        cx = cx * k, cy = cy * k,
        px = px * k, py = py * k,
        sliderX = if (sliderX < 0f) sliderX else sliderX * k,
        sliderY = if (sliderY < 0f) sliderY else sliderY * k,
        boardLeft = boardLeft * k,
        boardRight = boardRight * k,
        route = route.map { Pt(it.x * k, it.y * k) }
    )
}

class Detector {
    var boardSideFrac = 0.98f
    var coinDFrac = 0.045f
    var strikerDFrac = 0.09f
    var includeBankShots = true

    private fun distance(a: Pt, b: Pt) =
        hypot(a.x - b.x, a.y - b.y)

    private fun distSeg(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy

        val t = if (l2 <= 0f) 0f else
            (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2)
                .coerceIn(0f, 1f)

        return hypot(
            p.x - (a.x + t * dx),
            p.y - (a.y + t * dy)
        )
    }

    private fun isPurple(r: Int, g: Int, b: Int) =
        r > 65 && b > 105 && b > r * 1.18f && g < 150

    private fun isBlack(r: Int, g: Int, b: Int) =
        r < 72 && g < 72 && b < 82

    private fun isTeal(r: Int, g: Int, b: Int) =
        g > 65 && b > 55 &&
        g > r * 1.12f && b > r * 1.02f && g < 235

    fun process(
        a: ByteArray,
        rowStride: Int,
        pix: Int,
        w: Int,
        h: Int
    ): List<Seg> {

        if (w < 10 || h < 10 || pix < 3) return emptyList()

        val side = min(w * boardSideFrac, h * 0.92f)
        val x0 = ((w - side) / 2f).toInt().coerceAtLeast(0)
        val y0 = ((h - side) / 2f).toInt().coerceAtLeast(0)

        val x1 = (x0 + side).toInt().coerceAtMost(w - 1)
        val y1 = (y0 + side).toInt().coerceAtMost(h - 1)

        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1

        if (bw < 20 || bh < 20) return emptyList()

        val slider = findSlider(a, rowStride, pix, w, h, y1)

        val coinD = side * coinDFrac
        val strD = side * strikerDFrac
        val inset = side * 0.07f

        val pocketLeft = x0 + inset
        val pocketRight = x1 - inset
        val top = y0 + inset
        val bottom = y1 - inset

        // Striker movement area
        val left = x0 + side * 0.16f
        val right = x1 - side * 0.16f

        val pockets = listOf(
            Pt(pocketLeft, top),
            Pt(pocketRight, top),
            Pt(pocketLeft, bottom),
            Pt(pocketRight, bottom)
        )

        val classes = ByteArray(bw * bh)

        for (y in 0 until bh) {
            var off = (y + y0) * rowStride + x0 * pix

            for (x in 0 until bw) {
                if (off + 2 < a.size) {
                    val r = a[off].toInt() and 255
                    val g = a[off + 1].toInt() and 255
                    val b = a[off + 2].toInt() and 255

                    classes[y * bw + x] = when {
                        isPurple(r, g, b) -> 1
                        isBlack(r, g, b) -> 2
                        isTeal(r, g, b) -> 3
                        else -> 0
                    }.toByte()
                }
                off += pix
            }
        }

        // Connected-component detection
        val seen = BooleanArray(bw * bh)
        val stack = IntArray(bw * bh)
        val blobs = ArrayList<Blob>()

        for (i in classes.indices) {
            val color = classes[i].toInt()
            if (color == 0 || seen[i]) continue

            var sp = 0
            stack[sp++] = i
            seen[i] = true

            var count = 0
            var sumX = 0L
            var sumY = 0L

            var minX = bw
            var maxX = 0
            var minY = bh
            var maxY = 0

            while (sp > 0) {
                val q = stack[--sp]
                val qx = q % bw
                val qy = q / bw

                count++
                sumX += qx
                sumY += qy

                minX = min(minX, qx)
                maxX = max(maxX, qx)
                minY = min(minY, qy)
                maxY = max(maxY, qy)

                val ns = intArrayOf(
                    if (qx > 0) q - 1 else -1,
                    if (qx < bw - 1) q + 1 else -1,
                    if (qy > 0) q - bw else -1,
                    if (qy < bh - 1) q + bw else -1
                )

                for (n in ns) {
                    if (n >= 0 && !seen[n] &&
                        classes[n].toInt() == color
                    ) {
                        seen[n] = true
                        stack[sp++] = n
                    }
                }
            }

            val ww = maxX - minX + 1
            val hh = maxY - minY + 1
            val d = (ww + hh) / 2f
            val aspect = ww.toFloat() / hh.toFloat()
            val fill = count.toFloat() / (ww * hh).toFloat()

            if (d < coinD * 0.55f ||
                d > strD * 1.5f ||
                aspect < 0.60f ||
                aspect > 1.60f ||
                fill < 0.20f
            ) continue

            val bx = x0 + sumX.toFloat() / count
            val by = y0 + sumY.toFloat() / count

            if (pockets.any {
                distance(it, Pt(bx, by)) < inset * 0.55f
            }) continue

            blobs.add(Blob(bx, by, d, color))
        }

        // Detect striker
        val striker = blobs.filter {
            it.color == 3 &&
            it.d >= coinD &&
            it.y > y0 + bh * 0.65f
        }.maxBy { it.y } ?: return emptyList()

        // Purple = own target coins
        val targets = blobs.filter {
            it.color == 1 &&
            it.d >= coinD * 0.55f &&
            it.d <= coinD * 1.5f
        }

        if (targets.isEmpty()) return emptyList()

        // Black and other purple coins are obstacles
        val obstacles = blobs.filter {
            it.color == 2 ||
            (it.color == 1 &&
             distance(Pt(it.x, it.y), Pt(striker.x, striker.y)) > coinD)
        }

        val strR = striker.d / 2f
        val coinR = coinD / 2f
        val output = ArrayList<Seg>()

        for (coin in targets) {
            val coinPt = Pt(coin.x, coin.y)

            for (pocket in pockets) {
                val routes = ArrayList<Pair<List<Pt>, Boolean>>()

                // Direct pocket route
                routes.add(Pair(listOf(coinPt, pocket), false))

                // One-wall bank-shot routes
                if (includeBankShots) {
                    val virtuals = listOf(
                        Pair(Pt(2f * pocketLeft - pocket.x, pocket.y), 0),
                        Pair(Pt(2f * pocketRight - pocket.x, pocket.y), 1),
                        Pair(Pt(pocket.x, 2f * top - pocket.y), 2),
                        Pair(Pt(pocket.x, 2f * bottom - pocket.y), 3)
                    )

                    for ((v, wall) in virtuals) {
                        val dxv = v.x - coin.x
                        val dyv = v.y - coin.y

                        val t = when (wall) {
                            0 -> if (dxv < -0.001f)
                                (pocketLeft - coin.x) / dxv
                            else Float.POSITIVE_INFINITY

                            1 -> if (dxv > 0.001f)
                                (pocketRight - coin.x) / dxv
                            else Float.POSITIVE_INFINITY

                            2 -> if (dyv < -0.001f)
                                (top - coin.y) / dyv
                            else Float.POSITIVE_INFINITY

                            else -> if (dyv > 0.001f)
                                (bottom - coin.y) / dyv
                            else Float.POSITIVE_INFINITY
                        }

                        if (t > 0f && t < 1f) {
                            val bounce = Pt(
                                coin.x + dxv * t,
                                coin.y + dyv * t
                            )

                            if (distance(coinPt, bounce) > coinR * 2f &&
                                distance(bounce, pocket) > coinR * 2f
                            ) {
                                routes.add(
                                    Pair(listOf(coinPt, bounce, pocket), true)
                                )
                            }
                        }
                    }
                }

                for ((route, bank) in routes) {
                    val first = route[1]
                    val dx = first.x - coin.x
                    val dy = first.y - coin.y
                    val len = hypot(dx, dy)

                    if (len < 1f) continue

                    val ux = dx / len
                    val uy = dy / len

                    // Ghost-ball contact point
                    val gx = coin.x - ux * (strR + coinR)
                    val gy = coin.y - uy * (strR + coinR)
                    val ghost = Pt(gx, gy)

                    val shotLen = distance(
                        Pt(striker.x, striker.y),
                        ghost
                    )

                    if (shotLen < 1f) continue

                    val cos = (
                        (ghost.x - striker.x) * ux +
                        (ghost.y - striker.y) * uy
                    ) / shotLen

                    if (cos < 0.15f) continue

                    var blocked = false

                    for (o in obstacles) {
                        val op = Pt(o.x, o.y)

                        if (distance(op, coinPt) < coinR) continue

                        if (distSeg(
                            op,
                            Pt(striker.x, striker.y),
                            ghost
                        ) < strR + coinR * 0.85f
                        ) {
                            blocked = true
                            break
                        }

                        for (j in 0 until route.size - 1) {
                            if (distSeg(
                                op,
                                route[j],
                                route[j + 1]
                            ) < coinR * 1.75f
                            ) {
                                blocked = true
                                break
                            }
                        }

                        if (blocked) break
                    }

                    if (blocked) continue

                    val routeLen = route.zipWithNext()
                        .map { distance(it.first, it.second) }
                        .sum()

                    val score =
                        shotLen +
                        routeLen * 0.55f +
                        (1f - cos) * shotLen * 1.8f +
                        if (bank) 45f else 0f

                    output.add(
                        Seg(
                            striker.x, striker.y,
                            gx, gy,
                            coin.x, coin.y,
                            pocket.x, pocket.y,
                            score,
                            false,
                            slider.first,
                            slider.second,
                            left,
                            right,
                            route,
                            bank
                        )
                    )
                }
            }
        }

        if (output.isEmpty()) return emptyList()

        val sorted = output.sortedBy { it.score }.take(24)
        val best = sorted[0]

        return sorted.map {
            if (it === best) it.copy(best = true) else it
        }
    }

    // Slider knob detection below the board
    private fun findSlider(
        a: ByteArray,
        rowStride: Int,
        pix: Int,
        w: Int,
        h: Int,
        boardBottom: Int
    ): Pair<Float, Float> {

        val yStart = max(
            boardBottom + 20,
            (h * 0.82f).toInt()
        )
        val yEnd = (h * 0.96f).toInt().coerceAtMost(h - 1)

        if (yStart >= yEnd) return Pair(-1f, -1f)

        var sx = 0L
        var sy = 0L
        var count = 0

        for (y in yStart..yEnd step 2) {
            for (x in (w * 0.12f).toInt()..
                (w * 0.88f).toInt() step 2
            ) {
                val off = y * rowStride + x * pix
                if (off + 2 >= a.size) continue

                val r = a[off].toInt() and 255
                val g = a[off + 1].toInt() and 255
                val b = a[off + 2].toInt() and 255

                if (isTeal(r, g, b)) {
                    sx += x.toLong()
                    sy += y.toLong()
                    count++
                }
            }
        }

        if (count < 12) return Pair(-1f, -1f)

        return Pair(
            sx.toFloat() / count,
            sy.toFloat() / count
        )
    }
}