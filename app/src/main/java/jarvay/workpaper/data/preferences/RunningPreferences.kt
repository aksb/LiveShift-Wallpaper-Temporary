package jarvay.workpaper.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

data class RunningPreferences(
    var lastIndex: Int,
    var lastWallpaper: String,
    var running: Boolean,
    var currentVideoContentUri: String,
    var currentRuleId: Long,
    // 双击桌面"锁定无滤镜"状态：true 表示当前处于"无滤镜"锁定态，
    // 会一直保持到用户再次双击才恢复滤镜（而不是切下一张就自动恢复）。
    var styleLockedOff: Boolean,
    // 阅读器模式"跟随上一张"这个模式下用的全局缩放/位置，重启 App 之后
    // 还能接着上次的用；跟每张图各自记忆的那套（存在 Wallpaper 表里）是
    // 两回事，互不影响。
    var readerCarryScale: Float,
    var readerCarryPanX: Float,
    var readerCarryPanY: Float,
)

data object RunningPreferencesKeys {
    val LAST_INDEX = intPreferencesKey("lastIndex")
    val LAST_WALLPAPER = stringPreferencesKey("lastWallpaper")
    val RUNNING = booleanPreferencesKey("running")
    val CURRENT_VIDEO_CONTENT_URI = stringPreferencesKey("currentVideoContentUri")

    // 新增：持久化"当前生效的规则ID"，用于区分"进程重启后恢复同一规则"
    // 和"真正切换到一个新规则"这两种场景。
    val CURRENT_RULE_ID = longPreferencesKey("currentRuleId")

    val STYLE_LOCKED_OFF = booleanPreferencesKey("styleLockedOff")

    val READER_CARRY_SCALE = floatPreferencesKey("readerCarryScale")
    val READER_CARRY_PAN_X = floatPreferencesKey("readerCarryPanX")
    val READER_CARRY_PAN_Y = floatPreferencesKey("readerCarryPanY")
}