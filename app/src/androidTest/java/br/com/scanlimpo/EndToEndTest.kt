package br.com.scanlimpo

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.data.MarkKind
import br.com.scanlimpo.data.ScanDocument
import br.com.scanlimpo.data.ThemeMode
import br.com.scanlimpo.export.Exporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Passa pelas telas de verdade, no aparelho, sem depender de toque vindo do computador.
 * As capturas e os arquivos exportados ficam em files/e2e, para conferir depois com outros programas.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val output get() = File(app.getExternalFilesDir(null), "e2e").apply { mkdirs() }
    private lateinit var docId: String

    private fun doc(): ScanDocument = checkNotNull(app.repo.document(docId))
    private fun page() = doc().pages.first()

    private fun shot(name: String) {
        rule.waitForIdle()
        // Com um diálogo aberto há duas janelas, e a captura de uma só falha. A captura não é o que está em teste.
        val bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, timeout: Long = 30_000, condition: () -> Boolean) {
        // O waitUntil do framework conta no relógio acelerado dele: 30 s "dele" passam em poucos segundos reais,
        // e o trabalho em segundo plano (disco, imagem, OCR) anda no tempo real.
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            rule.waitForIdle()
            if (condition()) return
            if (SystemClock.elapsedRealtime() > deadline) {
                saveScreenState(what)
                throw AssertionError("Não aconteceu em $timeout ms: $what")
            }
            Thread.sleep(100)
        }
    }

    /** Quando algo falha, guarda o que estava na tela: é melhor olhar do que adivinhar. */
    private fun saveScreenState(what: String) {
        val name = "falha_" + what.replace(Regex("[^A-Za-z0-9]+"), "_")
        runCatching {
            val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
            val text = roots.joinToString("\n---\n") { root -> describe(root, 0) }
            File(output, "$name.txt").writeText("janelas: ${roots.size}\n$text\n")
        }
        runCatching {
            val bitmap = rule.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun describe(node: SemanticsNode, depth: Int): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        val disabled = node.config.contains(SemanticsProperties.Disabled)
        val line = listOfNotNull(text, description?.let { "desc=$it" }, "desabilitado".takeIf { disabled }).joinToString(" ")
        val own = if (line.isEmpty()) emptyList() else listOf("  ".repeat(depth) + line)
        return (own + node.children.map { describe(it, depth + 1) }).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Entra na tela de editar e espera a imagem carregar: antes disso o "Concluir" fica desabilitado de propósito. */
    private fun openMarks() {
        rule.onNodeWithText("Editar").performClick()
        waitFor("tela de editar") { exists("Editar página") }
        waitFor("imagem da pagina") { rule.onAllNodes(hasTestTag("pagina")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForRender() = waitFor("imagem final pronta") { !page().isStale }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(app.cacheDir, "e2e_foto.jpg")
        instrumentation.context.assets.open("foto.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        waitFor("biblioteca carregada") { app.repo.ready.value }
        runBlocking {
            app.repo.delete(app.repo.documents.value.map { it.id }.toSet())
            docId = checkNotNull(app.repo.createDocument(listOf(Uri.fromFile(file))).docId)
        }
        waitFor("OCR e recorte automático") { page().hasCurrentOcr && !page().isStale }
    }

    @After
    fun restoreTheme() {
        rule.runOnUiThread { app.settings.setThemeMode(ThemeMode.SYSTEM) }
    }

    private fun openDocument() {
        waitFor("documento na lista") { exists(doc().name) }
        rule.onNodeWithText(doc().name).performClick()
        waitFor("tela do documento") { exists("Escaneado em", substring = true) }
    }

    private fun openEditor() {
        openDocument()
        rule.onNodeWithContentDescription("Página 1").performClick()
        waitFor("editor") { exists("Girar") }
    }

    @Test
    fun documentGetsAutomaticNameAndDate() {
        assertEquals("Declaração de residência", doc().name)
        assertTrue("O título vem primeiro no texto: ${page().text.take(60)}", page().text.startsWith("DECLARA"))
        assertTrue("Devia ter alternativas: ${doc().suggestedNames()}", doc().suggestedNames().isNotEmpty())
        shot("01_inicio")
        openDocument()
        rule.onNodeWithText("Escaneado em", substring = true).assertExists()
        shot("02_documento_um_por_linha")
        assertEquals("Grade padrão dentro do documento", 1, app.settings.pageColumns)
        assertEquals("Grade padrão na tela inicial", 2, app.settings.homeColumns)

        // Nome escolhido pela pessoa não pode ser trocado depois.
        runBlocking { app.repo.rename(docId, "Meu nome") }
        rule.onNodeWithText("Texto").performClick()
        waitFor("tela de texto") { exists("RESID", substring = true) }
        shot("03_texto")
        assertEquals("Meu nome", doc().name)
    }

    @Test
    fun filterLevelChangesThePage() {
        openEditor()
        shot("10_editor_original")
        assertTrue("Original não tem barra de nível", !exists("Nível"))

        rule.onNodeWithText("Melhorar").performClick()
        waitFor("barra de nível") { exists("Nível") }
        waitForRender()
        assertEquals(PageFilter.AUTO, page().edit.filter)
        assertEquals(PageFilter.AUTO.defaultStrength, page().edit.filterStrength, 0.001f)
        shot("11_melhorar_padrao")

        rule.onNodeWithTag("nivel_filtro").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        waitFor("nível gravado") { kotlin.math.abs(page().edit.filterStrength - 0.25f) < 0.02f }
        waitForRender()
        shot("12_melhorar_25")

        rule.onNodeWithTag("nivel_filtro").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitFor("nível gravado") { page().edit.filterStrength > 0.98f }
        waitForRender()
        shot("13_melhorar_100")

        rule.onNodeWithText("Cinza").performClick()
        waitFor("filtro aplicado") { page().edit.filter == PageFilter.GRAY && !page().isStale }
        shot("14_cinza")

        rule.onNodeWithText("P&B").performClick()
        waitFor("filtro aplicado") { page().edit.filter == PageFilter.BW && !page().isStale }
        assertTrue("P&B a 100% fica sem perdas", page().renderFile!!.endsWith(".png"))
        shot("15_pb")

        rule.onNodeWithTag("nivel_filtro").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitFor("nível gravado") { kotlin.math.abs(page().edit.filterStrength - 0.5f) < 0.02f && !page().isStale }
        assertTrue("P&B misturado já não é 1 bit", page().renderFile!!.endsWith(".jpg"))
        shot("16_pb_50")

        rule.onNodeWithText("Original").performClick()
        waitFor("volta ao original") { page().edit.filter == PageFilter.ORIGINAL && !page().isStale }
    }

    /** Brilho médio do miolo da tela, onde a página fica. Sem imagem, sobra só o fundo escuro do editor. */
    private fun pageBrightness(): Int {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        var sum = 0
        var count = 0
        for (y in bitmap.height * 3 / 10 until bitmap.height * 5 / 10 step 12) {
            for (x in bitmap.width * 3 / 10 until bitmap.width * 7 / 10 step 12) {
                val p = bitmap.getPixel(x, y)
                sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                count++
            }
        }
        return sum / count
    }

    /** Fotografa a tela até a imagem final chegar e mais um pouco. Nenhuma foto pode sair sem a página. */
    private fun assertNeverBlank(what: String) {
        val deadline = SystemClock.elapsedRealtime() + 2500
        var frames = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val brightness = pageBrightness()
            frames++
            assertTrue("$what: a página sumiu da tela na foto $frames (brilho $brightness)", brightness > 70)
        }
        assertTrue("$what: poucas fotos ($frames)", frames >= 5)
    }

    @Test
    fun pageNeverDisappearsWhileChanging() {
        openEditor()
        waitForRender()
        Thread.sleep(800)

        rule.onNodeWithText("Melhorar").performClick()
        assertNeverBlank("trocar de filtro")
        waitForRender()

        rule.onNodeWithTag("nivel_filtro").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        assertNeverBlank("mudar o nível")
        waitForRender()
        shot("70_nivel_30")

        rule.onNodeWithTag("nivel_filtro").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertNeverBlank("mudar o nível de novo")
        waitForRender()

        rule.onNodeWithText("Girar").performClick()
        assertNeverBlank("girar")
        waitFor("ficou deitada") { !page().isStale && page().renderWidth > page().renderHeight }
        shot("71_girada_com_filtro")

        rule.onNodeWithText("P&B").performClick()
        assertNeverBlank("P&B")

        rule.onNodeWithText("Original").performClick()
        assertNeverBlank("voltar ao original")
    }

    @Test
    fun rotateAnimatesAndKeepsOrientation() {
        openEditor()
        val portrait = page().renderHeight > page().renderWidth
        assertTrue(portrait)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Girar").performClick()
        rule.mainClock.advanceTimeBy(170)
        val mid = rule.onRoot().captureToImage().asAndroidBitmap()
        File(output, "20_girando.png").outputStream().use { mid.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.mainClock.autoAdvance = true

        waitForRender()
        waitFor("ficou deitada") { page().renderWidth > page().renderHeight }
        shot("21_girada")

        // Girando rápido várias vezes, a última imagem precisa corresponder à soma dos giros.
        repeat(3) { rule.onNodeWithText("Girar").performClick() }
        waitFor("os três giros somados") { page().edit.rotation == 0 && !page().isStale }
        waitFor("voltou a ficar em pé") { page().renderHeight > page().renderWidth }
        shot("22_quatro_giros")
    }

    @Test
    fun marksGoThroughDraftApplyAndUndo() {
        openEditor()
        openMarks()
        rule.onNodeWithContentDescription("Desfazer").assertIsNotEnabled()

        // Passa o corretivo: fica em rascunho, nada aplicado ainda.
        rule.onNodeWithText("Corretivo").performClick()
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.7f, height * 0.4f))
        }
        waitFor("botão aplicar") { exists("Aplicar") }
        shot("30_rascunho_corretivo")

        // Move o rascunho: é o que antes não funcionava.
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.35f), Offset(width * 0.5f, height * 0.6f))
        }
        shot("31_rascunho_movido")

        // Muda o tamanho pelo canto.
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.7f, height * 0.65f), Offset(width * 0.85f, height * 0.72f))
        }
        shot("32_rascunho_maior")

        // Excluir tira o rascunho sem deixar rastro.
        rule.onNodeWithContentDescription("Excluir").performClick()
        waitFor("rascunho excluído") { !exists("Aplicar") }

        // De novo, agora aplicando. Tarja e marca-texto seguem o mesmo caminho.
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.7f, height * 0.4f))
        }
        waitFor("botão aplicar") { exists("Aplicar") }
        rule.onNodeWithText("Aplicar").performClick()
        waitFor("aplicado") { !exists("Aplicar") }
        rule.onNodeWithContentDescription("Desfazer").assertIsEnabled()

        // Com o rascunho aplicado, passar por cima cria outro, sem mover o anterior.
        rule.onNodeWithText("Tarja").performClick()
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.55f), Offset(width * 0.7f, height * 0.62f))
        }
        waitFor("botão aplicar") { exists("Aplicar") }
        rule.onNodeWithText("Aplicar").performClick()
        waitFor("aplicado") { !exists("Aplicar") }

        rule.onNodeWithText("Marca-texto").performClick()
        rule.onNodeWithTag("pagina").performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.7f), Offset(width * 0.7f, height * 0.76f))
        }
        waitFor("botão aplicar") { exists("Aplicar") }

        // Concluir com algo pendente pergunta o que fazer.
        rule.onNodeWithText("Concluir").performClick()
        waitFor("pergunta") { exists("Aplicar o que falta?") }
        shot("33_pendente")
        rule.onNode(hasText("Aplicar") and hasAnyAncestor(isDialog())).performClick()
        waitFor("voltou ao editor") { exists("Girar") }

        val marks = page().edit.marks
        assertEquals(listOf(MarkKind.WHITE, MarkKind.BLACK, MarkKind.HIGHLIGHT), marks.map { it.kind })
        assertEquals("Ordem de aplicação gravada", marks.map { it.order }.sorted(), marks.map { it.order })
        waitFor("OCR refeito sem o texto coberto") { page().hasCurrentOcr }
        waitForRender()
        shot("34_marcas_aplicadas")

        // Sair e voltar: o desfazer continua ativo, um passo por vez.
        openMarks()
        rule.onNodeWithContentDescription("Desfazer").assertIsEnabled().performClick()
        rule.onNodeWithText("Concluir").performClick()
        waitFor("voltou ao editor") { exists("Girar") }
        assertEquals(listOf(MarkKind.WHITE, MarkKind.BLACK), page().edit.marks.map { it.kind })

        openMarks()
        rule.onNodeWithContentDescription("Desfazer").performClick()
        rule.onNodeWithContentDescription("Desfazer").performClick()
        rule.onNodeWithContentDescription("Desfazer").assertIsNotEnabled()
        rule.onNodeWithText("Concluir").performClick()
        waitFor("voltou ao editor") { exists("Girar") }
        assertTrue(page().edit.marks.isEmpty())
    }

    @Test
    fun cropShowsGridAndKeepsCorners() {
        openEditor()
        rule.onNodeWithText("Recortar").performClick()
        waitFor("tela de recorte") { exists("Ajustar bordas") && exists("Aplicar") }
        Thread.sleep(800)
        shot("40_recorte")
        val before = page().edit.quad
        rule.onNodeWithText("Aplicar").performClick()
        waitFor("voltou ao editor") { exists("Girar") }
        assertEquals("Aplicar sem mexer não muda nada", before, page().edit.quad)
    }

    @Test
    fun exportsEveryFormatWithTheAutomaticName() {
        openDocument()
        rule.onNodeWithText("Exportar").performClick()
        waitFor("tela de exportar") { exists("Formato") }
        rule.onNodeWithText("PDF").performClick()
        rule.onNode(hasText("Original")).assertIsSelected()
        rule.onNode(hasText("Sem limite")).assertIsSelected()
        rule.onNode(hasText("A4")).assertIsSelected()
        shot("50_exportar")

        val cases = listOf(
            "PDF" to "pdf",
            "PDF pesquisável" to "pesquisavel",
            "Word" to "word",
            "JPG" to "jpg",
            "PNG" to "png",
            "WebP" to "webp",
            "TIFF" to "tiff",
            "Texto" to "texto",
            "ZIP" to "zip",
        )
        for ((label, folder) in cases) {
            rule.onNodeWithText(label).performClick()
            rule.onNodeWithText("Gerar $label").performClick()
            waitFor("gerar $label", 60_000) { exists("Arquivo pronto") }
            val latest = File(app.cacheDir, Exporter.EXPORTS_DIR).listFiles()!!.maxByOrNull { it.name }!!
            val target = File(output, "exportados/$folder").apply { deleteRecursively(); mkdirs() }
            latest.listFiles()!!.forEach { file ->
                assertTrue("$label vazio", file.length() > 0)
                assertTrue("$label sem o nome automático: ${file.name}", file.name.startsWith("Declaração de residência"))
                file.copyTo(File(target, file.name), overwrite = true)
            }
            if (label == "PDF") shot("51_arquivo_pronto")
            rule.onNode(hasContentDescription("Voltar")).performClick()
            waitFor("voltar para o formulário") { exists("Formato") }
        }
    }

    @Test
    fun selectAllDocuments() {
        val other = File(app.cacheDir, "e2e_foto.jpg")
        val secondId = runBlocking { checkNotNull(app.repo.createDocument(listOf(Uri.fromFile(other))).docId) }
        waitFor("dois documentos com nome") { app.repo.document(secondId)?.name == doc().name }
        rule.onAllNodes(hasText(doc().name)).onFirst().performTouchInput { longClick() }
        waitFor("modo de seleção") { exists("1 selecionado") }

        rule.onNodeWithText("Todos").performClick()
        waitFor("todos selecionados") { exists("2 selecionados") }
        shot("80_todos_selecionados")

        rule.onNodeWithText("Nenhum").performClick()
        waitFor("seleção desfeita") { !exists("selecionado", substring = true) }
        assertEquals("Selecionar não apaga nada", 2, app.repo.documents.value.size)
    }

    @Test
    fun settingsAndThemes() {
        rule.onNodeWithContentDescription("Configurações").performClick()
        waitFor("configurações") { exists("Tema") }
        rule.onNode(hasText("Sistema")).assertIsSelected()
        assertTrue("O quadro de promessas saiu", !exists("Sem anúncios"))
        rule.onNodeWithText("Espaço usado", substring = true).assertExists()
        shot("60_config")
        rule.onNodeWithText("Claro").performClick()
        waitFor("tema claro") { app.settings.themeMode.value == ThemeMode.LIGHT }
        shot("61_tema_claro")
        rule.onNodeWithText("Escuro").performClick()
        waitFor("tema escuro") { app.settings.themeMode.value == ThemeMode.DARK }
        Thread.sleep(1200)
        shot("62_tema_escuro")
    }
}
