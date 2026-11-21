package jarvay.workpaper.glance

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoints
import jarvay.workpaper.EntryPoint
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.preferences.DEFAULT_SETTINGS
import jarvay.workpaper.service.LiveWallpaperService

/**
 * "恢复默认大小和位置"独立 1x1 小组件，跟 PrevWidget/NextWidget 同一套配色，
 * 拖到桌面上跟另外两个拼在一起就是"上一张/恢复默认/下一张"。
 *
 * 跟另外两个不一样的地方：点击发的是一个不带目标组件的隐式广播
 * （LiveWallpaperService.ACTION_RESET_READER_TRANSFORM），只有正在渲染的
 * 动态壁纸引擎实例（如果活着）会动态注册接收并处理——不走"上一张/下一张"
 * 那套 WorkManager 任务队列，因为重置不需要真的切换壁纸。
 */
class ResetReaderWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val workpaper =
            EntryPoints.get(context.applicationContext, EntryPoint::class.java).workpaper()

        provideContent {
            GlanceTheme {
                Content(workpaper)
            }
        }
    }

    @Composable
    private fun Content(workpaper: Workpaper) {
        val context = LocalContext.current

        val settings by workpaper.settingsPreferencesRepository.settingsPreferencesFlow.collectAsState(
            initial = DEFAULT_SETTINGS
        )
        val colorScheme = WidgetColorScheme.fromValue(settings.widgetColorScheme)

        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(colorScheme.containerColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u21bb",
                style = TextStyle(
                    color = ColorProvider(colorScheme.textColor),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier
                    .background(Color.Transparent)
                    .clickable {
                        context.sendBroadcast(
                            Intent(LiveWallpaperService.ACTION_RESET_READER_TRANSFORM)
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
