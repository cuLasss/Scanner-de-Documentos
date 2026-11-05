package br.com.scanlimpo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import br.com.scanlimpo.imaging.FileKinds
import br.com.scanlimpo.ui.AppRoot
import br.com.scanlimpo.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    private val scanRequested = MutableStateFlow(false)
    private val documentRequested = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme(this) {
                AppRoot(
                    sharedUris = sharedUris,
                    scanRequested = scanRequested,
                    documentRequested = documentRequested,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    sharedUris.value = listOf(it).filter { uri -> FileKinds.isAllowedIncoming(this, uri) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    sharedUris.value = it.filter { uri -> FileKinds.isAllowedIncoming(this, uri) }
                }
            }
            ACTION_SCAN -> scanRequested.value = true
            ACTION_OPEN_DOCUMENT -> documentRequested.value = intent.getStringExtra(EXTRA_DOCUMENT_ID)
        }
    }

    companion object {
        const val ACTION_SCAN = "br.com.scanlimpo.action.SCAN"
        const val ACTION_OPEN_DOCUMENT = "br.com.scanlimpo.action.OPEN_DOCUMENT"
        const val EXTRA_DOCUMENT_ID = "documento"
    }
}
