package br.com.scanlimpo.core

import br.com.scanlimpo.core.imaging.EdgeDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * Mede o detector em cenas sintéticas difíceis, com cantos conhecidos, na resolução ideal e em duas
 * vizinhas, porque a câmera nem sempre entrega o tamanho exato.
 */
class DetectorScenesTest {
    private val dir = File("src/test/fixtures/cenas")

    private fun gray(image: BufferedImage, longest: Int): Triple<IntArray, Int, Int> {
        val scale = longest.toDouble() / max(image.width, image.height)
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        val px = small.getRGB(0, 0, w, h, null, 0, w)
        return Triple(
            IntArray(px.size) { i ->
                val p = px[i]
                (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            },
            w,
            h,
        )
    }

    @Test
    fun scenesAreDetected() {
        val manifest = File(dir, "cenas.txt")
        val failures = ArrayList<String>()
        var total = 0
        for (longest in listOf(EdgeDetector.IDEAL_SIDE, 240, 400)) {
            var hits = 0
            var millis = 0L
            val lines = manifest.readLines().filter { it.isNotBlank() }
            for (line in lines) {
                val parts = line.split(" ")
                val name = parts[0]
                val expected = FloatArray(8) { parts[it + 1].toFloat() }
                val (pixels, w, h) = gray(ImageIO.read(File(dir, "$name.jpg")), longest)
                val start = System.nanoTime()
                val found = EdgeDetector.detect(pixels, w, h)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                millis += elapsed
                total++
                val worst = found?.let { f -> (0 until 8).maxOf { abs(f[it] - expected[it]) } }
                val ok = worst != null && worst <= TOLERANCE
                if (ok) hits++ else failures += "$longest px: $name"
                println(
                    "%4d px  %-20s %-9s erro=%s  %d ms".format(
                        longest,
                        name,
                        if (ok) "OK" else if (found == null) "NADA" else "ERRADO",
                        worst?.let { "%.1f%%".format(it * 100) } ?: "-",
                        elapsed,
                    ),
                )
            }
            println("== $longest px: $hits de ${lines.size} certas, média ${millis / lines.size} ms")
        }
        println("Falhas: $failures")
        // Meta mínima: errar pouco é tolerável, porque a pessoa sempre pode ajustar os cantos depois da foto.
        assertTrue("Acertou menos do que o mínimo: $failures", failures.size <= total / 4)
    }

    private companion object {
        const val TOLERANCE = 0.03f
    }
}
