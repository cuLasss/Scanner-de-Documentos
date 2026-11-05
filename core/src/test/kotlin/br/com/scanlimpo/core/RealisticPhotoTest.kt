package br.com.scanlimpo.core

import br.com.scanlimpo.core.imaging.EdgeDetector
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.pdf.PdfImage
import br.com.scanlimpo.core.pdf.PdfWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Roda sobre uma foto simulada, para olhar o resultado dos filtros em texto de verdade.
 */
class RealisticPhotoTest {
    private val fixtures = File("src/test/fixtures")
    private val outDir = File("build/test-output").apply { mkdirs() }

    private fun pixels(img: BufferedImage) = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    @Test
    fun detectsCornersInRealisticPhoto() {
        val file = File(fixtures, "foto.jpg")
        val photo = ImageIO.read(file)

        // Mesma redução usada no app: lado maior com 640 px.
        val scale = 640.0 / maxOf(photo.width, photo.height)
        val w = (photo.width * scale).toInt()
        val h = (photo.height * scale).toInt()
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(photo, 0, 0, w, h, null)
            dispose()
        }
        val px = pixels(small)
        val gray = IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        val found = EdgeDetector.detect(gray, w, h)
        checkNotNull(found) { "Documento não detectado na foto realista" }

        val expected = listOf(250 to 190, 1270 to 150, 1400 to 1830, 110 to 1860)
        expected.forEachIndexed { i, (x, y) ->
            assertEquals("X do canto $i", x.toFloat(), found[i * 2] * photo.width, 40f)
            assertEquals("Y do canto $i", y.toFloat(), found[i * 2 + 1] * photo.height, 40f)
        }
    }

    @Test
    fun writesFiltersOfRealisticPage() {
        val file = File(fixtures, "recortada.jpg")
        val img = ImageIO.read(file)
        val w = img.width
        val h = img.height
        for (filter in PageFilter.entries) {
            val px = pixels(img)
            val start = System.nanoTime()
            ImageFilters.apply(filter, px, w, h)
            val millis = (System.nanoTime() - start) / 1_000_000
            println("Filtro ${filter.name}: $millis ms")
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(out, "png", File(outDir, "real-${filter.name.lowercase()}.png"))
        }

        // Mesmo conteúdo em PDF: compara o P&B de 1 bit com o JPEG colorido.
        val bw = pixels(img)
        ImageFilters.apply(PageFilter.BW, bw, w, h)
        File(outDir, "real-pb.pdf").outputStream().use { stream ->
            PdfWriter(stream).use { pdf ->
                pdf.addFittedPage(595.28f, 841.89f, PdfImage.Bilevel(w, h, ImageFilters.packBilevel(bw, w, h)))
            }
        }
        File(outDir, "real-colorido.pdf").outputStream().use { stream ->
            PdfWriter(stream).use { pdf -> pdf.addFittedPage(595.28f, 841.89f, PdfImage.Jpeg(file.readBytes())) }
        }
    }
}
