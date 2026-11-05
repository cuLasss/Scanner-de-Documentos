package br.com.scanlimpo.core.imaging

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Poucos filtros, e cada um com força de verdade. O nível de aplicação vem de [defaultStrength] para cima ou para baixo. */
enum class PageFilter(val label: String, val defaultStrength: Float) {
    ORIGINAL("Original", 1f),
    AUTO("Melhorar", 0.8f),
    GRAY("Cinza", 0.85f),
    BW("P&B", 1f),
}

/** Filtros de documento, sobre pixels ARGB. Não dependem de Android, então dá para testar no PC. */
object ImageFilters {

    /**
     * @param strength nível do filtro: 0 deixa a imagem como está e 1 aplica com toda a força.
     * O resultado é a mistura direta das duas imagens, o que permite mostrar o nível ao vivo enquanto a barra é arrastada.
     */
    fun apply(
        filter: PageFilter,
        px: IntArray,
        w: Int,
        h: Int,
        strength: Float = 1f,
        brightness: Float = 0f,
        contrast: Float = 0f,
    ) {
        val level = strength.coerceIn(0f, 1f)
        if (filter != PageFilter.ORIGINAL && level > 0f) {
            val base = if (level < 1f) px.copyOf() else null
            when (filter) {
                PageFilter.AUTO -> enhance(px, w, h)
                PageFilter.GRAY -> gray(px, w, h)
                PageFilter.BW -> bilevel(px, w, h, brightness)
                PageFilter.ORIGINAL -> Unit
            }
            if (base != null) mix(px, base, level)
        }
        // No P&B o brilho já deslocou o limiar: mais claro, menos tinta.
        if (filter != PageFilter.BW) adjust(px, brightness, contrast)
    }

    private fun mix(px: IntArray, base: IntArray, level: Float) {
        val a = (level * 256).toInt()
        val b = 256 - a
        for (i in px.indices) {
            val p = px[i]
            val q = base[i]
            val r = (((p shr 16) and 0xFF) * a + ((q shr 16) and 0xFF) * b) shr 8
            val g = (((p shr 8) and 0xFF) * a + ((q shr 8) and 0xFF) * b) shr 8
            val l = ((p and 0xFF) * a + (q and 0xFF) * b) shr 8
            px[i] = OPAQUE or (r shl 16) or (g shl 8) or l
        }
    }

    /** Fator e deslocamento do brilho/contraste, os mesmos usados na prévia em tempo real. */
    fun contrastFactor(contrast: Float): Float = if (contrast >= 0f) 1f + contrast * 1.5f else 1f + contrast * 0.7f
    fun brightnessOffset(brightness: Float): Float = brightness * 80f

    fun adjust(px: IntArray, brightness: Float, contrast: Float) {
        if (brightness == 0f && contrast == 0f) return
        val cf = contrastFactor(contrast)
        val bf = brightnessOffset(brightness)
        val lut = IntArray(256) { v -> clamp(((v - 128) * cf + 128 + bf).toInt()) }
        for (i in px.indices) {
            val p = px[i]
            px[i] = (p and OPAQUE) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    /** True se todos os pixels são preto ou branco puros, o que permite gravar em 1 bit. */
    fun isBilevel(px: IntArray): Boolean {
        for (p in px) {
            val rgb = p and 0xFFFFFF
            if (rgb != 0 && rgb != 0xFFFFFF) return false
        }
        return true
    }

    /** Empacota em 1 bit por pixel (bit 1 = branco), com linhas alinhadas em byte. */
    fun packBilevel(px: IntArray, w: Int, h: Int): ByteArray {
        val rowBytes = (w + 7) / 8
        val out = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            val row = y * w
            val base = y * rowBytes
            for (x in 0 until w) {
                val p = px[row + x]
                val luma = ((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29
                if (luma >= 128 shl 8) {
                    out[base + (x shr 3)] = (out[base + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    private fun enhance(px: IntArray, w: Int, h: Int) {
        flatten(px, w, h)
        val lut = toneCurve(black = 0.12f, white = 0.80f, gamma = 1.7f)
        for (i in px.indices) {
            val p = px[i]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            val l = (r * 77 + g * 150 + b * 29) shr 8
            // Pouca cor é ruído em cima de tinta preta: vira cinza neutro. Cor de verdade, como a de um carimbo, ganha força.
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            val saturation = ((chroma - 22) / 40f).coerceIn(0f, 1f) * 1.5f
            px[i] = OPAQUE or
                (clamp(l + ((r - l) * saturation).toInt()) shl 16) or
                (clamp(l + ((g - l) * saturation).toInt()) shl 8) or
                clamp(l + ((b - l) * saturation).toInt())
        }
    }

    private fun gray(px: IntArray, w: Int, h: Int) {
        flatten(px, w, h)
        val lut = toneCurve(black = 0.14f, white = 0.80f, gamma = 1.9f)
        for (i in px.indices) {
            val p = px[i]
            val v = lut[(((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8]
            px[i] = OPAQUE or (v shl 16) or (v shl 8) or v
        }
    }

    private fun bilevel(px: IntArray, w: Int, h: Int, brightness: Float) {
        val threshold = bilevelThreshold(bilevelBase(px, w, h), brightness)
        for (i in px.indices) {
            px[i] = if ((px[i] and 0xFF) >= threshold) WHITE else BLACK
        }
        despeckle(px, w, h)
    }

    /**
     * Primeira parte do P&B: deixa em [px] a página em cinza, sem sombras, e devolve o limiar sem ajuste.
     * Separada para a tela poder fazer o corte ao vivo, enquanto a barra de "mais tinta / menos tinta" é arrastada.
     */
    fun bilevelBase(px: IntArray, w: Int, h: Int): Int {
        flatten(px, w, h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val p = px[i]
            val v = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            hist[v]++
            px[i] = OPAQUE or (v shl 16) or (v shl 8) or v
        }
        // O limite superior evita que uma página em branco vire chuvisco.
        return otsu(hist, px.size).coerceIn(90, 215)
    }

    /** Limiar do corte para um valor da barra. Quanto mais claro, menos tinta: o limiar desce e menos pixels ficam pretos. */
    fun bilevelThreshold(base: Int, brightness: Float): Int = (base - (brightness * 45f).toInt()).coerceIn(30, 245)

    /** Remove pontinhos pretos isolados, que aumentam o arquivo e sujam o documento. */
    private fun despeckle(px: IntArray, w: Int, h: Int) {
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (px[i] != BLACK) continue
                if (px[i - 1] == WHITE && px[i + 1] == WHITE &&
                    px[i - w] == WHITE && px[i + w] == WHITE &&
                    px[i - w - 1] == WHITE && px[i - w + 1] == WHITE &&
                    px[i + w - 1] == WHITE && px[i + w + 1] == WHITE
                ) {
                    px[i] = WHITE
                }
            }
        }
    }

    /**
     * Divide cada canal pela iluminação estimada do papel: some com sombra e dominante de cor,
     * mantendo a cor da tinta. O fundo vem de uma versão bem reduzida da página, em que um
     * filtro de máximo apaga o texto e um desfoque suaviza o resultado.
     */
    private fun flatten(px: IntArray, w: Int, h: Int) {
        val step = max(1, ceil(max(w, h) / 160.0).toInt())
        val bw = max(1, ceil(w.toDouble() / step).toInt())
        val bh = max(1, ceil(h.toDouble() / step).toInt())
        val channels = Array(3) { IntArray(bw * bh) }
        val counts = IntArray(bw * bh)
        for (y in 0 until h) {
            val by = y / step
            val row = y * w
            for (x in 0 until w) {
                val cell = by * bw + x / step
                val p = px[row + x]
                channels[0][cell] += (p shr 16) and 0xFF
                channels[1][cell] += (p shr 8) and 0xFF
                channels[2][cell] += p and 0xFF
                counts[cell]++
            }
        }
        for (channel in channels) {
            for (i in channel.indices) channel[i] = if (counts[i] > 0) channel[i] / counts[i] else 255
            val dilated = maxFilter(channel, bw, bh, radius = 3)
            val blurred = boxBlur(boxBlur(dilated, bw, bh, 4), bw, bh, 4)
            blurred.copyInto(channel)
            // Limita o ganho, para que fotos e áreas fora do papel não fiquem estouradas.
            val hist = IntArray(256)
            for (v in channel) hist[v.coerceIn(0, 255)]++
            val paper = percentile(hist, channel.size, 0.90f)
            val floor = max(40, (paper * 0.45f).toInt())
            for (i in channel.indices) channel[i] = max(channel[i], floor).coerceAtMost(255)
        }

        val gain = IntArray(256) { v -> if (v == 0) 0 else (255 shl 16) / v }

        // Posição de cada coluna na grade reduzida, em ponto fixo (8 bits de fração).
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val tx = IntArray(w)
        for (x in 0 until w) {
            val fx = (x + 0.5f) / step - 0.5f
            val base = fx.toInt().coerceIn(0, bw - 1)
            x0[x] = base
            x1[x] = min(base + 1, bw - 1)
            tx[x] = (((fx - base).coerceIn(0f, 1f)) * 256).toInt()
        }
        val rowR = IntArray(bw)
        val rowG = IntArray(bw)
        val rowB = IntArray(bw)
        for (y in 0 until h) {
            val fy = (y + 0.5f) / step - 0.5f
            val y0 = fy.toInt().coerceIn(0, bh - 1)
            val y1 = min(y0 + 1, bh - 1)
            val ty = (((fy - y0).coerceIn(0f, 1f)) * 256).toInt()
            for (x in 0 until bw) {
                val a = y0 * bw + x
                val b = y1 * bw + x
                rowR[x] = channels[0][a] * (256 - ty) + channels[0][b] * ty
                rowG[x] = channels[1][a] * (256 - ty) + channels[1][b] * ty
                rowB[x] = channels[2][a] * (256 - ty) + channels[2][b] * ty
            }
            val row = y * w
            for (x in 0 until w) {
                val a = x0[x]
                val b = x1[x]
                val t = tx[x]
                val inv = 256 - t
                val bgR = (rowR[a] * inv + rowR[b] * t) ushr 16
                val bgG = (rowG[a] * inv + rowG[b] * t) ushr 16
                val bgB = (rowB[a] * inv + rowB[b] * t) ushr 16
                val p = px[row + x]
                val r = (((p shr 16) and 0xFF) * gain[bgR.coerceIn(1, 255)]) ushr 16
                val g = (((p shr 8) and 0xFF) * gain[bgG.coerceIn(1, 255)]) ushr 16
                val bl = ((p and 0xFF) * gain[bgB.coerceIn(1, 255)]) ushr 16
                px[row + x] = OPAQUE or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(bl, 255)
            }
        }
    }

    private fun toneCurve(black: Float, white: Float, gamma: Float) = IntArray(256) { v ->
        val t = ((v / 255f - black) / (white - black)).coerceIn(0f, 1f)
        clamp((t.pow(gamma) * 255f + 0.5f).toInt())
    }

    private fun maxFilter(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val horizontal = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var best = 0
                for (k in max(0, x - radius)..min(w - 1, x + radius)) best = max(best, src[row + k])
                horizontal[row + x] = best
            }
        }
        val out = IntArray(src.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var best = 0
                for (k in max(0, y - radius)..min(h - 1, y + radius)) best = max(best, horizontal[k * w + x])
                out[y * w + x] = best
            }
        }
        return out
    }

    internal fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val horizontal = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -radius..radius) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                horizontal[row + x] = sum / (2 * radius + 1)
                sum += src[row + (x + radius + 1).coerceIn(0, w - 1)] - src[row + (x - radius).coerceIn(0, w - 1)]
            }
        }
        val out = IntArray(src.size)
        for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) sum += horizontal[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / (2 * radius + 1)
                sum += horizontal[(y + radius + 1).coerceIn(0, h - 1) * w + x] - horizontal[(y - radius).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * hist[i]
        var sumBack = 0L
        var weightBack = 0L
        var best = 0.0
        var threshold = 128
        for (t in 0 until 256) {
            weightBack += hist[t]
            if (weightBack == 0L) continue
            val weightFore = total - weightBack
            if (weightFore == 0L) break
            sumBack += t.toLong() * hist[t]
            val meanBack = sumBack.toDouble() / weightBack
            val meanFore = (sum - sumBack).toDouble() / weightFore
            val diff = meanBack - meanFore
            val between = weightBack.toDouble() * weightFore * diff * diff
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold + 1
    }

    private fun percentile(hist: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toLong()
        var running = 0L
        for (i in hist.indices) {
            running += hist[i]
            if (running >= target) return i
        }
        return hist.size - 1
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private const val OPAQUE = 0xFF shl 24
    private const val WHITE = -1 // 0xFFFFFFFF
    private const val BLACK = OPAQUE
}
