@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.scanlimpo.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.data.ScanDocument
import br.com.scanlimpo.data.ScanPage
import br.com.scanlimpo.export.ExportActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun allText(doc: ScanDocument): String =
    if (doc.pages.size == 1) {
        doc.pages[0].text
    } else {
        doc.pages.mapIndexed { i, page -> "--- Página ${i + 1} ---\n${page.text}" }.joinToString("\n\n")
    }

@Composable
fun TextScreen(docId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val progress by app.ocr.progress.collectAsStateWithLifecycle()
    val doc = docs.find { it.id == docId }

    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Roda no escopo do app: sair da tela não joga fora o que já estava sendo lido.
    LaunchedEffect(docId) { app.scope.launch { app.ocr.ensure(docId) } }

    val running = progress[docId]
    val anyText = doc.pages.any { it.text.isNotBlank() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                },
                title = { Text("Texto do documento") },
                actions = {
                    IconButton(
                        enabled = anyText,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(doc.name, allText(doc)))
                            Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
                        },
                    ) { Icon(Icons.Outlined.ContentCopy, "Copiar todo o texto") }
                    IconButton(
                        enabled = anyText,
                        onClick = { ExportActions.shareText(context, allText(doc)) },
                    ) { Icon(Icons.Outlined.IosShare, "Compartilhar texto") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            running?.let { (done, total) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Lendo a página ${done + 1} de $total…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(doc.pages, key = { _, page -> page.id }) { index, page ->
                    PageTextEditor(
                        docId = docId,
                        page = page,
                        number = index + 1,
                        showNumber = doc.pages.size > 1,
                        reading = running != null && !page.hasCurrentOcr,
                    )
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun PageTextEditor(docId: String, page: ScanPage, number: Int, showNumber: Boolean, reading: Boolean) {
    val context = LocalContext.current
    val app = context.app
    // Reinicia quando chega um OCR novo. Depois disso, o texto digitado aqui é que vale.
    var text by remember(page.id, page.ocrText) { mutableStateOf(page.text) }

    LaunchedEffect(text) {
        if (text != page.text) {
            delay(500)
            app.repo.setUserText(docId, page.id, text)
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showNumber) {
                Text(
                    "Página $number",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            if (page.userText != null) {
                TextButton(
                    onClick = {
                        text = page.ocrText.orEmpty()
                    },
                ) { Text("Voltar ao original") }
            }
        }
        if (text.isBlank() && !page.hasCurrentOcr) {
            Text(
                if (reading) "Lendo…" else "Aguardando a leitura desta página…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else if (text.isBlank() && page.ocrText.isNullOrBlank() && page.userText == null) {
            Text(
                "Não encontrei texto nesta página.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
