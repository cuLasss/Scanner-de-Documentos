package br.com.scanlimpo.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Assinaturas salvas para reutilizar. Quem usa copia o arquivo para a pasta da página. */
class SignatureStore(context: Context) {
    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }
    private val state = MutableStateFlow(list())
    val signatures: StateFlow<List<File>> = state.asStateFlow()

    fun add(bitmap: Bitmap): File {
        val file = File(dir, "sig_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        state.value = list()
        return file
    }

    fun delete(file: File) {
        file.delete()
        state.value = list()
    }

    private fun list(): List<File> =
        dir.listFiles { f -> f.extension == "png" }.orEmpty().sortedByDescending { it.name }
}
