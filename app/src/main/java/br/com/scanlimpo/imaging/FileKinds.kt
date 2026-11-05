package br.com.scanlimpo.imaging

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.IOException

enum class FileKind { PDF, TIFF, IMAGE, DOCX, TEXT, UNKNOWN }

object FileKinds {
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Decide pelo conteúdo do arquivo primeiro. Nome e tipo informados podem estar errados. */
    fun detect(context: Context, uri: Uri, hintedMime: String? = null): FileKind {
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            }
        }.getOrNull() ?: ByteArray(0)

        val text = String(head, Charsets.ISO_8859_1)
        val mime = hintedMime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = displayName(context, uri).orEmpty().lowercase()
        return when {
            text.contains("%PDF-") -> FileKind.PDF
            text.startsWith("II*\u0000") || text.startsWith("MM\u0000*") -> FileKind.TIFF
            text.startsWith("PK\u0003\u0004") && (mime == DOCX_MIME || name.endsWith(".docx")) -> FileKind.DOCX
            mime == "application/pdf" || name.endsWith(".pdf") -> FileKind.PDF
            mime?.startsWith("image/") == true -> FileKind.IMAGE
            mime?.startsWith("text/") == true || name.endsWith(".txt") -> FileKind.TEXT
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> FileKind.IMAGE
            else -> FileKind.UNKNOWN
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * Só aceita arquivos entregues por um provedor de conteúdo de outro app.
     * Recusar file:// e o nosso próprio provedor impede que um app mal-intencionado
     * faça este app abrir e copiar os arquivos privados dele mesmo.
     */
    fun isAllowedIncoming(context: Context, uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority != "${context.packageName}.files"
}

class LockedPdfException(cause: Throwable) : Exception(cause)

class OpenedPdf internal constructor(
    val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val temporary: File?,
) : Closeable {
    override fun close() {
        renderer.close()
        descriptor.close()
        temporary?.delete()
    }
}

object PdfOpener {
    /**
     * Abre o PDF direto. Alguns apps entregam um fluxo que não permite pular de posição, e o
     * leitor do Android exige isso. Nesse caso faz uma cópia temporária.
     *
     * @throws LockedPdfException se o PDF tiver senha.
     */
    fun open(context: Context, uri: Uri): OpenedPdf {
        val resolver = context.contentResolver
        val direct = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Não foi possível abrir o PDF")
        try {
            return OpenedPdf(PdfRenderer(direct), direct, null)
        } catch (e: SecurityException) {
            direct.close()
            throw LockedPdfException(e)
        } catch (e: IOException) {
            direct.close()
        } catch (e: IllegalArgumentException) {
            direct.close()
        }

        val temporary = File(context.cacheDir, "leitura_${System.nanoTime()}.pdf")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o PDF")
            input.use { source -> temporary.outputStream().use { source.copyTo(it) } }
            val copy = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return OpenedPdf(PdfRenderer(copy), copy, temporary)
            } catch (e: SecurityException) {
                copy.close()
                throw LockedPdfException(e)
            } catch (e: IOException) {
                copy.close()
                throw e
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }
}
