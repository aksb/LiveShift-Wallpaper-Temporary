package jarvay.workpaper.glance

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.EntryPoints
import jarvay.workpaper.EntryPoint
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.preferences.DEFAULT_SETTINGS
import jarvay.workpaper.service.LiveWallpaperService

/**
 * 原来是一个小组件里左右两个按钮（上一张/下一张），现在拆成两个独立的
 * 1x1 小组件，用户可以分别拖到桌面任意位置、任意组合。两个小组件共用同一套
 * 配色方案（widgetColorScheme），只是各自只显示一个方向的箭头。
 *
 * 没有需要兼容的老用户/老版本小组件，所以这里直接删除了原来的组合款
 * ActionWidget，不做迁移兼容。
 */
private class SingleArrowContent(
    private val label: String,
    private val direction: String,
) {
    @Composable
    fun Provide(workpaper: Workpaper) {
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
                text = label,
                style = TextStyle(
                    color = ColorProvider(colorScheme.textColor),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier
                    // 见 WidgetColorScheme 说明：显式给透明背景，避免系统默认的
                    // 按压高亮残留。
                    .background(Color.Transparent)
                    .clickable {
                        val intent = Intent(LiveWallpaperService.ACTION_READER_STEP).apply {
                            putExtra(LiveWallpaperService.EXTRA_READER_STEP_DIRECTION, direction)
                        }
                        context.sendBroadcast(intent)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

class PrevWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val workpaper =
            EntryPoints.get(context.applicationContext, EntryPoint::class.java).workpaper()

        provideContent {
            GlanceTheme {
                SingleArrowContent(label = "<", direction = LiveWallpaperService.READER_STEP_PREV)
                    .Provide(workpaper)
            }
        }
    }
}

class NextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val workpaper =
            EntryPoints.get(context.applicationContext, EntryPoint::class.java).workpaper()

        provideContent {
            GlanceTheme {
                SingleArrowContent(label = ">", direction = LiveWallpaperService.READER_STEP_NEXT)
                    .Provide(workpaper)
            }
        }
    }
}
