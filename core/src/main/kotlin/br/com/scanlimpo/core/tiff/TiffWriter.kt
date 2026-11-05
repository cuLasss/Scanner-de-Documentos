package br.com.scanlimpo.core.tiff

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream

/**
 * TIFF baseline multipágina com compressão LZW (little-endian).
 * Formato comum em arquivos de escritório, abre no Visualizador do Windows, Acrobat, GIMP etc.
 */
class TiffWriter(private val out: OutputStream, private val pageCount: Int) : Closeable {
    private var position = 0L
    private var pageIndex = 0

    init {
        require(pageCount > 0)
        val header = ByteArray(8)
        header[0] = 'I'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 42
        putInt(header, 4, 8)
        write(header)
    }

    /** Pixels ARGB. Em cinza grava um canal só, o que deixa o arquivo bem menor. */
    fun addPage(pixels: IntArray, width: Int, height: Int, grayscale: Boolean, dpi: Int) {
        val samples = if (grayscale) 1 else 3
        val rowBytes = width * samples
        val strips = ArrayList<ByteArray>()
        var y = 0
        while (y < height) {
            val rows = minOf(ROWS_PER_STRIP, height - y)
            val raw = ByteArray(rows * rowBytes)
            for (row in 0 until rows) {
                val src = (y + row) * width
                val dst = row * rowBytes
                if (grayscale) {
                    var prev = 0
                    for (x in 0 until width) {
                        val p = pixels[src + x]
                        val v = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                        raw[dst + x] = (v - prev).toByte() // Predictor 2.
                        prev = v
                    }
                } else {
                    var pr = 0
                    var pg = 0
                    var pb = 0
                    for (x in 0 until width) {
                        val p = pixels[src + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        raw[dst + x * 3] = (r - pr).toByte()
                        raw[dst + x * 3 + 1] = (g - pg).toByte()
                        raw[dst + x * 3 + 2] = (b - pb).toByte()
                        pr = r
                        pg = g
                        pb = b
                    }
                }
            }
            strips += Lzw.encode(raw)
            y += rows
        }
        writePage(width, height, samples, bits = 8, photometric = if (grayscale) 1 else 2, predictor = 2, dpi, strips)
    }

    /** Linhas de 1 bit alinhadas em byte (bit 1 = branco). */
    fun addBilevelPage(packedRows: ByteArray, width: Int, height: Int, dpi: Int) {
        val rowBytes = (width + 7) / 8
        val strips = ArrayList<ByteArray>()
        var y = 0
        while (y < height) {
            val rows = minOf(ROWS_PER_STRIP, height - y)
            strips += Lzw.encode(packedRows.copyOfRange(y * rowBytes, (y + rows) * rowBytes))
            y += rows
        }
        writePage(width, height, samples = 1, bits = 1, photometric = 1, predictor = 1, dpi, strips)
    }

    private fun writePage(
        width: Int,
        height: Int,
        samples: Int,
        bits: Int,
        photometric: Int,
        predictor: Int,
        dpi: Int,
        strips: List<ByteArray>,
    ) {
        check(pageIndex < pageCount) { "Mais páginas que o informado" }

        // Layout: IFD, valores externos (alinhados), dados das faixas.
        val entryCount = 15
        val ifdSize = 2 + entryCount * 12 + 4
        var cursor = position + ifdSize

        val bitsOffset = cursor
        if (samples == 3) cursor += 6 + 2 // 3 SHORTs, mais alinhamento.
        val xResOffset = cursor
        cursor += 8
        val yResOffset = cursor
        cursor += 8
        val stripOffsetsOffset = cursor
        if (strips.size > 1) cursor += 4L * strips.size
        val stripCountsOffset = cursor
        if (strips.size > 1) cursor += 4L * strips.size

        val offsets = LongArray(strips.size)
        for (i in strips.indices) {
            offsets[i] = cursor
            cursor += strips[i].size
            if (cursor % 2 != 0L) cursor++
        }
        val next = if (pageIndex == pageCount - 1) 0L else cursor

        val head = ByteArrayOutputStream(ifdSize + 64 + strips.size * 8)
        fun short(v: Int) {
            head.write(v and 0xFF)
            head.write((v shr 8) and 0xFF)
        }
        fun int(v: Long) {
            for (i in 0 until 4) head.write(((v shr (8 * i)) and 0xFF).toInt())
        }
        fun entry(tag: Int, type: Int, count: Long, value: Long) {
            short(tag)
            short(type)
            int(count)
            int(value)
        }

        short(entryCount)
        entry(254, LONG, 1, if (pageCount > 1) 2 else 0) // NewSubfileType: página de um documento.
        entry(256, LONG, 1, width.toLong())
        entry(257, LONG, 1, height.toLong())
        entry(258, SHORT, samples.toLong(), if (samples == 3) bitsOffset else bits.toLong())
        entry(259, SHORT, 1, 5) // LZW.
        entry(262, SHORT, 1, photometric.toLong())
        entry(273, LONG, strips.size.toLong(), if (strips.size > 1) stripOffsetsOffset else offsets[0])
        entry(277, SHORT, 1, samples.toLong())
        entry(278, LONG, 1, ROWS_PER_STRIP.toLong())
        entry(279, LONG, strips.size.toLong(), if (strips.size > 1) stripCountsOffset else strips[0].size.toLong())
        entry(282, RATIONAL, 1, xResOffset)
        entry(283, RATIONAL, 1, yResOffset)
        entry(296, SHORT, 1, 2) // Polegadas.
        // PageNumber: dois SHORTs guardados direto no campo de valor.
        entry(297, SHORT, 2, pageIndex.toLong() or (pageCount.toLong() shl 16))
        entry(317, SHORT, 1, predictor.toLong())
        int(next)

        if (samples == 3) {
            repeat(3) { short(bits) }
            short(0)
        }
        int(dpi.toLong())
        int(1)
        int(dpi.toLong())
        int(1)
        if (strips.size > 1) {
            offsets.forEach { int(it) }
            strips.forEach { int(it.size.toLong()) }
        }
        write(head.toByteArray())

        for (strip in strips) {
            write(strip)
            if (position % 2 != 0L) write(ByteArray(1))
        }
        pageIndex++
    }

    override fun close() {
        out.flush()
        out.close()
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = (value shr (8 * i)).toByte()
    }

    private companion object {
        const val SHORT = 3
        const val LONG = 4
        const val RATIONAL = 5
        const val ROWS_PER_STRIP = 128
    }
}

/** Codificador LZW na variante do TIFF (MSB primeiro, com a troca antecipada de tamanho de código). */
internal object Lzw {
    private const val CLEAR = 256
    private const val EOI = 257
    private const val FIRST = 258
    private const val MAX_CODE = 4095
    private const val HASH_SIZE = 9973 * 2 + 1

    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        var bitBuffer = 0
        var bitCount = 0
        var width = 9

        fun emit(code: Int) {
            bitBuffer = (bitBuffer shl width) or code
            bitCount += width
            while (bitCount >= 8) {
                out.write((bitBuffer ushr (bitCount - 8)) and 0xFF)
                bitCount -= 8
            }
            bitBuffer = bitBuffer and ((1 shl bitCount) - 1)
        }

        val keys = IntArray(HASH_SIZE) { -1 }
        val values = IntArray(HASH_SIZE)
        var next = FIRST

        fun slot(key: Int): Int {
            var i = ((key * 31) xor (key ushr 7)).and(Int.MAX_VALUE) % HASH_SIZE
            while (keys[i] != -1 && keys[i] != key) {
                i++
                if (i == HASH_SIZE) i = 0
            }
            return i
        }

        emit(CLEAR)
        if (data.isEmpty()) {
            emit(EOI)
        } else {
            var prefix = data[0].toInt() and 0xFF
            for (i in 1 until data.size) {
                val c = data[i].toInt() and 0xFF
                val key = (prefix shl 8) or c
                val s = slot(key)
                if (keys[s] == key) {
                    prefix = values[s]
                    continue
                }
                emit(prefix)
                keys[s] = key
                values[s] = next
                next++
                prefix = c
                if (next == MAX_CODE - 1) {
                    emit(CLEAR)
                    keys.fill(-1)
                    next = FIRST
                    width = 9
                } else if (next > (1 shl width) - 1) {
                    width++
                }
            }
            emit(prefix)
            next++
            if (next == MAX_CODE - 1) {
                emit(CLEAR)
                width = 9
            } else if (next > (1 shl width) - 1) {
                width++
            }
            emit(EOI)
        }
        if (bitCount > 0) out.write((bitBuffer shl (8 - bitCount)) and 0xFF)
        return out.toByteArray()
    }
}
