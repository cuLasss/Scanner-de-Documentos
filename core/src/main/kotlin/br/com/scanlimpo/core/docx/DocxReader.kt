package br.com.scanlimpo.core.docx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

sealed interface DocxBlock {
    class Paragraph(val text: String, val bold: Boolean = false, val heading: Boolean = false) : DocxBlock
    class Picture(val bytes: ByteArray) : DocxBlock
}

class DocxFormatException(message: String) : Exception(message)

/**
 * Leitura simplificada de .docx: texto, títulos, negrito, tabelas (uma linha por vez) e imagens.
 * Serve para ler o conteúdo, não para reproduzir a formatação exata.
 *
 * O arquivo pode vir de qualquer lugar: os tamanhos são limitados contra zip bomb, e nada é gravado
 * em disco a partir dos nomes internos do ZIP.
 */
object DocxReader {
    private const val MAX_XML_BYTES = 64 * 1024 * 1024
    private const val MAX_PICTURE_BYTES = 48 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    fun read(input: InputStream): List<DocxBlock> {
        var documentXml: String? = null
        var relsXml: String? = null
        val media = HashMap<String, ByteArray>()
        var total = 0L

        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val limit = when {
                        name == "word/document.xml" || name == "word/_rels/document.xml.rels" -> MAX_XML_BYTES
                        name.startsWith("word/media/") -> MAX_PICTURE_BYTES
                        else -> continue
                    }
                    val bytes = readLimited(zip, limit)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw DocxFormatException("Arquivo grande demais")
                    when (name) {
                        "word/document.xml" -> documentXml = bytes.toString(Charsets.UTF_8)
                        "word/_rels/document.xml.rels" -> relsXml = bytes.toString(Charsets.UTF_8)
                        else -> media[name] = bytes
                    }
                }
            }
        } catch (e: IOException) {
            throw DocxFormatException("Não é um arquivo Word válido: ${e.message}")
        }

        val document = documentXml ?: throw DocxFormatException("Não é um arquivo Word (.docx)")
        val targets = relsXml?.let(::parseRelationships).orEmpty()
        return parseDocument(document) { id ->
            val target = targets[id] ?: return@parseDocument null
            media[if (target.startsWith("/")) target.drop(1) else "word/$target"]
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw DocxFormatException("Parte interna grande demais")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parseRelationships(xml: String): Map<String, String> {
        val result = HashMap<String, String>()
        val parser = MiniXml(xml)
        while (true) {
            when (parser.next()) {
                MiniXml.Event.EOF -> return result
                MiniXml.Event.START -> if (parser.localName == "Relationship") {
                    val id = parser.attribute("Id")
                    val target = parser.attribute("Target")
                    if (id != null && target != null) result[id] = target
                }
                else -> Unit
            }
        }
    }

    private fun parseDocument(xml: String, picture: (String) -> ByteArray?): List<DocxBlock> {
        val blocks = ArrayList<DocxBlock>()
        val parser = MiniXml(xml)

        val text = StringBuilder()
        var inText = false
        var heading = false
        var boldChars = 0
        var plainChars = 0
        var runBold = false
        var inRunProps = false
        var inParagraph = false

        var row: ArrayList<String>? = null
        var cell: StringBuilder? = null
        var fallbackDepth = 0

        while (true) {
            when (parser.next()) {
                MiniXml.Event.EOF -> return blocks
                MiniXml.Event.TEXT -> if (inText && fallbackDepth == 0) {
                    text.append(parser.text)
                    if (runBold) boldChars += parser.text.length else plainChars += parser.text.length
                }
                MiniXml.Event.START -> {
                    val name = parser.name
                    if (name == "mc:Fallback") {
                        // O Word grava a mesma figura duas vezes: a principal e uma alternativa para versões antigas.
                        fallbackDepth++
                        continue
                    }
                    if (fallbackDepth > 0) {
                        if (name == "mc:Fallback") fallbackDepth++
                        continue
                    }
                    when (name) {
                        "w:p" -> {
                            inParagraph = true
                            text.setLength(0)
                            heading = false
                            boldChars = 0
                            plainChars = 0
                        }
                        "w:r" -> runBold = false
                        "w:rPr" -> inRunProps = true
                        "w:b" -> if (inRunProps) {
                            val value = parser.attribute("val")
                            runBold = value != "0" && value != "false"
                        }
                        "w:pStyle" -> {
                            val style = parser.attribute("val").orEmpty().lowercase()
                            if (style.startsWith("heading") || style.startsWith("ttulo") || style.startsWith("título") || style == "title") {
                                heading = true
                            }
                        }
                        "w:t" -> inText = true
                        "w:tab" -> if (inParagraph && !inRunProps) text.append('\t')
                        "w:br", "w:cr" -> if (parser.attribute("type") != "page") text.append('\n')
                        "w:tr" -> row = ArrayList()
                        "w:tc" -> cell = StringBuilder()
                        "a:blip" -> parser.attribute("embed")?.let(picture)?.let { blocks += DocxBlock.Picture(it) }
                    }
                }
                MiniXml.Event.END -> {
                    val name = parser.name
                    if (fallbackDepth > 0) {
                        if (name == "mc:Fallback") fallbackDepth--
                        continue
                    }
                    when (name) {
                        "w:t" -> inText = false
                        "w:rPr" -> inRunProps = false
                        "w:p" -> {
                            inParagraph = false
                            val currentCell = cell
                            if (currentCell != null) {
                                if (currentCell.isNotEmpty() && text.isNotEmpty()) currentCell.append(' ')
                                currentCell.append(text)
                            } else {
                                blocks += DocxBlock.Paragraph(
                                    text.toString(),
                                    bold = boldChars > 0 && plainChars == 0,
                                    heading = heading,
                                )
                            }
                        }
                        "w:tc" -> {
                            cell?.let { row?.add(it.toString()) }
                            cell = null
                        }
                        "w:tr" -> {
                            row?.let { blocks += DocxBlock.Paragraph(it.joinToString("  |  ")) }
                            row = null
                        }
                    }
                }
            }
        }
    }
}

/**
 * Leitor de XML bem pequeno, com o que o document.xml usa.
 * DOCTYPE é recusado e entidades próprias não são expandidas, o que elimina XXE e "billion laughs" por construção.
 */
internal class MiniXml(private val source: String) {
    enum class Event { START, END, TEXT, EOF }

    var name = ""
        private set
    var text = ""
        private set
    val localName: String get() = name.substringAfter(':')

    private var position = 0
    private var pendingEnd = false
    private val attributes = HashMap<String, String>()

    /** Valor do atributo pelo nome sem prefixo. */
    fun attribute(local: String): String? = attributes[local]

    fun next(): Event {
        if (pendingEnd) {
            pendingEnd = false
            return Event.END
        }
        while (position < source.length) {
            if (source[position] != '<') {
                val end = source.indexOf('<', position).let { if (it < 0) source.length else it }
                text = decodeEntities(source.substring(position, end))
                position = end
                return Event.TEXT
            }
            when {
                source.startsWith("<?", position) -> skipPast("?>")
                source.startsWith("<!--", position) -> skipPast("-->")
                source.startsWith("<![CDATA[", position) -> {
                    val end = source.indexOf("]]>", position)
                    if (end < 0) throw DocxFormatException("XML truncado")
                    text = source.substring(position + 9, end)
                    position = end + 3
                    return Event.TEXT
                }
                source.startsWith("<!", position) -> throw DocxFormatException("XML com DOCTYPE não é aceito")
                source.startsWith("</", position) -> {
                    val end = source.indexOf('>', position)
                    if (end < 0) throw DocxFormatException("XML truncado")
                    name = source.substring(position + 2, end).trim()
                    position = end + 1
                    return Event.END
                }
                else -> {
                    readStartTag()
                    return Event.START
                }
            }
        }
        return Event.EOF
    }

    private fun skipPast(marker: String) {
        val end = source.indexOf(marker, position)
        if (end < 0) throw DocxFormatException("XML truncado")
        position = end + marker.length
    }

    private fun readStartTag() {
        attributes.clear()
        var i = position + 1
        val start = i
        while (i < source.length && source[i] !in " \t\r\n/>") i++
        name = source.substring(start, i)
        while (true) {
            while (i < source.length && source[i] in " \t\r\n") i++
            if (i >= source.length) throw DocxFormatException("XML truncado")
            when (source[i]) {
                '>' -> {
                    position = i + 1
                    return
                }
                '/' -> {
                    val close = source.indexOf('>', i)
                    if (close < 0) throw DocxFormatException("XML truncado")
                    position = close + 1
                    pendingEnd = true
                    return
                }
            }
            val keyStart = i
            while (i < source.length && source[i] !in "= \t\r\n/>") i++
            val key = source.substring(keyStart, i)
            while (i < source.length && source[i] in " \t\r\n") i++
            if (i >= source.length || source[i] != '=') continue
            i++
            while (i < source.length && source[i] in " \t\r\n") i++
            if (i >= source.length) throw DocxFormatException("XML truncado")
            val quote = source[i]
            if (quote != '"' && quote != '\'') throw DocxFormatException("XML malformado")
            val valueEnd = source.indexOf(quote, i + 1)
            if (valueEnd < 0) throw DocxFormatException("XML truncado")
            attributes[key.substringAfter(':')] = decodeEntities(source.substring(i + 1, valueEnd))
            i = valueEnd + 1
        }
    }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = value.indexOf(';', i)
            if (end < 0 || end - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = value.substring(i + 1, end)
            val decoded: Int? = when {
                entity == "amp" -> '&'.code
                entity == "lt" -> '<'.code
                entity == "gt" -> '>'.code
                entity == "quot" -> '"'.code
                entity == "apos" -> '\''.code
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                else -> null
            }
            if (decoded != null && Character.isValidCodePoint(decoded)) {
                out.appendCodePoint(decoded)
            } else {
                out.append(value, i, end + 1)
            }
            i = end + 1
        }
        return out.toString()
    }
}
