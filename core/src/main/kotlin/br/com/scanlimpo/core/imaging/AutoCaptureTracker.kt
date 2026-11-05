package br.com.scanlimpo.core.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Decide quando tirar a foto sozinho: o documento precisa ficar parado por um tempo.
 *
 * Depois de uma foto, só dispara de novo quando a cena muda (a folha saiu do lugar ou sumiu),
 * para não fotografar a mesma página sem parar.
 */
class AutoCaptureTracker(
    private val holdMillis: Long = 1000,
    private val moveTolerance: Float = 0.02f,
    private val rearmDistance: Float = 0.08f,
    private val graceMillis: Long = 400,
) {
    class Status(val progress: Float, val fire: Boolean)

    private var anchor: FloatArray? = null
    private var anchorTime = 0L
    private var lastSeen = 0L
    private var captured: FloatArray? = null

    /** Recebe o quadrilátero do quadro atual (ou null) e o horário dele. */
    fun onFrame(quad: FloatArray?, now: Long): Status {
        if (quad == null) {
            // Uma falha isolada de detecção não pode zerar a contagem. Só o sumiço de verdade conta.
            if (now - lastSeen > graceMillis) {
                anchor = null
                captured = null
            }
            return Status(progressAt(now), false)
        }
        lastSeen = now

        val done = captured
        if (done != null) {
            if (distance(quad, done) <= rearmDistance) return Status(0f, false)
            captured = null
        }

        val current = anchor
        if (current == null || distance(quad, current) > moveTolerance) {
            anchor = quad.copyOf()
            anchorTime = now
            return Status(0f, false)
        }

        val progress = progressAt(now)
        if (progress >= 1f) {
            markCaptured(quad)
            return Status(1f, true)
        }
        return Status(progress, false)
    }

    /** A foto saiu, automática ou manual: esta página já foi. */
    fun markCaptured(quad: FloatArray?) {
        captured = quad?.copyOf()
        anchor = null
    }

    /** A pessoa pediu para refazer: a mesma página volta a poder disparar. */
    fun allowSamePageAgain() {
        captured = null
        anchor = null
    }

    private fun progressAt(now: Long): Float =
        if (anchor == null) 0f else ((now - anchorTime).toFloat() / holdMillis).coerceIn(0f, 1f)

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (i in a.indices) worst = max(worst, abs(a[i] - b[i]))
        return worst
    }
}
