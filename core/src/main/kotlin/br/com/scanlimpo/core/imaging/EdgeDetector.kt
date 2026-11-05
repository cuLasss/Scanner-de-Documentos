package br.com.scanlimpo.core.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acha o quadrilátero de um documento em uma foto: bordas por Sobel, retas por transformada de Hough,
 * e escolhe a combinação de quatro retas com melhor sustentação nas bordas.
 *
 * Usado nas fotos importadas da galeria. As páginas vindas do scanner do Google já chegam recortadas.
 */
object EdgeDetector {
    private const val THETA_STEPS = 180
    private const val MIN_AREA = 0.07f
    private val COS = FloatArray(THETA_STEPS) { cos(it * PI / THETA_STEPS).toFloat() }
    private val SIN = FloatArray(THETA_STEPS) { sin(it * PI / THETA_STEPS).toFloat() }

    private class Line(val theta: Int, val rho: Int, val votes: Int)

    /** Lado maior ideal da imagem de entrada: nessa escala o texto vira borrão e as bordas da folha se destacam. */
    const val IDEAL_SIDE = 320

    /** Recebe luminância (0..255) e devolve TL, TR, BR, BL normalizados, ou null se não houver um documento claro. */
    fun detect(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 48 || h < 48) return null
        val blurred = ImageFilters.boxBlur(ImageFilters.boxBlur(gray, w, h, 2), w, h, 2)

        val gx = IntArray(w * h)
        val gy = IntArray(w * h)
        val mag = IntArray(w * h)
        val hist = IntArray(2048)
        var nonZero = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val dx = blurred[i - w + 1] + 2 * blurred[i + 1] + blurred[i + w + 1] -
                    blurred[i - w - 1] - 2 * blurred[i - 1] - blurred[i + w - 1]
                val dy = blurred[i + w - 1] + 2 * blurred[i + w] + blurred[i + w + 1] -
                    blurred[i - w - 1] - 2 * blurred[i - w] - blurred[i - w + 1]
                val m = (abs(dx) + abs(dy)).coerceAtMost(2047)
                gx[i] = dx
                gy[i] = dy
                mag[i] = m
                if (m > 0) {
                    hist[m]++
                    nonZero++
                }
            }
        }
        if (nonZero == 0) return null

        // Primeiro só com bordas fortes. Se não achar, tenta de novo mais sensível: papel branco sobre
        // mesa clara tem borda fraca, e o texto da folha sozinho já ocupa os gradientes mais fortes.
        return attempt(gx, gy, mag, hist, nonZero, w, h, fraction = 0.12, floor = 40)
            ?: attempt(gx, gy, mag, hist, nonZero, w, h, fraction = 0.40, floor = 12)
    }

    private fun attempt(
        gx: IntArray,
        gy: IntArray,
        mag: IntArray,
        hist: IntArray,
        nonZero: Int,
        w: Int,
        h: Int,
        fraction: Double,
        floor: Int,
    ): FloatArray? {
        var seen = 0
        var threshold = 2047
        while (threshold > 0 && seen < nonZero * fraction) {
            seen += hist[threshold]
            threshold--
        }
        threshold = max(threshold, floor)

        val edge = BooleanArray(w * h)
        var edgeCount = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < threshold) continue
                // Supressão de não-máximos na direção do gradiente, quantizada em 4 direções.
                val ax = abs(gx[i])
                val ay = abs(gy[i])
                val (n1, n2) = when {
                    ax > 2 * ay -> mag[i - 1] to mag[i + 1]
                    ay > 2 * ax -> mag[i - w] to mag[i + w]
                    gx[i] xor gy[i] >= 0 -> mag[i - w - 1] to mag[i + w + 1]
                    else -> mag[i - w + 1] to mag[i + w - 1]
                }
                if (m >= n1 && m >= n2) {
                    edge[i] = true
                    edgeCount++
                }
            }
        }
        if (edgeCount < 40) return null

        val lines = houghLines(edge, gx, gy, w, h)
        val horizontal = candidates(lines.filter { abs(it.theta - 90) < 45 }) { yAt(it, w / 2f) }
        val vertical = candidates(lines.filter { abs(it.theta - 90) >= 45 }) { xAt(it, h / 2f) }
        if (horizontal.size < 2 || vertical.size < 2) return null

        val near = dilate(edge, w, h, 2)
        return bestQuad(horizontal, vertical, near, w, h)
    }

    /**
     * As retas mais fortes somadas às mais externas. Só a força não basta: numa folha cheia de texto,
     * cada linha escrita tem duas bordas e soma mais votos do que a borda da própria folha.
     */
    private fun candidates(lines: List<Line>, position: (Line) -> Float): List<Line> {
        val byPosition = lines.sortedBy(position)
        return (lines.take(10) + byPosition.take(4) + byPosition.takeLast(4)).distinct()
    }

    private fun houghLines(edge: BooleanArray, gx: IntArray, gy: IntArray, w: Int, h: Int): List<Line> {
        val diag = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val rhoCount = 2 * diag + 1
        val acc = IntArray(THETA_STEPS * rhoCount)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!edge[i]) continue
                var angle = atan2(gy[i].toFloat(), gx[i].toFloat())
                if (angle < 0) angle += PI.toFloat()
                val center = (angle * THETA_STEPS / PI.toFloat()).roundToInt()
                for (d in -5..5) {
                    val t = (center + d).mod(THETA_STEPS)
                    val rho = (x * COS[t] + y * SIN[t]).roundToInt() + diag
                    acc[t * rhoCount + rho]++
                }
            }
        }

        val minVotes = max(20, (min(w, h) * 0.16f).toInt())
        val candidates = ArrayList<Line>()
        for (t in 0 until THETA_STEPS) {
            for (r in 1 until rhoCount - 1) {
                val v = acc[t * rhoCount + r]
                if (v < minVotes) continue
                val up = acc[((t + 1) % THETA_STEPS) * rhoCount + r]
                val down = acc[((t - 1 + THETA_STEPS) % THETA_STEPS) * rhoCount + r]
                if (v >= acc[t * rhoCount + r - 1] && v >= acc[t * rhoCount + r + 1] && v >= up && v >= down) {
                    candidates += Line(t, r - diag, v)
                }
            }
        }
        candidates.sortByDescending { it.votes }

        val kept = ArrayList<Line>()
        for (line in candidates) {
            if (kept.size >= 80) break
            val duplicate = kept.any { other ->
                var dt = abs(other.theta - line.theta)
                var dr = abs(other.rho - line.rho)
                if (dt > THETA_STEPS / 2) {
                    // Perto de 0°/180° a mesma reta aparece com o rho invertido.
                    dt = THETA_STEPS - dt
                    dr = abs(other.rho + line.rho)
                }
                dt <= 6 && dr <= 10
            }
            if (!duplicate) kept += line
        }
        return kept
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!src[y * w + x]) continue
                for (yy in max(0, y - radius)..min(h - 1, y + radius)) {
                    for (xx in max(0, x - radius)..min(w - 1, x + radius)) out[yy * w + xx] = true
                }
            }
        }
        return out
    }

    private fun bestQuad(horizontal: List<Line>, vertical: List<Line>, near: BooleanArray, w: Int, h: Int): FloatArray? {
        val hs = horizontal.sortedBy { yAt(it, w / 2f) }
        val vs = vertical.sortedBy { xAt(it, h / 2f) }
        val margin = 0.03f * max(w, h)
        val imageArea = w.toFloat() * h
        var best: FloatArray? = null
        var bestScore = 0f

        for (a in 0 until hs.size - 1) {
            for (b in a + 1 until hs.size) {
                for (c in 0 until vs.size - 1) {
                    for (d in c + 1 until vs.size) {
                        val tl = intersect(vs[c], hs[a]) ?: continue
                        val tr = intersect(vs[d], hs[a]) ?: continue
                        val br = intersect(vs[d], hs[b]) ?: continue
                        val bl = intersect(vs[c], hs[b]) ?: continue
                        val points = floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1])

                        var inside = true
                        for (i in 0 until 4) {
                            val x = points[i * 2]
                            val y = points[i * 2 + 1]
                            if (x < -margin || y < -margin || x > w + margin || y > h + margin) inside = false
                        }
                        if (!inside) continue

                        val area = polygonArea(points) / imageArea
                        if (area < MIN_AREA || area > 0.985f) continue
                        if (!convexWithSaneAngles(points)) continue

                        val s1 = support(points, 0, 1, near, w, h)
                        val s2 = support(points, 1, 2, near, w, h)
                        val s3 = support(points, 2, 3, near, w, h)
                        val s4 = support(points, 3, 0, near, w, h)
                        val weakest = minOf(s1, s2, s3, s4)
                        val mean = (s1 + s2 + s3 + s4) / 4f
                        if (weakest < 0.32f || mean < 0.55f) continue

                        val score = mean * (0.55f + 0.45f * sqrt(area))
                        if (score > bestScore) {
                            bestScore = score
                            best = points
                        }
                    }
                }
            }
        }

        val result = best ?: return null
        return FloatArray(8) { i ->
            val limit = if (i % 2 == 0) w else h
            (result[i] / limit).coerceIn(0f, 1f)
        }
    }

    private fun xAt(line: Line, y: Float): Float {
        val c = COS[line.theta]
        return if (abs(c) < 1e-4f) Float.MAX_VALUE else (line.rho - y * SIN[line.theta]) / c
    }

    private fun yAt(line: Line, x: Float): Float {
        val s = SIN[line.theta]
        return if (abs(s) < 1e-4f) Float.MAX_VALUE else (line.rho - x * COS[line.theta]) / s
    }

    private fun intersect(a: Line, b: Line): FloatArray? {
        val det = COS[a.theta] * SIN[b.theta] - SIN[a.theta] * COS[b.theta]
        if (abs(det) < 1e-3f) return null
        return floatArrayOf(
            (a.rho * SIN[b.theta] - b.rho * SIN[a.theta]) / det,
            (COS[a.theta] * b.rho - COS[b.theta] * a.rho) / det,
        )
    }

    private fun polygonArea(p: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sum += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return abs(sum) / 2f
    }

    private fun convexWithSaneAngles(p: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val prev = (i + 3) % 4
            val next = (i + 1) % 4
            val ax = p[prev * 2] - p[i * 2]
            val ay = p[prev * 2 + 1] - p[i * 2 + 1]
            val bx = p[next * 2] - p[i * 2]
            val by = p[next * 2 + 1] - p[i * 2 + 1]
            val cross = ax * by - ay * bx
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
            val cosine = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by) + 1e-3f)
            val degrees = acos(cosine.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
            if (degrees < 50f || degrees > 130f) return false
        }
        return true
    }

    /** Fração do lado que realmente coincide com uma borda da imagem. */
    private fun support(p: FloatArray, a: Int, b: Int, near: BooleanArray, w: Int, h: Int): Float {
        val ax = p[a * 2]
        val ay = p[a * 2 + 1]
        val bx = p[b * 2]
        val by = p[b * 2 + 1]
        val length = hypot(bx - ax, by - ay)
        val samples = max(8, (length / 2f).toInt())
        var hits = 0
        var total = 0
        for (i in 0..samples) {
            val t = i.toFloat() / samples
            val x = (ax + (bx - ax) * t).roundToInt()
            val y = (ay + (by - ay) * t).roundToInt()
            if (x !in 0 until w || y !in 0 until h) continue
            total++
            if (near[y * w + x]) hits++
        }
        return if (total < samples / 2) 0f else hits.toFloat() / total
    }
}
