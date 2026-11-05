package br.com.scanlimpo.core

import br.com.scanlimpo.core.docx.DocxBlock
import br.com.scanlimpo.core.docx.DocxFormatException
import br.com.scanlimpo.core.docx.DocxReader
import br.com.scanlimpo.core.docx.DocxWriter
import br.com.scanlimpo.core.imaging.AutoCaptureTracker
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.text.TextFiles
import br.com.scanlimpo.core.tiff.TiffFormatException
import br.com.scanlimpo.core.tiff.TiffReader
import br.com.scanlimpo.core.tiff.TiffWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadersTest {
    private val color = -0x1000000 or (120 shl 16) or (200 shl 8) or 40

    private fun tiffBytes(block: (TiffWriter) -> Unit, pages: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = TiffWriter(out, pages)
        block(writer)
        writer.close()
        return out.toByteArray()
    }

    @Test
    fun readsOwnMultipageTiff() {
        val w = 300
        val h = 400
        val random = Random(9)
        val noisy = IntArray(w * h) { -0x1000000 or random.nextInt(0xFFFFFF) }
        val gray = IntArray(w * h) { i -> val v = i % 256; -0x1000000 or (v shl 16) or (v shl 8) or v }
        val bw = IntArray(w * h) { i -> if ((i / w) % 8 < 4) -1 else -0x1000000 }
        val bytes = tiffBytes({
            it.addPage(noisy, w, h, grayscale = false, dpi = 200)
            it.addPage(gray, w, h, grayscale = true, dpi = 200)
            it.addBilevelPage(ImageFilters.packBilevel(bw, w, h), w, h, dpi = 300)
        }, 3)

        val reader = TiffReader(bytes)
        assertEquals(3, reader.pages.size)
        assertArrayEquals("Colorida", noisy, reader.decode(0, 4096).pixels)
        assertArrayEquals("Cinza", gray, reader.decode(1, 4096).pixels)
        assertArrayEquals("Preto e branco", bw, reader.decode(2, 4096).pixels)
    }

    @Test
    fun downsamplesLargePages() {
        val w = 1000
        val h = 500
        val px = IntArray(w * h) { color }
        val decoded = TiffReader(tiffBytes({ it.addPage(px, w, h, false, 72) }, 1)).decode(0, 250)
        assertEquals(250, decoded.width)
        assertEquals(125, decoded.height)
        assertTrue(decoded.pixels.all { it == color })
    }

    /** Arquivos gerados pelo libtiff (via Pillow), para não testar o leitor só contra o próprio gravador. */
    @Test
    fun readsTiffsFromOtherEncoders() {
        val dir = File("src/test/fixtures/tiff")
        val manifest = File(dir, "lista.txt")
        for (line in manifest.readLines().filter { it.isNotBlank() }) {
            val parts = line.split(" ")
            val reader = TiffReader(File(dir, parts[0]).readBytes())
            val expected = File(dir, parts[1]).readBytes()
            val decoded = reader.decode(0, 4096)
            assertEquals(parts[0], parts[2].toInt(), decoded.width)
            assertEquals(parts[0], parts[3].toInt(), decoded.height)
            var i = 0
            for (p in decoded.pixels) {
                assertEquals("${parts[0]} R em $i", expected[i].toInt() and 0xFF, (p shr 16) and 0xFF)
                assertEquals("${parts[0]} G em $i", expected[i + 1].toInt() and 0xFF, (p shr 8) and 0xFF)
                assertEquals("${parts[0]} B em $i", expected[i + 2].toInt() and 0xFF, p and 0xFF)
                i += 3
            }
        }
    }

    /** Entrada hostil: qualquer byte trocado pode dar erro tratado, mas nunca travar ou estourar de outro jeito. */
    @Test
    fun corruptTiffNeverEscapesAsUnexpectedError() {
        val w = 120
        val h = 90
        val random = Random(5)
        val px = IntArray(w * h) { -0x1000000 or random.nextInt(0xFFFFFF) }
        val good = tiffBytes({ it.addPage(px, w, h, false, 72) }, 1)
        repeat(1500) {
            val bad = good.copyOf()
            repeat(1 + random.nextInt(6)) { bad[random.nextInt(minOf(bad.size, 400))] = random.nextInt(256).toByte() }
            try {
                val reader = TiffReader(bad)
                reader.decode(0, 512)
            } catch (e: TiffFormatException) {
                // Esperado.
            }
        }
        assertThrows(TiffFormatException::class.java) { TiffReader(ByteArray(3)) }
        assertThrows(TiffFormatException::class.java) { TiffReader("isto nao e um tiff".toByteArray()) }
    }

    @Test
    fun filterOutputSurvivesTiffRoundTrip() {
        val w = 200
        val h = 300
        val px = IntArray(w * h) { i -> if ((i / w) % 20 < 3 && i % w in 30..170) -0x1000000 else -0x101010 }
        ImageFilters.apply(PageFilter.BW, px, w, h)
        val bytes = tiffBytes({ it.addBilevelPage(ImageFilters.packBilevel(px, w, h), w, h, 300) }, 1)
        assertArrayEquals(px, TiffReader(bytes).decode(0, 4096).pixels)
    }

    @Test
    fun readsOwnDocx() {
        val out = ByteArrayOutputStream()
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        DocxWriter(out, "Título").use { docx ->
            docx.addParagraph("Coração & ação <ok>")
            docx.addJpeg(jpeg, 100, 200)
            docx.pageBreak()
            docx.addParagraph("Segunda página", bold = true)
        }
        val blocks = DocxReader.read(ByteArrayInputStream(out.toByteArray()))
        val texts = blocks.filterIsInstance<DocxBlock.Paragraph>().filter { it.text.isNotEmpty() }
        assertEquals(listOf("Coração & ação <ok>", "Segunda página"), texts.map { it.text })
        assertFalse(texts[0].bold)
        assertTrue(texts[1].bold)
        assertArrayEquals(jpeg, blocks.filterIsInstance<DocxBlock.Picture>().single().bytes)
    }

    private fun docxWith(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("""<?xml version="1.0"?><w:document xmlns:w="x" xmlns:a="y" xmlns:r="z" xmlns:mc="m"><w:body>$body</w:body></w:document>""")
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write("""<Relationships><Relationship Id="rId9" Target="media/foto.png"/></Relationships>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/media/foto.png"))
            zip.write(byteArrayOf(7, 7, 7))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun docxTablesHeadingsAndDuplicatedDrawings() {
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Titulo &amp; subtitulo</w:t></w:r></w:p>
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>Nome</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Valor</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>Caf&#233;</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>R$ 5</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r><mc:AlternateContent>
                <mc:Choice><w:drawing><a:blip r:embed="rId9"/></w:drawing></mc:Choice>
                <mc:Fallback><w:pict><a:blip r:embed="rId9"/></w:pict><w:t>nao aparece</w:t></mc:Fallback>
            </mc:AlternateContent></w:r></w:p>
            <w:p><w:r><w:t>uma</w:t><w:br/><w:t>duas</w:t><w:tab/><w:t>tres</w:t></w:r></w:p>
        """.trimIndent()
        val blocks = DocxReader.read(ByteArrayInputStream(docxWith(body)))
        val paragraphs = blocks.filterIsInstance<DocxBlock.Paragraph>().filter { it.text.isNotEmpty() }
        assertEquals("Titulo & subtitulo", paragraphs[0].text)
        assertTrue(paragraphs[0].heading)
        assertEquals("Nome  |  Valor", paragraphs[1].text)
        assertEquals("Café  |  R$ 5", paragraphs[2].text)
        assertEquals("uma\nduas\ttres", paragraphs[3].text)
        assertEquals("A figura duplicada para Word antigo não pode aparecer duas vezes", 1, blocks.count { it is DocxBlock.Picture })
    }

    @Test
    fun docxRefusesDoctypeAndGarbage() {
        // XXE / billion laughs: DOCTYPE é recusado antes de qualquer expansão.
        val evil = """<!DOCTYPE lolz [<!ENTITY lol "lol">]><w:p><w:r><w:t>&lol;</w:t></w:r></w:p>"""
        assertThrows(DocxFormatException::class.java) { DocxReader.read(ByteArrayInputStream(docxWith(evil))) }
        assertThrows(DocxFormatException::class.java) { DocxReader.read(ByteArrayInputStream("nao e zip".toByteArray())) }
    }

    @Test
    fun textDecodingPicksTheRightEncoding() {
        assertEquals("Ação", TextFiles.decode("Ação".toByteArray(Charsets.UTF_8)))
        assertEquals("Ação", TextFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Ação".toByteArray(Charsets.UTF_8)))
        assertEquals("Ação", TextFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Ação".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Ação", TextFiles.decode("Ação".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun autoCaptureFiresOnlyWhenStill() {
        val tracker = AutoCaptureTracker(holdMillis = 1000)
        val quad = floatArrayOf(.1f, .1f, .9f, .1f, .9f, .9f, .1f, .9f)
        fun shifted(d: Float) = FloatArray(8) { quad[it] + d }

        assertFalse(tracker.onFrame(quad, 0).fire)
        assertEquals(0.5f, tracker.onFrame(quad, 500).progress, 0.01f)
        // Tremor pequeno não zera a contagem, e uma falha isolada de detecção também não.
        assertFalse(tracker.onFrame(shifted(0.01f), 700).fire)
        assertFalse(tracker.onFrame(null, 800).fire)
        assertTrue("Devia disparar parado por 1 s", tracker.onFrame(quad, 1050).fire)

        // Mesma página parada: não fotografa de novo, nem com o tempo.
        for (t in 1100L..6000L step 100) assertFalse(tracker.onFrame(quad, t).fire)

        // Movimento grande rearma, e aí precisa ficar parada de novo.
        assertFalse(tracker.onFrame(shifted(0.2f), 6100).fire)
        assertFalse(tracker.onFrame(shifted(0.2f), 6600).fire)
        assertTrue(tracker.onFrame(shifted(0.2f), 7200).fire)

        // Folha some de vez: a próxima que aparecer na mesma posição pode disparar.
        assertFalse(tracker.onFrame(null, 8000).fire)
        assertFalse(tracker.onFrame(shifted(0.2f), 8100).fire)
        assertTrue(tracker.onFrame(shifted(0.2f), 9200).fire)
    }

    @Test
    fun autoCaptureResetsWhenMoving() {
        val tracker = AutoCaptureTracker(holdMillis = 1000)
        val quad = floatArrayOf(.1f, .1f, .9f, .1f, .9f, .9f, .1f, .9f)
        var t = 0L
        repeat(40) { i ->
            val moving = FloatArray(8) { quad[it] + (i % 2) * 0.05f }
            assertFalse("Celular balançando não pode disparar", tracker.onFrame(moving, t).fire)
            t += 100
        }
        tracker.markCaptured(quad)
        assertFalse(tracker.onFrame(quad, t + 2000).fire)
        tracker.allowSamePageAgain()
        assertFalse(tracker.onFrame(quad, t + 2100).fire)
        assertTrue("Refazer libera a mesma página", tracker.onFrame(quad, t + 3200).fire)
    }
}
