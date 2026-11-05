package br.com.scanlimpo.core.text

import java.text.Normalizer

/** Bloco de texto do OCR, com posição normalizada (0..1) na página. */
class OcrBlockInfo(val lines: List<String>, val x: Float, val y: Float, val width: Float, val height: Float)

object OcrText {
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Monta o texto na ordem de leitura. O reconhecimento não entrega os blocos em ordem: o título
     * pode vir depois do primeiro parágrafo, e uma tabela sai coluna por coluna.
     *
     * Aqui os blocos são lidos de cima para baixo. Os que estão na mesma altura formam uma faixa, lida
     * da esquerda para a direita, e células de uma linha só ficam juntas, como em uma linha de tabela.
     */
    fun arrange(blocks: List<OcrBlockInfo>): String {
        val rows = ArrayList<MutableList<OcrBlockInfo>>()
        for (block in blocks.filter { it.lines.isNotEmpty() }.sortedBy { it.y }) {
            val row = rows.lastOrNull()
            if (row != null && block.y < row.first().y + row.first().height * 0.6f) row += block else rows += mutableListOf(block)
        }
        return rows.joinToString("\n\n") { row ->
            val ordered = row.sortedBy { it.x }
            if (ordered.size > 1 && ordered.all { it.lines.size == 1 }) {
                ordered.joinToString("    ") { it.lines.first() }
            } else {
                ordered.joinToString("\n\n") { it.lines.joinToString("\n") }
            }
        }
    }

    /**
     * Texto do OCR (linhas separadas por \n e blocos por linha em branco) em parágrafos corridos,
     * juntando palavras hifenizadas na quebra de linha ("docu-\nmento" vira "documento").
     */
    fun paragraphs(text: String): List<String> =
        text.split(PARAGRAPH_BREAK).mapNotNull { block ->
            val joined = StringBuilder()
            for (raw in block.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    joined.isEmpty() -> joined.append(line)
                    joined.last() == '-' && line.first().isLowerCase() -> {
                        joined.setLength(joined.length - 1)
                        joined.append(line)
                    }
                    else -> joined.append(' ').append(line)
                }
            }
            joined.takeIf { it.isNotEmpty() }?.toString()
        }

    /** Forma para busca: minúsculas e sem acento, assim "acao" encontra "Ação". */
    fun searchKey(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** Trecho ao redor da primeira ocorrência, para mostrar nos resultados da busca. */
    fun snippet(text: String, query: String, radius: Int = 42): String? {
        val key = searchKey(query)
        if (key.isEmpty()) return null
        // Só tira os acentos caractere a caractere, para os índices continuarem batendo com o texto original.
        val folded = buildString(text.length) {
            for (c in text) append(searchKey(c.toString()).firstOrNull() ?: c)
        }
        val index = folded.indexOf(key)
        if (index < 0) return null
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + key.length + radius).coerceAtMost(text.length)
        val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return buildString {
            if (start > 0) append('…')
            append(core)
            if (end < text.length) append('…')
        }
    }
}
