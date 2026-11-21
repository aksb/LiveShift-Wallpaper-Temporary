package jarvay.workpaper.compose

import android.annotation.SuppressLint
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import jarvay.workpaper.compose.components.LocalMainActivityModel
import jarvay.workpaper.compose.components.LocalSimpleSnackbar
import jarvay.workpaper.compose.components.LocalSnackbarHostState
import jarvay.workpaper.compose.components.SimpleSnackbar
import jarvay.workpaper.compose.home.HomeScreen
import jarvay.workpaper.others.Global
import jarvay.workpaper.viewModel.MainActivityViewModel

/**
 * 没有抽屉菜单、没有导航图——整个 App 就是 HomeScreen 这一个页面。
 * 原来这里的 NavHost/Screen 路由、UpgradeDialog（检查更新弹窗）都已经删除。
 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun WorkpaperApp(mainActivityViewModel: MainActivityViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostState = remember {
        SnackbarHostState()
    }

    val simpleSnackbar = SimpleSnackbar(
        scope = scope,
        hostState = hostState,
        context = context
    )
    Global.workpaperAppScope = scope
    Global.snackbarHostState = hostState

    CompositionLocalProvider(
        LocalMainActivityModel provides mainActivityViewModel,
        LocalSnackbarHostState provides hostState,
        LocalSimpleSnackbar provides simpleSnackbar
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = hostState)
            }
        ) { _ ->
            HomeScreen()
        }
    }
}
