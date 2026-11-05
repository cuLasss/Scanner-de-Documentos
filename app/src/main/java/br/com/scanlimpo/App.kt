package br.com.scanlimpo

import android.app.Application
import android.content.Context
import br.com.scanlimpo.data.DocumentRepository
import br.com.scanlimpo.data.Settings
import br.com.scanlimpo.data.SignatureStore
import br.com.scanlimpo.export.Exporter
import br.com.scanlimpo.ocr.OcrCoordinator
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application(), SingletonImageLoader.Factory {
    /** Vive enquanto o app viver: renderização e OCR continuam mesmo trocando de tela. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: Settings
        private set
    lateinit var repo: DocumentRepository
        private set
    lateinit var ocr: OcrCoordinator
        private set
    lateinit var exporter: Exporter
        private set
    lateinit var signatures: SignatureStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = DocumentRepository(this, scope, settings)
        ocr = OcrCoordinator(repo, settings, scope)
        repo.onContentChanged = ocr::scheduleBackground
        exporter = Exporter(this, repo, ocr)
        signatures = SignatureStore(this)
        repo.start()
        scope.launch(Dispatchers.IO) { Exporter.cleanOldExports(this@App) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(120).build()
}

val Context.app: App get() = applicationContext as App
