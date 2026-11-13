package app.shilex.android.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * A live preview of a page the assistant built (agent mode). The page runs in a WebView that
 * can't read the phone's files or load anything from the network.
 */
class SitePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Preview" }
        val html = runCatching { File(path).readText() }.getOrDefault("<p style='color:white'>Couldn't open the preview.</p>")
        setContent {
            ShilexTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                        TextButton(onClick = { shareCode(html) }) { Text("Share code") }
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                loadDataWithBaseURL("https://preview.local/", html, "text/html", "utf-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }

    private fun shareCode(html: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, html)
        startActivity(Intent.createChooser(send, null))
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }
}
