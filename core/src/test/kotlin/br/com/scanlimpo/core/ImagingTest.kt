package br.com.scanlimpo.core

import br.com.scanlimpo.core.imaging.EdgeDetector
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

class ImagingTest {
    private val outDir = File("build/test-output").apply { mkdirs() }

    /** Folha clara fotografada sobre uma mesa escura, em perspectiva, com "linhas de texto" e ruído de câmera. */
    private fun photo(w: Int, h: Int, corners: IntArray, seed: Long = 7): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(92, 70, 58)
        g.fillRect(0, 0, w, h)
        val xs = intArrayOf(corners[0], corners[2], corners[4], corners[6])
        val ys = intArrayOf(corners[1], corners[3], corners[5], corners[7])
        g.color = Color(238, 236, 228)
        g.fillPolygon(Polygon(xs, ys, 4))
        g.color = Color(40, 40, 50)
        g.stroke = BasicStroke(2f)
        for (t in 1..12) {
            val f = t / 14f
            val lx = corners[0] + (corners[6] - corners[0]) * f + 24
            val ly = corners[1] + (corners[7] - corners[1]) * f
            val rx = corners[2] + (corners[4] - corners[2]) * f - 24
            val ry = corners[3] + (corners[5] - corners[3]) * f
            g.drawLine(lx.toInt(), ly.toInt(), rx.toInt(), ry.toInt())
        }
        g.dispose()
        val random = Random(seed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = Color(img.getRGB(x, y))
                val n = (random.nextGaussian() * 4).toInt()
                img.setRGB(
                    x, y,
                    Color((c.red + n).coerceIn(0, 255), (c.green + n).coerceIn(0, 255), (c.blue + n).coerceIn(0, 255)).rgb,
                )
            }
        }
        return img
    }

    private fun luma(img: BufferedImage): IntArray {
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
    }

    private fun assertCorners(expected: IntArray, found: FloatArray?, w: Int, h: Int) {
        assertNotNull("Documento não detectado", found)
        for (i in 0 until 8) {
            val size = if (i % 2 == 0) w else h
            val actual = found!![i] * size
            assertEquals("Coordenada $i", expected[i].toFloat(), actual, 0.025f * maxOf(w, h))
        }
    }

    @Test
    fun detectsTiltedDocument() {
        val corners = intArrayOf(70, 60, 330, 45, 365, 470, 40, 480)
        val img = photo(400, 530, corners)
        assertCorners(corners, EdgeDetector.detect(luma(img), img.width, img.height), img.width, img.height)
    }

    @Test
    fun detectsStraightDocument() {
        val corners = intArrayOf(50, 50, 350, 50, 350, 480, 50, 480)
        val img = photo(400, 530, corners)
        assertCorners(corners, EdgeDetector.detect(luma(img), img.width, img.height), img.width, img.height)
    }

    @Test
    fun detectsStrongPerspective() {
        val corners = intArrayOf(120, 70, 300, 80, 380, 500, 30, 490)
        val img = photo(400, 530, corners)
        assertCorners(corners, EdgeDetector.detect(luma(img), img.width, img.height), img.width, img.height)
    }

    @Test
    fun returnsNullWithoutDocument() {
        val random = Random(1)
        val w = 300
        val h = 400
        // Só uma mancha suave e ruído: não existe retângulo aqui.
        val gray = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (120 + 40 * Math.sin(x / 40.0) * Math.cos(y / 60.0) + random.nextGaussian() * 3).toInt().coerceIn(0, 255)
        }
        assertNull(EdgeDetector.detect(gray, w, h))
    }

    /** Página com sombra forte em um lado: é o caso que o filtro precisa resolver. */
    private fun shadowedPage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val shade = 0.45f + 0.55f * x / w
                val isText = y % 40 in 10..17 && x in 40 until w - 40
                val base = if (isText) 35 else 240
                val v = (base * shade).toInt()
                px[y * w + x] = (0xFF shl 24) or ((v * 1.0).toInt() shl 16) or ((v * 0.97).toInt() shl 8) or (v * 0.90).toInt()
            }
        }
        return px
    }

    @Test
    fun blackAndWhiteSurvivesHardShadow() {
        val w = 1000
        val h = 1400
        val px = shadowedPage(w, h)
        ImageFilters.apply(PageFilter.BW, px, w, h)
        assertTrue(ImageFilters.isBilevel(px))

        fun isWhite(x: Int, y: Int) = px[y * w + x] and 0xFFFFFF == 0xFFFFFF
        // Papel: branco tanto na sombra quanto na luz.
        assertTrue("Papel na sombra", isWhite(20, 5))
        assertTrue("Papel na luz", isWhite(w - 20, 5))
        // Texto: preto nos dois lados.
        assertTrue("Texto na sombra", !isWhite(60, 53))
        assertTrue("Texto na luz", !isWhite(w - 60, 53))

        val rate = px.count { it and 0xFFFFFF == 0 }.toFloat() / px.size
        assertTrue("Proporção de tinta inesperada: $rate", rate in 0.10f..0.25f)
    }

    @Test
    fun enhanceEvensOutTheShadow() {
        val w = 1000
        val h = 1400
        val before = shadowedPage(w, h)
        val px = before.copyOf()
        ImageFilters.apply(PageFilter.AUTO, px, w, h)
        fun red(values: IntArray, x: Int, y: Int) = (values[y * w + x] shr 16) and 0xFF
        val gapBefore = red(before, w - 20, 5) - red(before, 20, 5)
        val gapAfter = red(px, w - 20, 5) - red(px, 20, 5)
        assertTrue("Sombra deveria sumir: antes=$gapBefore depois=$gapAfter", gapAfter < 25 && gapBefore > 100)
        assertTrue("Papel deveria ficar claro", red(px, 20, 5) > 225)
        assertTrue("Texto deveria seguir escuro", red(px, 60, 53) < 90)
    }

    @Test
    fun blankPageStaysBlank() {
        val random = Random(3)
        val w = 800
        val h = 1000
        val px = IntArray(w * h) {
            val v = (242 + random.nextGaussian() * 2.5).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        ImageFilters.apply(PageFilter.BW, px, w, h)
        val ink = px.count { it and 0xFFFFFF == 0 }
        assertTrue("Página em branco com $ink pixels pretos", ink < w * h / 2000)
    }

    @Test
    fun brightnessMovesBlackAndWhiteThreshold() {
        val w = 1000
        val h = 1400
        val light = softTextPage(w, h).also { ImageFilters.apply(PageFilter.BW, it, w, h, brightness = 1f) }
        val dark = softTextPage(w, h).also { ImageFilters.apply(PageFilter.BW, it, w, h, brightness = -1f) }
        val inkLight = light.count { it and 0xFFFFFF == 0 }
        val inkDark = dark.count { it and 0xFFFFFF == 0 }
        assertTrue("Mais escuro deveria ter mais tinta: $inkDark vs $inkLight", inkDark > inkLight)
    }

    /** O corte ao vivo da tela precisa dar o mesmo que a imagem final, senão a página pula ao soltar a barra. */
    @Test
    fun liveCutMatchesBlackAndWhite() {
        val w = 1000
        val h = 1400
        for (brightness in listOf(-1f, 0f, 1f)) {
            val final = softTextPage(w, h).also { ImageFilters.apply(PageFilter.BW, it, w, h, brightness = brightness) }
            val gray = softTextPage(w, h)
            val threshold = ImageFilters.bilevelThreshold(ImageFilters.bilevelBase(gray, w, h), brightness)
            val different = gray.indices.count { ((gray[it] and 0xFF) >= threshold) != (final[it] and 0xFFFFFF == 0xFFFFFF) }
            assertTrue("$different pixels diferentes com brilho $brightness", different < w * h / 200)
        }
    }

    /** Traço com borda suave, como em foto: mudar o limiar muda a espessura da letra. */
    private fun softTextPage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val distance = kotlin.math.abs(y % 40 - 14)
            for (x in 0 until w) {
                val v = if (x in 40 until w - 40 && distance <= 8) 35 + distance * 25 else 240
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return px
    }

    @Test
    fun writesFilterPreviewsForInspection() {
        val w = 600
        val h = 800
        for (filter in PageFilter.entries) {
            val px = shadowedPage(w, h)
            ImageFilters.apply(filter, px, w, h)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(img, "png", File(outDir, "filtro-${filter.name.lowercase()}.png"))
        }
    }
}
