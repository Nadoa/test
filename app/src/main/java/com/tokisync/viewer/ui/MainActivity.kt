package com.tokisync.viewer.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.tokisync.viewer.ui.screens.EpisodesScreen
import com.tokisync.viewer.ui.screens.LibraryScreen
import com.tokisync.viewer.ui.screens.SettingsScreen
import com.tokisync.viewer.ui.reader.ReaderScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TokiTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (vm.onVolumeKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 볼륨키를 페이지 넘김에 쓰는 동안 시스템 볼륨 UI가 뜨지 않게 key up 도 소비
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            vm.backStack.value.lastOrNull() is Screen.Reader && vm.settings.viewer.value.volumeKeys
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun TokiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8AB4F8),
            secondary = Color(0xFFA8C7FA),
            background = Color(0xFF121212),
            surface = Color(0xFF121212),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2F5FB3),
            secondary = Color(0xFF4A6FA5),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val stack by vm.backStack.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    BackHandler(enabled = stack.size > 1) { vm.back() }

    when (val screen = stack.last()) {
        Screen.Settings -> SettingsScreen(vm, snackbar, canGoBack = stack.size > 1)
        Screen.Library -> LibraryScreen(vm, snackbar)
        is Screen.Episodes -> EpisodesScreen(vm, screen.series, snackbar)
        is Screen.Reader -> ReaderScreen(vm, snackbar)
    }
}
