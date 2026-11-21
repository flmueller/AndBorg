package de.flokke.borgapp.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.flokke.borgapp.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BorgTheme { AppRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLocal()
    }
}

@Composable
fun BorgTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FCFE0))
        else -> lightColorScheme(primary = Color(0xFF1E4D5C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: Int, val icon: ImageVector) {
    STATUS(R.string.tab_status, Icons.Filled.Home),
    CONFIG(R.string.tab_settings, Icons.Filled.Settings),
    HISTORY(R.string.tab_history, Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val snack by vm.snackbar.collectAsStateWithLifecycle()
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(snack) {
        snack?.let { snackHost.showSnackbar(it); vm.snackbarShown() }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(if (tab == 0) R.string.app_name else Tab.entries[tab].title)) })
                if (busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "$busy …", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(stringResource(t.title)) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
    ) { pad ->
        val m = Modifier.padding(pad).fillMaxSize()
        when (Tab.entries[tab]) {
            Tab.STATUS -> StatusScreen(vm, m, openSettings = { tab = Tab.CONFIG.ordinal })
            Tab.CONFIG -> ConfigScreen(vm, m)
            Tab.HISTORY -> HistoryScreen(vm, m)
        }
    }

    dialog?.let { d ->
        val clipboard = rememberCopy()
        AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = {
                Text(
                    when (d.success) { true -> "✅ "; false -> "⚠️ "; null -> "" } + d.title
                )
            },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(d.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissDialog) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { clipboard(d.title, d.text) }) { Text(stringResource(R.string.copy)) } },
        )
    }
}
