package br.com.scanlimpo.ui.crop

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.data.Quad
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.DarkSystemBars
import br.com.scanlimpo.ui.theme.EditorAccent
import br.com.scanlimpo.ui.theme.EditorBackground
import br.com.scanlimpo.ui.theme.EditorMuted
import br.com.scanlimpo.ui.theme.EditorOnSurface
import br.com.scanlimpo.ui.theme.EditorSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CropScreen(docId: String, pageId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val page = docs.find { it.id == docId }?.pages?.find { it.id == pageId }
    val scope = rememberCoroutineScope()

    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    DarkSystemBars()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var detected by remember { mutableStateOf<Quad?>(null) }
    var corners by remember { mutableStateOf((page.edit.quad ?: Quad.FULL).toOffsets()) }
    var confirmOverlays by remember { mutableStateOf(false) }

    LaunchedEffect(pageId) {
        val (bitmap, quad) = withContext(Dispatchers.Default) {
            val b = PageRenderer.rotatedSource(app.repo.pageDir(docId, pageId), page.sourceFile, page.edit.rotation, 1800)
            b to runCatching { PageRenderer.detectQuad(b) }.getOrNull()
        }
        image = bitmap.asImageBitmap()
        detected = quad
    }

    fun commit() {
        val quad = corners.toQuadOrNull()
        if (quad == page.edit.quad) {
            onDone()
            return
        }
        scope.launch {
            // As marcações foram posicionadas sobre o recorte anterior. Fora de lugar, uma tarja deixaria de cobrir o que devia.
            app.repo.updateEdit(docId, pageId) { it.copy(quad = quad, marks = emptyList(), signatures = emptyList()) }
            withContext(Dispatchers.Main.immediate) { onDone() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(EditorBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) { Icon(Icons.Outlined.Close, "Cancelar", tint = EditorOnSurface) }
            Text(
                "Ajustar bordas",
                color = EditorOnSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = EditorAccent)
            } else {
                QuadEditor(bitmap, corners, { corners = it }, Modifier.fillMaxSize())
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(EditorSurface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val quad = detected
                        if (quad != null) {
                            corners = quad.toOffsets()
                        } else {
                            Toast.makeText(context, "Não achei as bordas sozinho. Ajuste os cantos com o dedo.", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(Icons.Outlined.AutoFixHigh, null, tint = EditorAccent)
                    Text(" Auto", color = EditorAccent)
                }
                TextButton(onClick = { corners = Quad.FULL.toOffsets() }) {
                    Icon(Icons.Outlined.CropFree, null, tint = EditorOnSurface)
                    Text(" Tudo", color = EditorOnSurface)
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = { if (page.edit.hasOverlays) confirmOverlays = true else commit() },
                    colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
                    enabled = image != null,
                ) {
                    Text("Aplicar")
                }
            }
            if (page.edit.hasOverlays) {
                Text(
                    "Esta página tem assinatura ou tarja: elas saem ao mudar o recorte.",
                    color = EditorMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }

    if (confirmOverlays) {
        ConfirmDialog(
            title = "Mudar o recorte?",
            message = "As assinaturas e tarjas desta página serão removidas, porque ficariam fora do lugar. Depois é só marcar de novo.",
            confirmLabel = "Mudar recorte",
            onConfirm = {
                confirmOverlays = false
                commit()
            },
            onDismiss = { confirmOverlays = false },
        )
    }
}
