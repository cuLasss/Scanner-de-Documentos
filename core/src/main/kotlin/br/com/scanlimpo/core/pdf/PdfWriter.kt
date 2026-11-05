package br.com.scanlimpo.core.pdf

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

sealed class PdfImage {
    abstract val width: Int
    abstract val height: Int

    /** Bytes que vão de fato para o arquivo. */
    abstract val payloadSize: Int

    /** JPEG embutido sem recompressão (DCTDecode). */
    class Jpeg(val bytes: ByteArray) : PdfImage() {
        val components: Int
        override val width: Int
        override val height: Int

        init {
            val info = parseSof(bytes)
            width = info[0]
            height = info[1]
            components = info[2]
        }

        override val payloadSize get() = bytes.size

        private fun parseSof(data: ByteArray): IntArray {
            require(data.size > 4 && u(data, 0) == 0xFF && u(data, 1) == 0xD8) { "Não é um JPEG" }
            var i = 2
            while (i + 9 < data.size) {
                if (u(data, i) != 0xFF) {
                    i++
                    continue
                }
                val marker = u(data, i + 1)
                when (marker) {
                    0xFF -> {
                        i++
                        continue
                    }
                    0xD8, 0x01, in 0xD0..0xD7 -> {
                        i += 2
                        continue
                    }
                }
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    val height = (u(data, i + 5) shl 8) or u(data, i + 6)
                    val width = (u(data, i + 7) shl 8) or u(data, i + 8)
                    return intArrayOf(width, height, u(data, i + 9))
                }
                i += 2 + ((u(data, i + 2) shl 8) or u(data, i + 3))
            }
            error("JPEG sem marcador SOF")
        }

        private fun u(data: ByteArray, index: Int) = data[index].toInt() and 0xFF
    }

    /**
     * Imagem de 1 bit, com linhas alinhadas em byte (bit 1 = branco).
     * Fica nítida e muito menor que um JPEG equivalente.
     */
    class Bilevel(override val width: Int, override val height: Int, packedRows: ByteArray) : PdfImage() {
        val deflated: ByteArray = deflateWithUpPredictor(width, height, packedRows)
        override val payloadSize get() = deflated.size

        private fun deflateWithUpPredictor(w: Int, h: Int, packed: ByteArray): ByteArray {
            val rowBytes = (w + 7) / 8
            require(packed.size >= rowBytes * h) { "Dados insuficientes para a imagem" }
            val buffer = ByteArrayOutputStream(packed.size / 8 + 64)
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                DeflaterOutputStream(buffer, deflater).use { stream ->
                    val row = ByteArray(rowBytes + 1)
                    row[0] = 2 // Filtro PNG "Up".
                    for (y in 0 until h) {
                        val start = y * rowBytes
                        for (i in 0 until rowBytes) {
                            val above = if (y == 0) 0 else packed[start - rowBytes + i].toInt()
                            row[i + 1] = (packed[start + i].toInt() - above).toByte()
                        }
                        stream.write(row)
                    }
                }
            } finally {
                deflater.end()
            }
            return buffer.toByteArray()
        }
    }
}

/** Palavra do OCR com posição normalizada (0..1) em relação à imagem, com origem no canto superior esquerdo. */
class PdfWord(val text: String, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Gravador de PDF em fluxo contínuo: cada página vai para o disco assim que é adicionada,
 * então o consumo de memória não cresce com o tamanho do documento.
 */
class PdfWriter(
    output: OutputStream,
    password: String? = null,
    private val title: String? = null,
) : Closeable {
    private val out = CountingStream(output)
    private val encryption = password?.takeIf { it.isNotEmpty() }?.let { PdfEncryption(it) }
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextObject = 1
    private val catalogId = allocate()
    private val pagesId = allocate()
    private val fontId = allocate()
    private var finished = false

    init {
        ascii("%PDF-1.7\n")
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
    }

    /** Adiciona uma página com a imagem ajustada e centralizada, sem distorcer. */
    fun addFittedPage(pageWidth: Float, pageHeight: Float, image: PdfImage, words: List<PdfWord> = emptyList()) {
        val scale = minOf(pageWidth / image.width, pageHeight / image.height)
        val w = image.width * scale
        val h = image.height * scale
        addPage(pageWidth, pageHeight, image, (pageWidth - w) / 2f, (pageHeight - h) / 2f, w, h, words)
    }

    fun addPage(
        pageWidth: Float,
        pageHeight: Float,
        image: PdfImage,
        x: Float,
        y: Float,
        drawWidth: Float,
        drawHeight: Float,
        words: List<PdfWord> = emptyList(),
    ) {
        check(!finished) { "PDF já finalizado" }

        val imageId = allocate()
        writeImage(imageId, image)

        val contentId = allocate()
        val content = buildContent(x, y, drawWidth, drawHeight, words)
        writeStream(contentId, "/Filter /FlateDecode", deflate(content))

        val pageId = allocate()
        pageIds += pageId
        writeObject(
            pageId,
            "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] " +
                "/Resources << /XObject << /Im0 $imageId 0 R >> /Font << /F1 $fontId 0 R >> >> " +
                "/Contents $contentId 0 R >>",
        )
    }

    fun finish() {
        if (finished) return
        finished = true
        writeObject(fontId, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val kids = pageIds.joinToString(" ") { "$it 0 R" }
        writeObject(pagesId, "<< /Type /Pages /Kids [$kids] /Count ${pageIds.size} >>")
        val extensions = if (encryption != null) " /Extensions << /ADBE << /BaseVersion /1.7 /ExtensionLevel 8 >> >>" else ""
        writeObject(catalogId, "<< /Type /Catalog /Pages $pagesId 0 R$extensions >>")

        val infoId = allocate()
        val date = SimpleDateFormat("'D:'yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val info = StringBuilder("<< /Producer ${textString("Scan Limpo")} /CreationDate ${textString(date, ascii = true)}")
        if (!title.isNullOrBlank()) info.append(" /Title ").append(textString(title))
        info.append(" >>")
        writeObject(infoId, info.toString())

        val encryptId = encryption?.let { enc -> allocate().also { writeObject(it, enc.dictionary) } }

        val startXref = out.count
        val size = nextObject
        val xref = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) {
            val offset = offsets[id] ?: error("Objeto $id não foi gravado")
            xref.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        ascii(xref.toString())

        val fileId = java.security.MessageDigest.getInstance("MD5")
            .digest("$date|$size|$startXref|${System.nanoTime()}".toByteArray())
            .toHex()
        val encryptRef = encryptId?.let { " /Encrypt $it 0 R" }.orEmpty()
        ascii("trailer\n<< /Size $size /Root $catalogId 0 R /Info $infoId 0 R$encryptRef /ID [<$fileId> <$fileId>] >>\n")
        ascii("startxref\n$startXref\n%%EOF\n")
        out.flush()
    }

    override fun close() {
        finish()
        out.close()
    }

    private fun buildContent(x: Float, y: Float, w: Float, h: Float, words: List<PdfWord>): ByteArray {
        val cs = StringBuilder(64 + words.size * 48)
        cs.append("q ").append(n(w)).append(" 0 0 ").append(n(h)).append(' ')
            .append(n(x)).append(' ').append(n(y)).append(" cm /Im0 Do Q\n")

        if (words.isNotEmpty()) {
            // Modo 3: texto invisível, mas selecionável e pesquisável.
            cs.append("BT\n3 Tr\n/F1 1 Tf\n")
            for (word in words) {
                val encoded = WinAnsi.encode(word.text)
                if (encoded.isEmpty() || word.width <= 0f || word.height <= 0f) continue
                val natural = WinAnsi.width(encoded)
                if (natural <= 0f) continue
                val boxW = word.width * w
                val boxH = word.height * h
                val px = x + word.x * w
                val py = y + h - (word.y + word.height) * h + boxH * 0.2f
                cs.append(n(boxW / natural)).append(" 0 0 ").append(n(boxH)).append(' ')
                    .append(n(px)).append(' ').append(n(py)).append(" Tm <")
                    .append((encoded + SPACE).toHex()).append("> Tj\n")
            }
            cs.append("ET\n")
        }
        return cs.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun writeImage(id: Int, image: PdfImage) {
        when (image) {
            is PdfImage.Jpeg -> {
                val colorSpace = when (image.components) {
                    1 -> "/DeviceGray"
                    4 -> "/DeviceCMYK"
                    else -> "/DeviceRGB"
                }
                writeStream(
                    id,
                    "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode",
                    image.bytes,
                )
            }
            is PdfImage.Bilevel -> writeStream(
                id,
                "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode " +
                    "/DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 1 /Columns ${image.width} >>",
                image.deflated,
            )
        }
    }

    private fun allocate() = nextObject++

    private fun writeObject(id: Int, body: String) {
        offsets[id] = out.count
        ascii("$id 0 obj\n$body\nendobj\n")
    }

    private fun writeStream(id: Int, dict: String, data: ByteArray) {
        val payload = encryption?.encrypt(data) ?: data
        offsets[id] = out.count
        ascii("$id 0 obj\n<< $dict /Length ${payload.size} >>\nstream\n")
        out.write(payload)
        ascii("\nendstream\nendobj\n")
    }

    private fun textString(text: String, ascii: Boolean = false): String {
        val bytes = if (ascii) {
            text.toByteArray(Charsets.ISO_8859_1)
        } else {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        }
        val payload = encryption?.encrypt(bytes) ?: bytes
        return "<${payload.toHex()}>"
    }

    private fun deflate(data: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream(data.size / 2 + 32)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(buffer, deflater).use { it.write(data) }
        } finally {
            deflater.end()
        }
        return buffer.toByteArray()
    }

    private fun ascii(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

    private fun n(value: Float): String = String.format(Locale.ROOT, "%.3f", value)

    private class CountingStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    private companion object {
        val SPACE = byteArrayOf(0x20)
    }
}
