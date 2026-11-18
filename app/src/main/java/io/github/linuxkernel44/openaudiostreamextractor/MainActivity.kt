package io.github.linuxkernel44.openaudiostreamextractor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.linuxkernel44.openaudiostreamextractor.ui.AppTheme
import io.github.linuxkernel44.openaudiostreamextractor.ui.BrowserScreen
import io.github.linuxkernel44.openaudiostreamextractor.ui.ExtractScreen
import io.github.linuxkernel44.openaudiostreamextractor.ui.LibraryScreen
import io.github.linuxkernel44.openaudiostreamextractor.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val shareTick = androidx.compose.runtime.mutableIntStateOf(0)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(vm.message) {
                    vm.message?.let {
                        snackbar.showSnackbar(it)
                        vm.message = null
                    }
                }
                LaunchedEffect(shareTick.intValue) {
                    if (shareTick.intValue > 0) tab = 0
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(
                                Icons.Filled.Search to "Extract",
                                Icons.Filled.Language to "Browser",
                                Icons.Filled.LibraryMusic to "Library",
                                Icons.Filled.Settings to "Settings"
                            )
                            items.forEachIndexed { i, (icon, label) ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    }
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> ExtractScreen(vm, m)
                        1 -> BrowserScreen(vm, (application as OaseApp).container.gecko, m) { url ->
                            vm.extract(url)
                            tab = 0
                        }
                        2 -> LibraryScreen(vm, m)
                        else -> SettingsScreen(vm, m)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
            if (url.isNotBlank()) {
                vm.urlInput = url
                vm.extract(url)
                shareTick.intValue++
            }
        }
    }
}
