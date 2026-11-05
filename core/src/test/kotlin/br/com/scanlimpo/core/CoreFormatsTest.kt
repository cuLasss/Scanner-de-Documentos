package br.com.scanlimpo.core

import br.com.scanlimpo.core.docx.DocxWriter
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.pdf.PdfImage
import br.com.scanlimpo.core.pdf.PdfWord
import br.com.scanlimpo.core.pdf.PdfWriter
import br.com.scanlimpo.core.tiff.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Gera arquivos reais em build/test-output. Além das verificações aqui, eles são conferidos com
 * ferramentas independentes (poppler, Pillow, python-docx): ver scripts em docs/validacao.
 */
class CoreFormatsTest {
    private val outDir = File("build/test-output").apply { mkdirs() }

    private fun sampleImage(w: Int = 600, h: Int = 800, tone: Color = Color(245, 243, 238)): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = tone
        g.fillRect(0, 0, w, h)
        g.color = Color(30, 30, 90)
        for (y in 80 until h - 60 step 40) g.fillRect(50, y, w - 100, 14)
        g.dispose()
        return img
    }

    private fun jpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        return out.toByteArray()
    }

    private fun pixels(img: BufferedImage): IntArray = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    private val words = listOf(
        PdfWord("Declaração", 0.08f, 0.10f, 0.30f, 0.03f),
        PdfWord("de", 0.40f, 0.10f, 0.05f, 0.03f),
        PdfWord("São", 0.47f, 0.10f, 0.08f, 0.03f),
        PdfWord("Paulo", 0.57f, 0.10f, 0.14f, 0.03f),
        PdfWord("—", 0.72f, 0.10f, 0.03f, 0.03f),
        PdfWord("ação", 0.76f, 0.10f, 0.12f, 0.03f),
    )

    @Test
    fun jpegHeaderIsParsed() {
        val image = PdfImage.Jpeg(jpeg(sampleImage(321, 123)))
        assertEquals(321, image.width)
        assertEquals(123, image.height)
        assertEquals(3, image.components)
    }

    @Test
    fun writesPlainSearchablePdf() {
        val file = File(outDir, "pesquisavel.pdf")
        PdfWriter(file.outputStream(), title = "Declaração de teste").use { pdf ->
            pdf.addFittedPage(595.28f, 841.89f, PdfImage.Jpeg(jpeg(sampleImage())), words)
            pdf.addFittedPage(595.28f, 841.89f, PdfImage.Jpeg(jpeg(sampleImage(800, 600))))
        }
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.7"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
    }

    @Test
    fun writesEncryptedPdf() {
        val file = File(outDir, "com-senha.pdf")
        PdfWriter(file.outputStream(), password = "cão-123", title = "Documento sigiloso").use { pdf ->
            pdf.addFittedPage(595.28f, 841.89f, PdfImage.Jpeg(jpeg(sampleImage())), words)
        }
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/R 6"))
        assertTrue(text.contains("/Encrypt"))
        assertTrue("O título não pode ficar visível no arquivo cifrado", !text.contains("sigiloso"))
    }

    @Test
    fun writesBilevelPdf() {
        val img = sampleImage(1240, 1754)
        val px = pixels(img)
        ImageFilters.apply(br.com.scanlimpo.core.imaging.PageFilter.BW, px, img.width, img.height)
        assertTrue(ImageFilters.isBilevel(px))
        val packed = ImageFilters.packBilevel(px, img.width, img.height)
        val file = File(outDir, "pb.pdf")
        PdfWriter(file.outputStream()).use { pdf ->
            pdf.addFittedPage(595.28f, 841.89f, PdfImage.Bilevel(img.width, img.height, packed))
        }
        assertTrue("P&B de 1 bit deveria ser bem pequeno: ${file.length()}", file.length() < 40_000)
    }

    @Test
    fun tiffRoundTripsThroughImageIo() {
        val color = sampleImage(400, 300, Color(250, 240, 220))
        val gray = sampleImage(300, 400, Color(235, 235, 235))
        val bw = sampleImage(500, 700)
        val bwPx = pixels(bw)
        ImageFilters.apply(br.com.scanlimpo.core.imaging.PageFilter.BW, bwPx, bw.width, bw.height)

        val file = File(outDir, "multipagina.tif")
        TiffWriter(file.outputStream(), 3).use { tiff ->
            tiff.addPage(pixels(color), color.width, color.height, grayscale = false, dpi = 200)
            tiff.addPage(pixels(gray), gray.width, gray.height, grayscale = true, dpi = 200)
            tiff.addBilevelPage(ImageFilters.packBilevel(bwPx, bw.width, bw.height), bw.width, bw.height, dpi = 300)
        }

        val reader = ImageIO.getImageReadersByFormatName("tiff").next()
        ImageIO.createImageInputStream(file).use { input ->
            reader.input = input
            assertEquals(3, reader.getNumImages(true))

            val first = reader.read(0)
            assertEquals(400, first.width)
            assertEquals(Color(250, 240, 220).rgb, first.getRGB(5, 5))
            assertEquals(Color(30, 30, 90).rgb, first.getRGB(200, 85))

            val second = reader.read(1)
            assertEquals(300, second.width)
            // Valor bruto do canal: o getRGB aplicaria correção de gama em imagens cinza.
            assertTrue(second.raster.getSample(5, 5, 0) > 220)
            assertTrue(second.raster.getSample(150, 85, 0) < 60)

            val third = reader.read(2)
            assertEquals(700, third.height)
            assertEquals(Color.WHITE.rgb, third.getRGB(5, 5))
            assertEquals(Color.BLACK.rgb, third.getRGB(250, 85))
        }
        reader.dispose()
    }

    @Test
    fun lzwHandlesIncompressibleDataPastTableReset() {
        // Ruído força várias trocas de tamanho de código e o reinício da tabela.
        val random = java.util.Random(42)
        val w = 700
        val h = 700
        val px = IntArray(w * h) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val file = File(outDir, "ruido.tif")
        TiffWriter(file.outputStream(), 1).use { it.addPage(px, w, h, grayscale = false, dpi = 72) }
        val decoded = ImageIO.read(file)
        for (i in 0 until 5000) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            assertEquals(px[y * w + x], decoded.getRGB(x, y))
        }
    }

    @Test
    fun docxIsWellFormed() {
        val file = File(outDir, "documento.docx")
        DocxWriter(file.outputStream(), "Contrato & <teste>").use { docx ->
            val data = jpeg(sampleImage())
            docx.addJpeg(data, 600, 800)
            docx.pageBreak()
            docx.addParagraph("Página com acentuação: ação, coração & <tags>\u0001 sem quebrar")
            docx.addParagraph("Segundo parágrafo")
            docx.pageBreak()
            docx.addJpeg(data, 600, 800)
        }
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        for (required in listOf(
            "[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels",
            "word/styles.xml", "docProps/core.xml", "docProps/app.xml", "word/media/image1.jpeg", "word/media/image2.jpeg",
        )) {
            assertTrue("Falta $required", entries.containsKey(required))
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, data) in entries) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
            }
        }
        val document = entries.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(document.contains("coração &amp; &lt;tags&gt;"))
        assertTrue(!document.contains("\u0001"))
    }
}
