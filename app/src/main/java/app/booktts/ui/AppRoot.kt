package app.booktts.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Minimal back-stack navigation: "library", "reader/<id>", "voices". */
@Composable
fun AppRoot(pendingImport: MutableStateFlow<Uri?>) {
    var stack by rememberSaveable { mutableStateOf(listOf("library")) }
    fun push(route: String) { stack = stack + route }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // A file opened from another app is imported by the library screen, so go there.
    val pending by pendingImport.collectAsState()
    LaunchedEffect(pending) { if (pending != null) stack = listOf("library") }

    val route = stack.last()
    when {
        route == "library" -> LibraryScreen(
            pendingImport = pendingImport,
            onOpen = { push("reader/$it") },
            onVoices = { push("voices") },
        )
        route.startsWith("reader/") -> ReaderScreen(
            bookId = route.removePrefix("reader/"),
            onBack = ::pop,
            onVoices = { push("voices") },
        )
        route == "voices" -> VoicesScreen(onBack = ::pop)
    }
}
