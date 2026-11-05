package br.com.scanlimpo.core.pdf

import java.text.Normalizer

/** Conversão de texto para WinAnsiEncoding e larguras da Helvetica, usadas na camada de texto do PDF. */
internal object WinAnsi {
    private val extras = mapOf(
        0x20AC to 0x80, 0x201A to 0x82, 0x0192 to 0x83, 0x201E to 0x84, 0x2026 to 0x85, 0x2020 to 0x86,
        0x2021 to 0x87, 0x02C6 to 0x88, 0x2030 to 0x89, 0x0160 to 0x8A, 0x2039 to 0x8B, 0x0152 to 0x8C,
        0x017D to 0x8E, 0x2018 to 0x91, 0x2019 to 0x92, 0x201C to 0x93, 0x201D to 0x94, 0x2022 to 0x95,
        0x2013 to 0x96, 0x2014 to 0x97, 0x02DC to 0x98, 0x2122 to 0x99, 0x0161 to 0x9A, 0x203A to 0x9B,
        0x0153 to 0x9C, 0x017E to 0x9E, 0x0178 to 0x9F,
    )

    // Larguras AFM da Helvetica para os códigos 32..126.
    private val ascii = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    // Larguras para os códigos 0xC0..0xFF (letras acentuadas).
    private val latin = intArrayOf(
        667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,
    )

    fun encode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val code = mapCodePoint(cp) ?: fallback(cp)
            code?.let { out.add(it.toByte()) }
        }
        return out.toByteArray()
    }

    private fun mapCodePoint(cp: Int): Int? = when {
        cp in 0x20..0x7E || cp in 0xA0..0xFF -> cp
        else -> extras[cp]
    }

    /** Para caracteres fora da tabela, tenta a letra-base (ex.: "ő" vira "o") e por fim "?". */
    private fun fallback(cp: Int): Int? {
        if (Character.isWhitespace(cp)) return 0x20
        if (Character.isISOControl(cp)) return null
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        return decomposed.firstOrNull()?.code?.let(::mapCodePoint) ?: '?'.code
    }

    /** Largura natural do texto codificado, para fonte de tamanho 1. */
    fun width(encoded: ByteArray): Float {
        var total = 0
        for (b in encoded) {
            val c = b.toInt() and 0xFF
            total += when {
                c in 32..126 -> ascii[c - 32]
                c >= 0xC0 -> latin[c - 0xC0]
                else -> 556
            }
        }
        return total / 1000f
    }
}
