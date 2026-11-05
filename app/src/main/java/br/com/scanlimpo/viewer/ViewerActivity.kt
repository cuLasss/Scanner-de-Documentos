package br.com.scanlimpo.viewer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.scanlimpo.MainActivity
import br.com.scanlimpo.imaging.FileKinds
import br.com.scanlimpo.ui.theme.AppTheme

/** O que abre quando a pessoa escolhe o Scan Limpo no "Abrir com" de outro app. */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null || !FileKinds.isAllowedIncoming(this, uri)) {
            Toast.makeText(this, "Não consegui abrir esse arquivo.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val mime = intent.type
        setContent {
            AppTheme(this) {
                ViewerScreen(
                    uri = uri,
                    hintedMime = mime,
                    onClose = ::finish,
                    onOpenDocument = ::openDocument,
                )
            }
        }
    }

    private fun openDocument(id: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DOCUMENT)
                .putExtra(MainActivity.EXTRA_DOCUMENT_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
