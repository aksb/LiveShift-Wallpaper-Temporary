package jarvay.workpaper.viewModel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.album.Album
import jarvay.workpaper.data.album.AlbumRepository
import jarvay.workpaper.data.preferences.DEFAULT_SETTINGS
import jarvay.workpaper.data.preferences.SettingsPreferencesKeys
import jarvay.workpaper.data.preferences.SettingsPreferencesRepository
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.data.rule.Rule
import jarvay.workpaper.data.rule.RuleRepository
import jarvay.workpaper.data.style.Style
import jarvay.workpaper.data.style.StyleRepository
import jarvay.workpaper.data.wallpaper.Wallpaper
import jarvay.workpaper.data.wallpaper.WallpaperRepository
import jarvay.workpaper.others.GestureEvent
import jarvay.workpaper.others.STATE_IN_STATED
import jarvay.workpaper.others.SUPPORTED_WALLPAPER_TYPES_PREFIX
import jarvay.workpaper.others.wallpaperType
import jarvay.workpaper.receiver.RuleReceiver
import jarvay.workpaper.receiver.WallpaperReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val WALLPAPER_INSERT_CHUNK_SIZE = 50

/**
 * 简化模式下的核心 ViewModel。
 *
 * 和模板原有的"多相册配多计划配多滤镜"玩法不同，这里全局只维护：
 * - 多个"文件夹"（Album，一个文件夹对应一个相册，可以任意切换哪个在用）
 * - 一条"规则"（Rule，只用来存"定时切换开关+间隔"和"当前在用哪个文件夹"）
 * - 一份"滤镜"（Style，全局生效，通过 settings.defaultStyleId 覆盖掉
 *   Rule 自身的 styleId，见 Workpaper.handleBitmapStyle()）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SimpleWallpaperViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val ruleRepository: RuleRepository,
    private val styleRepository: StyleRepository,
    private val settingsPreferencesRepository: SettingsPreferencesRepository,
    private val runningPreferencesRepository: RunningPreferencesRepository,
    private val workpaper: Workpaper,
) : ViewModel() {

    val albums = albumRepository.allAlbums.stateIn(
        viewModelScope, STATE_IN_STATED, emptyList()
    )

    // 全局只有一条规则，取第一条（也是唯一一条）即可。
    val rule = ruleRepository.allRules.map { it.firstOrNull() }.stateIn(
        viewModelScope, STATE_IN_STATED, null
    )

    val settings = settingsPreferencesRepository.settingsPreferencesFlow.stateIn(
        viewModelScope, STATE_IN_STATED, DEFAULT_SETTINGS
    )

    val style = settingsPreferencesRepository.settingsPreferencesFlow
        .map { it.defaultStyleId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id <= 0) flowOf(null) else (styleRepository.findFlowById(id) ?: flowOf(null))
        }.stateIn(viewModelScope, STATE_IN_STATED, null)

    init {
        ensureDefaults()
    }

    /**
     * 首次启动时（数据库为空）自动建立"默认规则"和"默认滤镜"这两行数据，
     * 并把它们分别设为 forcedUsedRuleId / defaultStyleId，
     * 这样即使不用模板原来的多规则调度逻辑，也能保证这条规则始终生效。
     */
    private fun ensureDefaults() {
        viewModelScope.launch {
            if (ruleRepository.allRules.first().isEmpty()) {
                val ruleId = ruleRepository.insertAndGetId(
                    Rule(
                        changeByTiming = false,
                        albumIds = emptyList(),
                    )
                )
                settingsPreferencesRepository.update(
                    SettingsPreferencesKeys.FORCED_USED_RULE_ID, ruleId
                )
            }

            if (styleRepository.allStyles.first().isEmpty()) {
                val styleId = styleRepository.insertAndGetId(Style(name = "默认"))
                settingsPreferencesRepository.update(
                    SettingsPreferencesKeys.DEFAULT_STYLE_ID, styleId
                )
            }
        }
    }

    /**
     * 选完文件夹后：拿到持久化权限 -> 建一个新相册（一个文件夹对应一个相册）
     * -> 扫描该文件夹下的图片/视频写入 wallpapers 表 -> 自动切换为当前使用的文件夹。
     */
    fun addFolder(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            val name =
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "相册"
            val albumId = albumRepository.insertAndGetId(
                Album(name = name, dirs = listOf(uri.toString()))
            )

            scanAlbum(context, albumId, uri)

            setActiveAlbum(context, albumId)
        }
    }

    private suspend fun scanAlbum(context: Context, albumId: Long, dirUri: Uri) {
        val documentFile = DocumentFile.fromTreeUri(context, dirUri) ?: return
        val wallpapers = mutableListOf<Wallpaper>()
        val scanSubfolders = settingsPreferencesRepository.settingsPreferencesFlow.first().scanSubfolders
        collectWallpapers(documentFile, wallpapers, scanSubfolders)

        wallpapers.chunked(WALLPAPER_INSERT_CHUNK_SIZE).forEach { chunk ->
            wallpaperRepository.insert(chunk.map { it.copy(albumId = albumId) })
        }
    }

    private fun collectWallpapers(
        documentFile: DocumentFile,
        result: MutableList<Wallpaper>,
        scanSubfolders: Boolean,
    ) {
        for (item in documentFile.listFiles()) {
            if (item.isDirectory) {
                if (scanSubfolders) {
                    collectWallpapers(item, result, scanSubfolders)
                }
            } else if (item.isFile) {
                val mimeType = item.type ?: continue
                val supported = SUPPORTED_WALLPAPER_TYPES_PREFIX.any { mimeType.startsWith(it) }
                if (!supported) continue
                result.add(
                    Wallpaper(
                        contentUri = item.uri.toString(),
                        type = wallpaperType(mimeType),
                        ratio = null
                    )
                )
            }
        }
    }

    /**
     * 切换"当前使用的文件夹"。
     * 如果动态壁纸还没启用过，会拉起系统"更换动态壁纸"确认界面（只有这一次）；
     * 如果已经在运行了，只是把规则的 albumIds 换掉，然后直接把这条规则重新推一遍，
     * 不会再弹系统确认界面。
     */
    fun setActiveAlbum(context: Context, albumId: Long) {
        viewModelScope.launch {
            val currentRule = rule.value?.rule ?: return@launch
            ruleRepository.update(currentRule.copy(albumIds = listOf(albumId)))

            val isRunning = runningPreferencesRepository.runningPreferencesFlow.first().running
            if (!isRunning) {
                settingsPreferencesRepository.update(
                    SettingsPreferencesKeys.USE_LIVE_WALLPAPER, true
                )
                workpaper.start(requestLiveWallpaper = true)
            } else {
                val intent = Intent(context, RuleReceiver::class.java).apply {
                    putExtra(RuleReceiver.RULE_ID_KEY, currentRule.ruleId)
                    putExtra(RuleReceiver.IS_RESTORE_KEY, false)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    fun removeAlbum(album: Album) {
        viewModelScope.launch {
            val currentRule = rule.value?.rule
            if (currentRule != null && currentRule.albumIds.contains(album.albumId)) {
                ruleRepository.update(
                    currentRule.copy(albumIds = currentRule.albumIds - album.albumId)
                )
            }
            albumRepository.delete(album)
        }
    }

    fun previous(context: Context) = sendReaderStepBroadcast(context, jarvay.workpaper.service.LiveWallpaperService.READER_STEP_PREV)
    fun next(context: Context) = sendReaderStepBroadcast(context, jarvay.workpaper.service.LiveWallpaperService.READER_STEP_NEXT)

    /**
     * "跳转到第几张"。index 是用户在输入框里填的"第几张"（从 1 开始），
     * 越界（<1 或 > 当前文件夹总数）或者文件夹是空的，都不自动纠正，
     * 而是回调 onOutOfRange 交给界面弹提示。
     */
    fun jumpTo(context: Context, oneBasedIndex: Int, onOutOfRange: () -> Unit) {
        viewModelScope.launch {
            val targetIndex = oneBasedIndex - 1
            val ok = workpaper.jumpTo(targetIndex)
            if (!ok) {
                onOutOfRange()
                return@launch
            }
            sendWallpaperBroadcast(context, WallpaperReceiver.DIRECTION_NEXT)
        }
    }

    private fun sendWallpaperBroadcast(context: Context, direction: String) {
        val intent = Intent(context, WallpaperReceiver::class.java).apply {
            putExtra(WallpaperReceiver.FLAG_DIRECTION, direction)
        }
        context.sendBroadcast(intent)
    }

    /**
     * "上一张/下一张"按钮现在不直接发给 WallpaperReceiver 了，先发这个隐式
     * 广播——如果阅读器模式正在播的这张图缩放超出了屏幕、还没翻到头，正在
     * 渲染的引擎会自己拦下来做"分区翻页"（只挪动当前这张图片的显示位置，
     * 不真的换图）；只有翻到头了，引擎才会自己转发一条 [sendWallpaperBroadcast]
     * 一样的广播，走真正的切图流程。不在阅读器模式、或者图片本来就没超出
     * 屏幕的情况下，引擎会直接原样转发，效果跟以前直接发给 WallpaperReceiver
     * 完全一样。
     */
    private fun sendReaderStepBroadcast(context: Context, direction: String) {
        val intent = Intent(jarvay.workpaper.service.LiveWallpaperService.ACTION_READER_STEP).apply {
            putExtra(jarvay.workpaper.service.LiveWallpaperService.EXTRA_READER_STEP_DIRECTION, direction)
        }
        context.sendBroadcast(intent)
    }

    fun setAutoSwitch(enabled: Boolean) {
        viewModelScope.launch {
            val currentRule = rule.value?.rule ?: return@launch
            ruleRepository.update(currentRule.copy(changeByTiming = enabled))
        }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            val currentRule = rule.value?.rule ?: return@launch
            ruleRepository.update(currentRule.copy(interval = minutes.coerceAtLeast(1)))
        }
    }

    /**
     * 双击手势下拉框："无｜上一张｜下一张｜开启/关闭效果" 四选一。
     */
    fun setDoubleTapEvent(event: GestureEvent) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.DOUBLE_TAP_EVENT,
                event.name
            )
        }
    }

    fun setEnableNotification(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.ENABLE_NOTIFICATION, enabled)
        }
    }

    fun setWallpaperScrollable(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.WALLPAPER_SCROLLABLE, enabled)
        }
    }

    fun setImageTransition(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.IMAGE_TRANSITION, enabled)
        }
    }

    fun setWidgetColorScheme(value: String) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.WIDGET_COLOR_SCHEME, value)
        }
    }

    fun setScaleType(type: jarvay.workpaper.others.WallpaperScaleType) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.SCALE_TYPE, type.name)
        }
    }

    fun setScalePercent(percent: Int) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.SCALE_PERCENT,
                percent.coerceIn(10, 300)
            )
        }
    }

    fun setReaderMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.READER_MODE, enabled)
        }
    }

    fun setReaderUndersizeVerticalAlign(align: jarvay.workpaper.others.ReaderUndersizeVAlign) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_UNDERSIZE_VERTICAL_ALIGN,
                align.name
            )
        }
    }

    fun setReaderSectionPaging(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_SECTION_PAGING,
                enabled
            )
        }
    }

    fun setReaderCarryOverTransform(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_CARRY_OVER_TRANSFORM,
                enabled
            )
        }
    }

    fun setReaderCarryOverPanXMode(mode: jarvay.workpaper.others.ReaderPanXMode) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_CARRY_OVER_PAN_X_MODE,
                mode.name
            )
        }
    }

    fun setReaderCarryOverPanYMode(mode: jarvay.workpaper.others.ReaderPanYMode) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_CARRY_OVER_PAN_Y_MODE,
                mode.name
            )
        }
    }

    fun setReaderDoubleTapReset(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_DOUBLE_TAP_RESET,
                enabled
            )
        }
    }

    fun setReaderReadingDirection(direction: jarvay.workpaper.others.ReaderReadingDirection) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(
                SettingsPreferencesKeys.READER_READING_DIRECTION,
                direction.name
            )
        }
    }

    fun setScanSubfolders(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferencesRepository.update(SettingsPreferencesKeys.SCAN_SUBFOLDERS, enabled)
        }
    }

    fun updateFilter(
        blurRadius: Int? = null,
        brightness: Int? = null,
        contrast: Int? = null,
        saturation: Int? = null,
    ) {
        viewModelScope.launch {
            val current = style.value ?: return@launch
            styleRepository.update(
                current.copy(
                    blurRadius = blurRadius ?: current.blurRadius,
                    brightness = brightness ?: current.brightness,
                    contrast = contrast ?: current.contrast,
                    saturation = saturation ?: current.saturation,
                )
            )
        }
    }
}
