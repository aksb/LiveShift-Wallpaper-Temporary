package jarvay.workpaper

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.compose.WorkpaperApp
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.ui.theme.WorkpaperTheme
import jarvay.workpaper.viewModel.MainActivityViewModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "检查更新/打赏"功能删除后，App 完全不联网，原来监听 APK 下载完成再
 * 拉起系统安装界面的两个 BroadcastReceiver 也一并删除。
 */
@AndroidEntryPoint
@Singleton
class MainActivity : ComponentActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    @Inject
    lateinit var runningPreferencesRepository: RunningPreferencesRepository

    @Inject
    lateinit var workpaper: Workpaper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        viewModel.settings.observe(this) {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.let { manager ->
                manager.appTasks.forEach { task ->
                    task?.setExcludeFromRecents(it.hideInRecentTask)
                }
            }
        }

        viewModel.settings.observe(this) {
            setContent {
                WorkpaperTheme(
                    dynamicColor = it.enableDynamicColor
                ) {
                    WorkpaperApp(mainActivityViewModel = viewModel)
                }
            }
        }
    }
}
