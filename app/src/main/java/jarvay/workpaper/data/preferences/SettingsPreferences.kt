package jarvay.workpaper.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import jarvay.workpaper.others.GestureEvent
import jarvay.workpaper.others.ReaderPanXMode
import jarvay.workpaper.others.ReaderPanYMode
import jarvay.workpaper.others.ReaderReadingDirection
import jarvay.workpaper.others.ReaderUndersizeVAlign
import jarvay.workpaper.others.WallpaperScaleType

data class SettingsPreferences(
    var startWithPrevRule: Boolean,
    var enableNotification: Boolean,
    var notificationOngoing: Boolean,
    var alsoSetLockWallpaper: Boolean,
    var hideInRecentTask: Boolean,
    var enableDynamicColor: Boolean,
    var disableWhenPlayingAudio: Boolean,
    var useLiveWallpaper: Boolean,
    var videoResetProgressOnScreenOff: Boolean,
    var defaultStyleId: Long,
    var forcedUsedRuleId: Long,
    var doubleTapEvent: String,
    var enableLog: Boolean,
    var wallpaperScrollable: Boolean,
    var imageTransition: Boolean,
    var widgetColorScheme: String,
    // 全局缩放模式：按高度/按宽度/适合屏幕/固定百分比，图片和视频共用。
    var scaleType: String,
    // 只在 scaleType=FIXED_PERCENTAGE 时生效，10~300。
    var scalePercent: Int,
    // 阅读器模式：开启后双指缩放/拖动壁纸，双击复位到"适合屏幕"，
    // 每张图的缩放/位置会记住。
    var readerMode: Boolean,
    // 一张图在*当前缩放下*没有超出屏幕高度时（不管有没有开"跟随上一张"，
    // 也不管是刚换图、翻页换图还是双击复位触发的），纵向怎么摆：
    // CENTER（默认，等于以前唯一的行为）=居中；TOP/BOTTOM=贴上/贴下边缘
    // （比如漫画图通常没有屏幕高，想贴顶部躲开桌面底部的 dock 栏）。
    // 跟下面的 readerCarryOverPanYMode（管的是*超屏*之后怎么贴边）完全是
    // 两码事，不要求 readerCarryOverTransform 开着才生效。
    var readerUndersizeVerticalAlign: String,
    // true（默认，等于以前唯一的行为）= 阅读器模式下，图片缩放超出屏幕时，
    // 点上一张/下一张先按屏幕大小分区翻页，翻完当前图片才真的换图；
    // false = 不管有没有超屏，点上一张/下一张都直接换成真正的下一张/上一张
    // 图片（跳过分区翻页这一步），对应这个功能加之前更早期版本的行为。
    var readerSectionPaging: Boolean,
    // false（默认）= 每张图各自单独记忆缩放/位置，没记录过的用 1/0/0；
    // true = 不分别记忆，统一用"当前正在用的这个值"，不管切到哪张图
    // （不分上一张/下一张）都直接套用，双击/恢复默认清零的也是这个全局值。
    var readerCarryOverTransform: Boolean,
    // 只在 readerCarryOverTransform=true 时生效，管横向位置(panX)怎么带到
    // 下一张：FOLLOW（默认）=位置原样带过去；LEFT/RIGHT=改成图片贴左/贴右
    // 边缘（图片没超屏幕宽度时直接归零居中），漫画阅读器常见用法。
    var readerCarryOverPanXMode: String,
    // 同上，管纵向位置(panY)，TOP/BOTTOM=贴上/贴下边缘。
    var readerCarryOverPanYMode: String,
    // true（默认，等于以前唯一的行为）= 阅读器模式下双击图片会复位缩放/位置；
    // false = 双击不再做任何事，需要用"恢复默认"按钮或桌面小组件才能复位。
    var readerDoubleTapReset: Boolean,
    // 阅读器模式"分区翻页"用的阅读方向，跟上面 readerCarryOverPanXMode/
    // readerCarryOverPanYMode（"跟随上一张"下面那个贴边设置）是完全独立的
    // 两码事，互不影响，也不要求"跟随上一张"开着才生效。
    var readerReadingDirection: String,
    // 全局设置：选择/重新扫描文件夹时是否递归扫描子文件夹。
    // true（默认，等于以前唯一的行为）= 递归扫描所有子文件夹；
    // false = 只扫描当前这一层，不进子文件夹。
    // 只影响以后的扫描操作，不会去清理已经入库的存量壁纸。
    var scanSubfolders: Boolean,
) {

    override fun equals(other: Any?): Boolean {
        return hashCode() == other.hashCode()
    }

    override fun hashCode(): Int {
        var result = startWithPrevRule.hashCode()
        result = 31 * result + enableNotification.hashCode()
        result = 31 * result + notificationOngoing.hashCode()
        result = 31 * result + alsoSetLockWallpaper.hashCode()
        result = 31 * result + hideInRecentTask.hashCode()
        result = 31 * result + enableDynamicColor.hashCode()
        result = 31 * result + disableWhenPlayingAudio.hashCode()
        result = 31 * result + useLiveWallpaper.hashCode()
        result = 31 * result + videoResetProgressOnScreenOff.hashCode()
        result = 31 * result + defaultStyleId.hashCode()
        result = 31 * result + forcedUsedRuleId.hashCode()
        result = 31 * result + doubleTapEvent.hashCode()
        result = 31 * result + enableLog.hashCode()
        result = 31 * result + wallpaperScrollable.hashCode()
        result = 31 * result + imageTransition.hashCode()
        result = 31 * result + widgetColorScheme.hashCode()
        result = 31 * result + scaleType.hashCode()
        result = 31 * result + scalePercent.hashCode()
        result = 31 * result + readerMode.hashCode()
        result = 31 * result + readerUndersizeVerticalAlign.hashCode()
        result = 31 * result + readerSectionPaging.hashCode()
        result = 31 * result + readerCarryOverTransform.hashCode()
        result = 31 * result + readerCarryOverPanXMode.hashCode()
        result = 31 * result + readerCarryOverPanYMode.hashCode()
        result = 31 * result + readerDoubleTapReset.hashCode()
        result = 31 * result + readerReadingDirection.hashCode()
        result = 31 * result + scanSubfolders.hashCode()
        return result
    }
}

data object SettingsPreferencesKeys {
    val START_WITH_PREV_RULE = booleanPreferencesKey("startWithPrevRule")
    val ENABLE_NOTIFICATION = booleanPreferencesKey("enableNotification")
    val NOTIFICATION_ONGOING = booleanPreferencesKey("notificationOngoing")
    val ALSO_SET_LOCK_WALLPAPER = booleanPreferencesKey("alsoSetLockWallpaper")
    val HIDE_IN_RECENT_TASK = booleanPreferencesKey("hideInRecentTask")
    val ENABLE_DYNAMIC_COLOR = booleanPreferencesKey("enableDynamicColor")
    val DISABLE_WHEN_PLAYING_AUDIO = booleanPreferencesKey("disableWhenPlayingAudio")
    val USE_LIVE_WALLPAPER = booleanPreferencesKey("useLiveWallpaper")
    val VIDEO_RESET_PROGRESS_ON_SCREEN_OFF = booleanPreferencesKey("videoResetProgressOnScreenOff")
    val DEFAULT_STYLE_ID = longPreferencesKey("defaultStyleId")
    val FORCED_USED_RULE_ID = longPreferencesKey("forcedUsedRuleId")
    val DOUBLE_TAP_EVENT = stringPreferencesKey("doubleTapEvent")
    val ENABLE_LOG = booleanPreferencesKey("enableLog")
    val WALLPAPER_SCROLLABLE = booleanPreferencesKey("wallpaperScrollable")
    val IMAGE_TRANSITION = booleanPreferencesKey("imageTransition")
    val WIDGET_COLOR_SCHEME = stringPreferencesKey("widgetColorScheme")
    val SCALE_TYPE = stringPreferencesKey("scaleType")
    val SCALE_PERCENT = intPreferencesKey("scalePercent")
    val READER_MODE = booleanPreferencesKey("readerMode")
    val READER_UNDERSIZE_VERTICAL_ALIGN = stringPreferencesKey("readerUndersizeVerticalAlign")
    val READER_SECTION_PAGING = booleanPreferencesKey("readerSectionPaging")
    val READER_CARRY_OVER_TRANSFORM = booleanPreferencesKey("readerCarryOverTransform")
    val READER_CARRY_OVER_PAN_X_MODE = stringPreferencesKey("readerCarryOverPanXMode")
    val READER_CARRY_OVER_PAN_Y_MODE = stringPreferencesKey("readerCarryOverPanYMode")
    val READER_DOUBLE_TAP_RESET = booleanPreferencesKey("readerDoubleTapReset")
    val READER_READING_DIRECTION = stringPreferencesKey("readerReadingDirection")
    val SCAN_SUBFOLDERS = booleanPreferencesKey("scanSubfolders")
}

val DEFAULT_SETTINGS =
    SettingsPreferences(
        startWithPrevRule = true,
        enableNotification = false,
        notificationOngoing = false,
        alsoSetLockWallpaper = false,
        hideInRecentTask = false,
        enableDynamicColor = false,
        disableWhenPlayingAudio = false,
        // 去掉了"以动态壁纸模式运行"开关，默认直接启用；只有设备真的不支持
        // OpenGL ES 2.0 时，MainApplication 启动时的检测会把它自动改成 false
        // 并提示一次，代码见 MainApplication.checkLiveWallpaperSupport()。
        useLiveWallpaper = true,
        // "锁屏关闭恢复进度"开关已删除，固定为不恢复。
        videoResetProgressOnScreenOff = false,
        defaultStyleId = -1,
        forcedUsedRuleId = -1,
        doubleTapEvent = GestureEvent.CHANGE_WALLPAPER_NEXT.name,
        enableLog = false,
        wallpaperScrollable = false,
        imageTransition = false,
        widgetColorScheme = jarvay.workpaper.glance.WidgetColorScheme.DEFAULT.value,
        // 默认值 = 以前唯一的效果（铺满裁剪，100%），保证老用户/新装用户
        // 不设置的话看到的效果和以前完全一样。
        scaleType = WallpaperScaleType.FIXED_PERCENTAGE.name,
        scalePercent = 100,
        readerMode = false,
        // 默认居中 = 和以前唯一的行为保持一致，老用户升级不会有任何变化。
        readerUndersizeVerticalAlign = ReaderUndersizeVAlign.CENTER.name,
        // 默认开启 = 和以前唯一的行为保持一致（超屏时先分区翻页）。
        readerSectionPaging = true,
        readerCarryOverTransform = false,
        readerCarryOverPanXMode = ReaderPanXMode.FOLLOW.name,
        readerCarryOverPanYMode = ReaderPanYMode.FOLLOW.name,
        // 默认开启 = 和以前唯一的行为保持一致（双击复位）。
        readerDoubleTapReset = true,
        readerReadingDirection = ReaderReadingDirection.LEFT_TO_RIGHT.name,
        // 默认开启 = 和以前唯一的行为保持一致（无条件递归子文件夹）。
        scanSubfolders = true,
    )