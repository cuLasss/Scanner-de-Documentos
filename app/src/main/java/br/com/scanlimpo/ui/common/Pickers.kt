package br.com.scanlimpo.ui.common

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class Pickers(
    val pickImages: () -> Unit,
    val pickFile: () -> Unit,
)

/** Trazer páginas de arquivos que já estão no celular. Nenhum dos dois caminhos pede permissão. */
@Composable
fun rememberPickers(onResult: (List<Uri>) -> Unit, onMessage: (String) -> Unit): Pickers {
    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        if (uris.isNotEmpty()) onResult(uris)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onResult(listOf(uri))
    }
    return remember {
        Pickers(
            pickImages = {
                imagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            pickFile = {
                try {
                    fileLauncher.launch(arrayOf("application/pdf", "image/*"))
                } catch (e: ActivityNotFoundException) {
                    Log.w("Pickers", "Sem seletor de arquivos", e)
                    onMessage("Não achei um seletor de arquivos neste celular.")
                }
            },
        )
    }
}

/** Escolhe quantos itens aparecem por linha na grade. */
@Composable
fun GridSizeButton(columns: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.GridView, "Ajustar grade") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (1..4).forEach { count ->
                DropdownMenuItem(
                    text = { Text("$count por linha") },
                    trailingIcon = { if (count == columns) Icon(Icons.Outlined.Check, "Selecionado") },
                    onClick = {
                        open = false
                        onChange(count)
                    },
                )
            }
        }
    }
}

private const val MAX_IMAGES = 50
