package br.com.scanlimpo.data

import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.text.DocumentNamer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Pt(val x: Float, val y: Float)

/** Quatro cantos normalizados (0..1) sobre a imagem já girada. */
@Serializable
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun rotatedCw() = Quad(tl = bl.rotatedCw(), tr = tl.rotatedCw(), br = tr.rotatedCw(), bl = br.rotatedCw())

    private fun Pt.rotatedCw() = Pt(1f - y, x)

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        fun of(a: FloatArray) = Quad(Pt(a[0], a[1]), Pt(a[2], a[3]), Pt(a[4], a[5]), Pt(a[6], a[7]))
    }
}

@Serializable
enum class MarkKind(val label: String) {
    BLACK("Tarja"),
    WHITE("Corretivo"),
    HIGHLIGHT("Marca-texto"),
}

/**
 * Retângulo normalizado sobre a página final.
 *
 * @param order posição na sequência de aplicação, junto com as assinaturas. É o que permite
 * desfazer passo a passo, mesmo depois de sair da tela.
 */
@Serializable
data class RectMark(val kind: MarkKind, val x: Float, val y: Float, val w: Float, val h: Float, val order: Long = 0)

/**
 * Assinatura posicionada na página. O tamanho é relativo ao menor lado da página,
 * o que mantém a proporção certa quando a página é girada.
 */
@Serializable
data class SignatureMark(
    val file: String,
    val cx: Float,
    val cy: Float,
    val size: Float,
    val rotation: Int = 0,
    val order: Long = 0,
)

/** Documentos antigos podem citar filtros que não existem mais: "Sem sombra" e "Vivo" viraram parte do "Melhorar". */
object PageFilterSerializer : KSerializer<PageFilter> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PageFilter", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PageFilter) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): PageFilter {
        val name = decoder.decodeString()
        return PageFilter.entries.find { it.name == name } ?: PageFilter.AUTO
    }
}

@Serializable
data class PageEdit(
    @Serializable(with = PageFilterSerializer::class)
    val filter: PageFilter = PageFilter.ORIGINAL,
    /** Nível do filtro: de 0 (sem efeito) a 1 (força total). */
    val filterStrength: Float = 1f,
    val rotation: Int = 0,
    val quad: Quad? = null,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val marks: List<RectMark> = emptyList(),
    val signatures: List<SignatureMark> = emptyList(),
) {
    val isIdentity: Boolean get() = this == PageEdit()

    /** O OCR só precisa rodar de novo se a geometria ou as tarjas mudarem; filtro não afeta o texto. */
    val ocrKey: String get() = "$rotation|$quad|$marks"

    val geometryKey: String get() = "$rotation|$quad"

    val hasOverlays: Boolean get() = marks.isNotEmpty() || signatures.isNotEmpty()

    fun rotatedCw() = copy(
        rotation = (rotation + 90) % 360,
        quad = quad?.rotatedCw(),
        marks = marks.map { it.copy(x = 1f - it.y - it.h, y = it.x, w = it.h, h = it.w) },
        signatures = signatures.map { it.copy(cx = 1f - it.cy, cy = it.cx, rotation = (it.rotation + 90) % 360) },
    )
}

@Serializable
data class ScanPage(
    val id: String,
    val sourceFile: String = "source.jpg",
    val sourceWidth: Int,
    val sourceHeight: Int,
    val edit: PageEdit = PageEdit(),
    /** Edição que a imagem em [renderFile] representa. Se for diferente de [edit], a imagem está desatualizada. */
    val renderedEdit: PageEdit = PageEdit(),
    val renderFile: String? = null,
    val renderWidth: Int = sourceWidth,
    val renderHeight: Int = sourceHeight,
    val thumbFile: String = "thumb.jpg",
    val ocrKey: String? = null,
    val ocrText: String? = null,
    /** Correção manual do texto. Prevalece sobre o OCR nas exportações de texto. */
    val userText: String? = null,
    /** Nomes sugeridos a partir do que está escrito na página, o melhor primeiro. */
    val nameSuggestions: List<String> = emptyList(),
) {
    val isStale: Boolean get() = edit != renderedEdit
    val displayFile: String get() = renderFile ?: sourceFile
    val hasCurrentOcr: Boolean get() = ocrKey == edit.ocrKey
    val text: String get() = userText ?: ocrText.orEmpty()
}

@Serializable
data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage> = emptyList(),
) {
    /** Sugestões de nome da primeira página que tenha alguma. */
    fun suggestedNames(): List<String> =
        pages.firstOrNull { it.nameSuggestions.isNotEmpty() }?.nameSuggestions
            ?: pages.firstOrNull { it.text.isNotBlank() }?.let { DocumentNamer.suggestFromText(it.text) }
            ?: emptyList()
}

/** Palavras reconhecidas de uma página, com posição normalizada. Fica em ocr.json, ao lado da página. */
@Serializable
data class OcrWord(val t: String, val x: Float, val y: Float, val w: Float, val h: Float)

@Serializable
data class OcrPageData(val words: List<OcrWord>)
