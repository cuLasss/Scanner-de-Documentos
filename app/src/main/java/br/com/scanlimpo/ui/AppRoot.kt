package br.com.scanlimpo.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import br.com.scanlimpo.app
import br.com.scanlimpo.ui.annotate.AnnotateScreen
import br.com.scanlimpo.ui.camera.ScanCameraScreen
import br.com.scanlimpo.ui.common.BusyOverlay
import br.com.scanlimpo.ui.crop.CropScreen
import br.com.scanlimpo.ui.document.DocumentScreen
import br.com.scanlimpo.ui.editor.EditorScreen
import br.com.scanlimpo.ui.export.ExportScreen
import br.com.scanlimpo.ui.home.HomeScreen
import br.com.scanlimpo.ui.settings.SettingsScreen
import br.com.scanlimpo.ui.text.TextScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
private data object HomeRoute

@Serializable
private data object SettingsRoute

@Serializable
private data class ScanRoute(val docId: String = "", val replacePageId: String = "")

@Serializable
private data class DocumentRoute(val docId: String)

@Serializable
private data class EditorRoute(val docId: String, val pageId: String)

@Serializable
private data class CropRoute(val docId: String, val pageId: String)

@Serializable
private data class AnnotateRoute(val docId: String, val pageId: String)

@Serializable
private data class TextRoute(val docId: String)

@Serializable
private data class ExportRoute(val docId: String, val pageIds: String = "")

/** Dois toques rápidos em "voltar" não podem esvaziar a pilha, senão a tela fica em branco. */
private fun NavController.safePop() {
    if (previousBackStackEntry != null) popBackStack()
}

@Composable
fun AppRoot(
    sharedUris: MutableStateFlow<List<Uri>>,
    scanRequested: MutableStateFlow<Boolean>,
    documentRequested: MutableStateFlow<String?>,
) {
    val context = LocalContext.current
    val app = context.app
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val scanShortcut by scanRequested.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }

    fun openDocument(id: String, cropPageId: String?) {
        nav.navigate(DocumentRoute(id))
        // Uma foto só: abre direto no ajuste das bordas, em vez de esconder isso atrás de dois toques.
        if (cropPageId != null) nav.navigate(CropRoute(id, cropPageId))
    }

    // Imagens e PDFs recebidos de outros apps viram um documento novo. O collect atende um pedido
    // de cada vez, então esvaziar o fluxo aqui dentro não cancela a importação em andamento.
    LaunchedEffect(Unit) {
        sharedUris.filter { it.isNotEmpty() }.collect { uris ->
            sharedUris.value = emptyList()
            busy = "Importando…"
            try {
                val outcome = app.repo.createDocument(uris) { done, total ->
                    busy = "Importando ${minOf(done + 1, total)} de $total…"
                }
                val id = outcome.docId
                if (id != null) {
                    openDocument(id, outcome.singlePhotoPageId)
                } else {
                    Toast.makeText(context, outcome.message ?: "Não consegui abrir esse arquivo.", Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = null
            }
        }
    }

    // Pedido do leitor: "Editar" abre o documento recém-importado aqui.
    LaunchedEffect(Unit) {
        documentRequested.filterNotNull().collect { id ->
            documentRequested.value = null
            nav.navigate(DocumentRoute(id))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            enterTransition = { fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 12 } },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { it / 12 } },
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    scanRequested = scanShortcut,
                    onScanConsumed = { scanRequested.value = false },
                    onScan = { nav.navigate(ScanRoute()) },
                    onOpenDocument = ::openDocument,
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { nav.safePop() })
            }
            composable<ScanRoute> { entry ->
                val route = entry.toRoute<ScanRoute>()
                ScanCameraScreen(
                    singleShot = route.replacePageId.isNotEmpty(),
                    onCancel = { nav.safePop() },
                    onFinish = { pages ->
                        // Fica nesta tela até terminar: as fotos estão na pasta temporária que ela apaga ao sair.
                        scope.launch {
                            busy = "Salvando…"
                            try {
                                when {
                                    route.replacePageId.isNotEmpty() -> {
                                        app.repo.replaceWithCapture(route.docId, route.replacePageId, pages.first())
                                        nav.safePop()
                                    }
                                    route.docId.isNotEmpty() -> {
                                        app.repo.addCaptures(route.docId, pages)
                                        nav.safePop()
                                    }
                                    else -> {
                                        val id = app.repo.createFromCaptures(pages)
                                        nav.navigate(DocumentRoute(id)) { popUpTo<ScanRoute> { inclusive = true } }
                                    }
                                }
                            } catch (e: IOException) {
                                Log.e("AppRoot", "Falha ao salvar as páginas", e)
                                Toast.makeText(context, "Não consegui salvar. O armazenamento pode estar cheio.", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = null
                            }
                        }
                    },
                )
            }
            composable<DocumentRoute> { entry ->
                val route = entry.toRoute<DocumentRoute>()
                DocumentScreen(
                    docId = route.docId,
                    onBack = { nav.safePop() },
                    onOpenPage = { nav.navigate(EditorRoute(route.docId, it)) },
                    onOpenText = { nav.navigate(TextRoute(route.docId)) },
                    onExport = { pages -> nav.navigate(ExportRoute(route.docId, pages.joinToString(","))) },
                    onOpenDocument = { id ->
                        nav.safePop()
                        nav.navigate(DocumentRoute(id))
                    },
                    onScanMore = { nav.navigate(ScanRoute(docId = route.docId)) },
                )
            }
            composable<EditorRoute> { entry ->
                val route = entry.toRoute<EditorRoute>()
                EditorScreen(
                    docId = route.docId,
                    initialPageId = route.pageId,
                    onBack = { nav.safePop() },
                    onCrop = { nav.navigate(CropRoute(route.docId, it)) },
                    onAnnotate = { nav.navigate(AnnotateRoute(route.docId, it)) },
                    onOpenText = { nav.navigate(TextRoute(route.docId)) },
                    onRescan = { nav.navigate(ScanRoute(docId = route.docId, replacePageId = it)) },
                )
            }
            composable<CropRoute> { entry ->
                val route = entry.toRoute<CropRoute>()
                CropScreen(route.docId, route.pageId, onDone = { nav.safePop() })
            }
            composable<AnnotateRoute> { entry ->
                val route = entry.toRoute<AnnotateRoute>()
                AnnotateScreen(route.docId, route.pageId, onDone = { nav.safePop() })
            }
            composable<TextRoute> { entry ->
                TextScreen(entry.toRoute<TextRoute>().docId, onBack = { nav.safePop() })
            }
            composable<ExportRoute> { entry ->
                val route = entry.toRoute<ExportRoute>()
                ExportScreen(
                    docId = route.docId,
                    pageIds = route.pageIds.split(",").filter { it.isNotEmpty() },
                    onBack = { nav.safePop() },
                )
            }
        }
        busy?.let { BusyOverlay(it) }
    }
}
