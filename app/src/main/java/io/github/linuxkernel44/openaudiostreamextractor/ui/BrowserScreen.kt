package io.github.linuxkernel44.openaudiostreamextractor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.linuxkernel44.openaudiostreamextractor.MainViewModel
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionOrchestrator
import io.github.linuxkernel44.openaudiostreamextractor.extract.GeckoBridge
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun BrowserScreen(vm: MainViewModel, gecko: GeckoBridge, modifier: Modifier = Modifier, onExtract: (String) -> Unit) {
    var currentUrl by remember { mutableStateOf("") }
    var field by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    val session = gecko.browseSession

    LaunchedEffect(Unit) { gecko.ensureReady() }

    DisposableEffect(session) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null && url != "about:blank") {
                    currentUrl = url
                    field = url
                }
            }

            override fun onCanGoBack(s: GeckoSession, can: Boolean) {
                canGoBack = can
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(s: GeckoSession, p: Int) {
                progress = p
            }
        }
        session.setActive(true)
        onDispose {
            session.setActive(false)
            session.navigationDelegate = null
            session.progressDelegate = null
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.goBack() }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { session.loadUri(ExtractionOrchestrator.normalizeUrl(field)) })
            )
            IconButton(onClick = { if (currentUrl.isNotBlank()) onExtract(currentUrl) }, enabled = currentUrl.isNotBlank()) {
                Icon(Icons.Filled.GraphicEq, "Extract this page")
            }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GeckoView(ctx).also { it.setSession(session) }
            },
            onRelease = { it.releaseSession() }
        )
    }
}
