package br.com.scanlimpo.core.tiff

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.max

class TiffFormatException(message: String) : Exception(message)

/**
 * Leitor de TIFF: uma página por vez, em faixas (strips), sem compressão, LZW, Deflate ou PackBits.
 *
 * O arquivo pode vir de qualquer lugar, então nada aqui confia nos tamanhos declarados:
 * toda leitura é conferida, e dados inválidos viram [TiffFormatException].
 */
class TiffReader(private val data: ByteArray) {
    class Page internal constructor(val width: Int, val height: Int, internal val tags: Map<Int, LongArray>)

    class Decoded(val pixels: IntArray, val width: Int, val height: Int)

    private val littleEndian: Boolean
    val pages: List<Page>

    init {
        if (data.size < 8) throw TiffFormatException("Arquivo curto demais")
        littleEndian = when {
            data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() -> true
            data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() -> false
            else -> throw TiffFormatException("Não é um TIFF")
        }
        if (u16(2) != 42) throw TiffFormatException("Não é um TIFF clássico")
        pages = readPages()
        if (pages.isEmpty()) throw TiffFormatException("TIFF sem páginas")
    }

    /** Decodifica reduzindo por amostragem até o lado maior caber em [maxSide]. */
    fun decode(index: Int, maxSide: Int): Decoded {
        try {
            return decodePage(pages[index], maxSide)
        } catch (e: IndexOutOfBoundsException) {
            throw TiffFormatException("TIFF corrompido: ${e.message}")
        } catch (e: NegativeArraySizeException) {
            throw TiffFormatException("TIFF corrompido: ${e.message}")
        }
    }

    private fun readPages(): List<Page> {
        val result = ArrayList<Page>()
        val visited = HashSet<Long>()
        var offset = u32(4)
        while (offset != 0L && result.size < MAX_PAGES) {
            if (!visited.add(offset)) break // Corrente em círculo: arquivo malformado.
            val start = checkedOffset(offset)
            val count = u16(start)
            val tags = HashMap<Int, LongArray>()
            for (i in 0 until count) {
                val entry = start + 2 + i * 12
                val tag = u16(entry)
                if (tag !in KNOWN_TAGS) continue
                readTag(entry)?.let { tags[tag] = it }
            }
            val width = tags[TAG_WIDTH]?.firstOrNull()?.toInt() ?: 0
            val height = tags[TAG_HEIGHT]?.firstOrNull()?.toInt() ?: 0
            // Páginas "miniatura" (NewSubfileType com bit 0) não são páginas do documento.
            val isThumbnail = (tags[TAG_SUBFILE]?.firstOrNull() ?: 0L) and 1L == 1L
            if (width > 0 && height > 0 && !isThumbnail) result += Page(width, height, tags)
            offset = u32(start + 2 + count * 12)
        }
        return result
    }

    private fun readTag(entry: Int): LongArray? {
        val type = u16(entry + 2)
        val count = u32(entry + 4)
        val size = when (type) {
            TYPE_BYTE -> 1
            TYPE_SHORT -> 2
            TYPE_LONG -> 4
            else -> return null
        }
        if (count <= 0 || count > MAX_TAG_VALUES) return null
        val total = count * size
        val base = if (total <= 4) entry + 8 else checkedOffset(u32(entry + 8))
        if (base + total > data.size) return null
        return LongArray(count.toInt()) { i ->
            when (type) {
                TYPE_BYTE -> (data[base + i].toInt() and 0xFF).toLong()
                TYPE_SHORT -> u16(base + i * 2).toLong()
                else -> u32(base + i * 4)
            }
        }
    }

    private fun decodePage(page: Page, maxSide: Int): Decoded {
        val tags = page.tags
        val w = page.width
        val h = page.height
        if (tags.containsKey(TAG_TILE_WIDTH)) throw TiffFormatException("TIFF em blocos não é suportado")
        if ((tags[TAG_PLANAR]?.firstOrNull() ?: 1L) != 1L) throw TiffFormatException("TIFF em planos separados não é suportado")

        val samples = (tags[TAG_SAMPLES]?.firstOrNull() ?: 1L).toInt()
        val bits = (tags[TAG_BITS]?.firstOrNull() ?: 1L).toInt()
        val compression = (tags[TAG_COMPRESSION]?.firstOrNull() ?: 1L).toInt()
        val photometric = (tags[TAG_PHOTOMETRIC]?.firstOrNull() ?: 1L).toInt()
        val predictor = (tags[TAG_PREDICTOR]?.firstOrNull() ?: 1L).toInt()
        val offsets = tags[TAG_STRIP_OFFSETS] ?: throw TiffFormatException("TIFF sem dados de imagem")
        val counts = tags[TAG_STRIP_COUNTS] ?: throw TiffFormatException("TIFF sem tamanho das faixas")
        val declaredRows = tags[TAG_ROWS_PER_STRIP]?.firstOrNull() ?: h.toLong()
        val rowsPerStrip = declaredRows.coerceIn(1L, h.toLong()).toInt()

        if (samples !in 1..8) throw TiffFormatException("Número de canais inválido: $samples")
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16) throw TiffFormatException("Profundidade não suportada: $bits bits")
        if (photometric !in SUPPORTED_PHOTOMETRIC) throw TiffFormatException("Espaço de cor não suportado: $photometric")
        if (compression !in SUPPORTED_COMPRESSION) throw TiffFormatException("Esse TIFF usa uma compressão não suportada ($compression)")

        val rowBytesLong = (w.toLong() * samples * bits + 7) / 8
        if (rowBytesLong * rowsPerStrip > MAX_STRIP_BYTES) throw TiffFormatException("Faixa grande demais")
        val rowBytes = rowBytesLong.toInt()

        val step = max(1, ceil(max(w, h).toDouble() / maxSide.coerceAtLeast(1)).toInt())
        val outW = (w + step - 1) / step
        val outH = (h + step - 1) / step
        if (outW.toLong() * outH > MAX_OUTPUT_PIXELS) throw TiffFormatException("Imagem grande demais")
        val out = IntArray(outW * outH) { WHITE }

        val palette = tags[TAG_COLOR_MAP]
        val levels = 1 shl minOf(bits, 8)
        if (photometric == PHOTOMETRIC_PALETTE && (palette == null || palette.size < levels * 3)) {
            throw TiffFormatException("TIFF de paleta sem a tabela de cores")
        }

        for (strip in offsets.indices) {
            val y0 = strip * rowsPerStrip
            if (y0 >= h) break
            val rows = minOf(rowsPerStrip, h - y0)
            val raw = decompress(
                compression,
                checkedOffset(offsets[strip]),
                counts.getOrElse(strip) { 0L },
                rows * rowBytes,
            )
            if (predictor == 2) undoPredictor(raw, rowBytes, samples, bits)
            for (row in 0 until rows) {
                val y = y0 + row
                if (y % step != 0) continue
                val base = row * rowBytes
                if (base + rowBytes > raw.size) break // Faixa truncada: o resto fica em branco.
                convertRow(raw, base, w, step, samples, bits, photometric, palette, out, (y / step) * outW)
            }
        }
        return Decoded(out, outW, outH)
    }

    private fun convertRow(
        raw: ByteArray,
        base: Int,
        width: Int,
        step: Int,
        samples: Int,
        bits: Int,
        photometric: Int,
        palette: LongArray?,
        out: IntArray,
        outBase: Int,
    ) {
        var o = outBase
        var x = 0
        while (x < width) {
            when (photometric) {
                PHOTOMETRIC_RGB -> {
                    val r = sample8(raw, base, x * samples, bits)
                    val g = sample8(raw, base, x * samples + 1, bits)
                    val b = sample8(raw, base, x * samples + 2, bits)
                    out[o] = OPAQUE or (r shl 16) or (g shl 8) or b
                }
                PHOTOMETRIC_PALETTE -> {
                    val index = sampleRaw(raw, base, x * samples, bits)
                    val size = palette!!.size / 3
                    val r = (palette[index] shr 8).toInt() and 0xFF
                    val g = (palette[size + index] shr 8).toInt() and 0xFF
                    val b = (palette[size * 2 + index] shr 8).toInt() and 0xFF
                    out[o] = OPAQUE or (r shl 16) or (g shl 8) or b
                }
                PHOTOMETRIC_CMYK -> {
                    val c = sample8(raw, base, x * samples, bits)
                    val m = sample8(raw, base, x * samples + 1, bits)
                    val y = sample8(raw, base, x * samples + 2, bits)
                    val k = sample8(raw, base, x * samples + 3, bits)
                    out[o] = OPAQUE or
                        (((255 - c) * (255 - k) / 255) shl 16) or
                        (((255 - m) * (255 - k) / 255) shl 8) or
                        ((255 - y) * (255 - k) / 255)
                }
                else -> {
                    var v = sample8(raw, base, x * samples, bits)
                    if (photometric == PHOTOMETRIC_WHITE_IS_ZERO) v = 255 - v
                    out[o] = OPAQUE or (v shl 16) or (v shl 8) or v
                }
            }
            o++
            x += step
        }
    }

    /** Valor do canal na escala 0..255. */
    private fun sample8(raw: ByteArray, base: Int, sample: Int, bits: Int): Int = when (bits) {
        8 -> raw[base + sample].toInt() and 0xFF
        16 -> raw[base + sample * 2 + (if (littleEndian) 1 else 0)].toInt() and 0xFF
        else -> sampleRaw(raw, base, sample, bits) * 255 / ((1 shl bits) - 1)
    }

    /** Valor bruto do canal, sem converter de escala. É o índice da paleta. */
    private fun sampleRaw(raw: ByteArray, base: Int, sample: Int, bits: Int): Int = when (bits) {
        8 -> raw[base + sample].toInt() and 0xFF
        16 -> raw[base + sample * 2 + (if (littleEndian) 1 else 0)].toInt() and 0xFF
        else -> {
            val bit = sample * bits
            val byte = raw[base + bit / 8].toInt() and 0xFF
            (byte shr (8 - bits - bit % 8)) and ((1 shl bits) - 1)
        }
    }

    private fun undoPredictor(raw: ByteArray, rowBytes: Int, samples: Int, bits: Int) {
        if (bits != 8) throw TiffFormatException("Predictor só é suportado com 8 bits")
        var base = 0
        while (base + rowBytes <= raw.size) {
            for (i in samples until rowBytes) {
                raw[base + i] = (raw[base + i] + raw[base + i - samples]).toByte()
            }
            base += rowBytes
        }
    }

    private fun decompress(compression: Int, offset: Int, count: Long, expected: Int): ByteArray {
        val available = minOf(count, (data.size - offset).toLong()).toInt().coerceAtLeast(0)
        return when (compression) {
            COMPRESSION_NONE -> data.copyOfRange(offset, offset + minOf(available, expected))
            COMPRESSION_LZW -> lzw(offset, available, expected)
            COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_OLD -> inflate(offset, available, expected)
            COMPRESSION_PACKBITS -> packBits(offset, available, expected)
            else -> throw TiffFormatException("Compressão não suportada: $compression")
        }
    }

    private fun inflate(offset: Int, length: Int, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data, offset, length)
            val out = ByteArray(expected)
            var position = 0
            while (position < expected && !inflater.finished()) {
                val read = inflater.inflate(out, position, expected - position)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                position += read
            }
            return if (position == expected) out else out.copyOf(position)
        } catch (e: DataFormatException) {
            throw TiffFormatException("Dados Deflate inválidos: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun packBits(offset: Int, length: Int, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var i = offset
        val end = offset + length
        while (i < end && out.size() < expected) {
            val n = data[i++].toInt()
            when {
                n >= 0 -> {
                    val count = minOf(n + 1, end - i)
                    out.write(data, i, count)
                    i += count
                }
                n != -128 && i < end -> {
                    val value = data[i++].toInt()
                    repeat(1 - n) { out.write(value) }
                }
            }
        }
        return out.toByteArray()
    }

    /** LZW na variante do TIFF: MSB primeiro, com a troca antecipada de tamanho de código. */
    private fun lzw(offset: Int, length: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var position = 0
        val prefix = IntArray(LZW_TABLE)
        val suffix = ByteArray(LZW_TABLE)
        val sizes = IntArray(LZW_TABLE)
        for (i in 0 until 256) {
            suffix[i] = i.toByte()
            sizes[i] = 1
            prefix[i] = -1
        }

        fun firstByte(code: Int): Byte {
            var c = code
            while (prefix[c] >= 0) c = prefix[c]
            return suffix[c]
        }

        fun emit(code: Int) {
            val size = sizes[code]
            var c = code
            var i = position + size - 1
            while (c >= 0) {
                if (i < expected) out[i] = suffix[c]
                i--
                c = prefix[c]
            }
            position += size
        }

        var next = 258
        var width = 9
        var previous = -1
        var bitBuffer = 0L
        var bitCount = 0
        var input = offset
        val end = offset + length

        while (position < expected) {
            while (bitCount < width && input < end) {
                bitBuffer = (bitBuffer shl 8) or (data[input++].toLong() and 0xFF)
                bitCount += 8
            }
            if (bitCount < width) break
            bitCount -= width
            val code = ((bitBuffer shr bitCount) and ((1L shl width) - 1)).toInt()
            bitBuffer = bitBuffer and ((1L shl bitCount) - 1)

            if (code == LZW_EOI) break
            if (code == LZW_CLEAR) {
                next = 258
                width = 9
                previous = -1
                continue
            }
            if (previous == -1) {
                if (code > 255) throw TiffFormatException("LZW inválido")
                emit(code)
                previous = code
                continue
            }
            if (next >= LZW_TABLE) throw TiffFormatException("LZW inválido: tabela cheia")
            when {
                code < next -> {
                    emit(code)
                    prefix[next] = previous
                    suffix[next] = firstByte(code)
                }
                code == next -> {
                    prefix[next] = previous
                    suffix[next] = firstByte(previous)
                    sizes[next] = sizes[previous] + 1
                    emit(code)
                }
                else -> throw TiffFormatException("LZW inválido: código fora da tabela")
            }
            sizes[next] = sizes[previous] + 1
            next++
            if (next == 511 || next == 1023 || next == 2047) width++
            previous = code
        }
        return if (position >= expected) out else out.copyOf(position)
    }

    private fun checkedOffset(offset: Long): Int {
        if (offset < 0 || offset >= data.size) throw TiffFormatException("Posição fora do arquivo")
        return offset.toInt()
    }

    private fun u16(at: Int): Int {
        if (at < 0 || at + 2 > data.size) throw TiffFormatException("Leitura fora do arquivo")
        val a = data[at].toInt() and 0xFF
        val b = data[at + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    private fun u32(at: Int): Long {
        val a = u16(at).toLong()
        val b = u16(at + 2).toLong()
        return if (littleEndian) a or (b shl 16) else (a shl 16) or b
    }

    private companion object {
        const val MAX_PAGES = 5000
        const val MAX_TAG_VALUES = 2_000_000L
        const val MAX_STRIP_BYTES = 512L * 1024 * 1024
        const val MAX_OUTPUT_PIXELS = 80_000_000L

        const val OPAQUE = 0xFF shl 24
        const val WHITE = -1

        const val TYPE_BYTE = 1
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4

        const val TAG_SUBFILE = 254
        const val TAG_WIDTH = 256
        const val TAG_HEIGHT = 257
        const val TAG_BITS = 258
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC = 262
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_SAMPLES = 277
        const val TAG_ROWS_PER_STRIP = 278
        const val TAG_STRIP_COUNTS = 279
        const val TAG_PLANAR = 284
        const val TAG_PREDICTOR = 317
        const val TAG_COLOR_MAP = 320
        const val TAG_TILE_WIDTH = 322
        val KNOWN_TAGS = setOf(
            TAG_SUBFILE, TAG_WIDTH, TAG_HEIGHT, TAG_BITS, TAG_COMPRESSION, TAG_PHOTOMETRIC, TAG_STRIP_OFFSETS,
            TAG_SAMPLES, TAG_ROWS_PER_STRIP, TAG_STRIP_COUNTS, TAG_PLANAR, TAG_PREDICTOR, TAG_COLOR_MAP, TAG_TILE_WIDTH,
        )

        const val PHOTOMETRIC_WHITE_IS_ZERO = 0
        const val PHOTOMETRIC_BLACK_IS_ZERO = 1
        const val PHOTOMETRIC_RGB = 2
        const val PHOTOMETRIC_PALETTE = 3
        const val PHOTOMETRIC_CMYK = 5
        val SUPPORTED_PHOTOMETRIC = setOf(
            PHOTOMETRIC_WHITE_IS_ZERO, PHOTOMETRIC_BLACK_IS_ZERO, PHOTOMETRIC_RGB, PHOTOMETRIC_PALETTE, PHOTOMETRIC_CMYK,
        )

        const val COMPRESSION_NONE = 1
        const val COMPRESSION_LZW = 5
        const val COMPRESSION_DEFLATE = 8
        const val COMPRESSION_PACKBITS = 32773
        const val COMPRESSION_DEFLATE_OLD = 32946
        val SUPPORTED_COMPRESSION = setOf(
            COMPRESSION_NONE, COMPRESSION_LZW, COMPRESSION_DEFLATE, COMPRESSION_PACKBITS, COMPRESSION_DEFLATE_OLD,
        )

        const val LZW_CLEAR = 256
        const val LZW_EOI = 257
        const val LZW_TABLE = 4096
    }
}
