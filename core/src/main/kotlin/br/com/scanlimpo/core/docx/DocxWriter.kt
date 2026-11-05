package br.com.scanlimpo.core.docx

import java.io.Closeable
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Gera um .docx (A4, margens de 2 cm). As imagens vão para o ZIP assim que são adicionadas. */
class DocxWriter(output: OutputStream, private val title: String) : Closeable {
    private val zip = ZipOutputStream(output)
    private val body = StringBuilder()
    private val imageRels = StringBuilder()
    private var imageCount = 0
    private var breakBeforeNext = false
    private var finished = false

    /** A próxima coisa adicionada começa em uma página nova. */
    fun pageBreak() {
        breakBeforeNext = true
    }

    fun addParagraph(text: String, bold: Boolean = false) {
        val props = StringBuilder()
        if (breakBeforeNext) props.append("<w:pageBreakBefore/>")
        breakBeforeNext = false
        body.append("<w:p>")
        if (props.isNotEmpty()) body.append("<w:pPr>").append(props).append("</w:pPr>")
        val clean = xml(text)
        if (clean.isNotEmpty()) {
            body.append("<w:r>")
            if (bold) body.append("<w:rPr><w:b/></w:rPr>")
            body.append("<w:t xml:space=\"preserve\">").append(clean).append("</w:t></w:r>")
        }
        body.append("</w:p>")
    }

    /** Adiciona a imagem centralizada, ajustada à área útil da página. */
    fun addJpeg(jpeg: ByteArray, widthPx: Int, heightPx: Int) {
        imageCount++
        val id = imageCount
        val name = "image$id.jpeg"
        zip.putNextEntry(ZipEntry("word/media/$name").apply { method = ZipEntry.DEFLATED })
        zip.write(jpeg)
        zip.closeEntry()
        imageRels.append("<Relationship Id=\"rIdImg$id\" ")
            .append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" ")
            .append("Target=\"media/$name\"/>")

        val scale = minOf(MAX_WIDTH_EMU / widthPx.toDouble(), MAX_HEIGHT_EMU / heightPx.toDouble())
        val cx = (widthPx * scale).toLong()
        val cy = (heightPx * scale).toLong()

        body.append("<w:p><w:pPr>")
        if (breakBeforeNext) body.append("<w:pageBreakBefore/>")
        breakBeforeNext = false
        body.append("<w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>")
        body.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        body.append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
        body.append("<wp:docPr id=\"$id\" name=\"Página $id\"/>")
        body.append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
        body.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        body.append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>")
        body.append("<pic:blipFill><a:blip r:embed=\"rIdImg$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        body.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
        body.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
        body.append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    fun finish() {
        if (finished) return
        finished = true
        entry("[Content_Types].xml", CONTENT_TYPES)
        entry("_rels/.rels", ROOT_RELS)
        entry("word/_rels/document.xml.rels", DOCUMENT_RELS_HEAD + imageRels + "</Relationships>")
        entry("word/styles.xml", STYLES)
        entry("docProps/app.xml", APP_PROPS)
        entry("docProps/core.xml", coreProps())
        entry(
            "word/document.xml",
            DOCUMENT_HEAD + body +
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" " +
                "w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>",
        )
        zip.finish()
        zip.flush()
    }

    override fun close() {
        finish()
        zip.close()
    }

    private fun entry(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun coreProps(): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        return XML_DECL +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${xml(title)}</dc:title><dc:creator>Scan Limpo</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>"
    }

    /** Escapa o texto e descarta caracteres que o XML 1.0 não aceita. */
    private fun xml(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> sb.append(' ')
                cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private companion object {
        // Área útil do A4 com 2 cm de margem, com folga na altura para não empurrar parágrafo para outra página.
        const val MAX_WIDTH_EMU = 6_120_130.0
        const val MAX_HEIGHT_EMU = 9_000_000.0

        const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

        const val CONTENT_TYPES = XML_DECL +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>"

        const val ROOT_RELS = XML_DECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

        const val DOCUMENT_RELS_HEAD = XML_DECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"

        const val APP_PROPS = XML_DECL +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
            "<Application>Scan Limpo</Application></Properties>"

        const val STYLES = XML_DECL +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"pt-BR\"/>" +
            "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "</w:styles>"

        const val DOCUMENT_HEAD = XML_DECL +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
    }
}
