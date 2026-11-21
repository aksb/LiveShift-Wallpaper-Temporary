package jarvay.workpaper

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import jarvay.workpaper.data.preferences.RunningPreferencesKeys
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.data.preferences.SettingsPreferencesRepository
import jarvay.workpaper.data.rule.RuleRepository
import jarvay.workpaper.data.rule.RuleWithRelation
import jarvay.workpaper.data.style.StyleRepository
import jarvay.workpaper.data.wallpaper.Wallpaper
import jarvay.workpaper.data.wallpaper.WallpaperType
import jarvay.workpaper.others.blur
import jarvay.workpaper.others.effect
import jarvay.workpaper.others.noise
import jarvay.workpaper.receiver.RuleReceiver
import jarvay.workpaper.receiver.UpdateActionWidgetReceiver
import jarvay.workpaper.receiver.WallpaperReceiver
import jarvay.workpaper.service.LiveWallpaperService
import jarvay.workpaper.service.WorkpaperService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AlarmType(val value: Int) {
    REPEAT(1), RULE(2)
}

data class NextWallpaper(
    var index: Int,
    var wallpaper: Wallpaper,
    var isManual: Boolean,
)

@Singleton
class Workpaper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var runningPreferencesRepository: RunningPreferencesRepository

    @Inject
    lateinit var settingsPreferencesRepository: SettingsPreferencesRepository

    @Inject
    lateinit var styleRepository: StyleRepository

    val currentRuleId = MutableStateFlow<Long>(-1)
    val nextRuleId = MutableStateFlow<Long>(-1)
    val currentRuleWithRelation = MutableStateFlow<RuleWithRelation?>(null)

    var nextWallpaper: MutableStateFlow<NextWallpaper?> = MutableStateFlow(null)
    var nextWallpaperTime: Long = 0
    var nextRuleTime: Long = 0

    var wallpapers: List<Wallpaper> = emptyList()

    var lastWallpaperWorkerId: UUID? = null

    val imageUri = MutableStateFlow<String?>(null)
    val videoUri = MutableStateFlow<String?>(null)

    val loadingAlbumIdSet = MutableStateFlow<MutableSet<Long>>(mutableSetOf())

    init {
        MainScope().launch {
            currentRuleId.collect {
                currentRuleWithRelation.value = ruleRepository.findRuleById(ruleId = it)
            }
        }
    }

    suspend fun restart() {
        stop()

        start()
    }

    /**
     * @param requestLiveWallpaper 是否需要拉起系统"更换动态壁纸"的确认界面。
     * 只有用户在 App 内手动点击启用/切换时才需要为 true——这一步本质上是
     * "把当前动态壁纸切换成本 App"，安卓出于安全考虑要求用户手动确认。
     * 如果本 App 的动态壁纸此前已经是系统当前壁纸（例如进程被系统重启/杀死后
     * 拉起来做恢复），系统重启后会自动重新绑定同一个 LiveWallpaperService，
     * 完全不需要、也不应该再弹一次确认界面——这里必须传 false，
     * 否则每次进程被系统重建都会自动弹出系统设置界面，需要用户手动点一下才行。
     */
    suspend fun start(requestLiveWallpaper: Boolean = true) {
        val i = Intent(context, WorkpaperService::class.java)
        context.startService(i)

        runningPreferencesRepository.apply {
            update(RunningPreferencesKeys.RUNNING, true)
        }

        if (requestLiveWallpaper &&
            settingsPreferencesRepository.settingsPreferencesFlow.first().useLiveWallpaper
        ) {
            context.startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(
                        context, LiveWallpaperService::class.java
                    )
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    suspend fun stop() {
        currentRuleId.value = -1
        nextRuleId.value = -1

        imageUri.value = null
        videoUri.value = null

        cancelAllAlarm()

        runningPreferencesRepository.apply {
            update(RunningPreferencesKeys.LAST_INDEX, -1)
            update(RunningPreferencesKeys.LAST_WALLPAPER, "")
            update(RunningPreferencesKeys.CURRENT_VIDEO_CONTENT_URI, "")
            update(RunningPreferencesKeys.RUNNING, false)
            update(RunningPreferencesKeys.CURRENT_RULE_ID, -1L)
        }

        nextWallpaper.value = null
        val i = Intent(context, UpdateActionWidgetReceiver::class.java)
        context.sendBroadcast(i)

        val intent = Intent(context, WorkpaperService::class.java)
        context.stopService(intent)
    }

    private fun cancelAllAlarm() {
        AlarmType.entries.forEach {
            cancelAlarm(it)
        }
    }

    private fun getIntent(type: AlarmType): Intent {
        val i = Intent()
        return i.apply {
            when (type) {
                AlarmType.REPEAT -> {
                    i.setClass(context, WallpaperReceiver::class.java)
                }

                AlarmType.RULE -> {
                    i.setClass(context, RuleReceiver::class.java)
                }
            }
        }
    }

    fun getPendingIntent(type: AlarmType): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            type.value,
            getIntent(type = type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAlarm(type: AlarmType) {
        val pendingIntent = getPendingIntent(type)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    suspend fun isRunning(
    ): Boolean {
        return runningPreferencesRepository.runningPreferencesFlow.first().running
    }

    suspend fun getNextWallpaper(): NextWallpaper? {
        return if (nextWallpaper.value == null) generateNextWallpaper() else nextWallpaper.value
    }

    /**
     * "跳转到第几张"：index 为 0-based，越界时返回 false，调用方据此弹提示，
     * 不做自动纠正（越界不 clamp）。
     */
    fun jumpTo(index: Int): Boolean {
        if (index !in wallpapers.indices) return false
        setNextWallpaper(NextWallpaper(index = index, wallpaper = wallpapers[index], isManual = true))
        return true
    }

    fun setNextWallpaper(next: NextWallpaper) {
        if (nextWallpaper.value?.wallpaper == next.wallpaper) return
        nextWallpaper.value = next

        val intent = Intent(context, UpdateActionWidgetReceiver::class.java)
        context.sendBroadcast(intent)
    }

    suspend fun generateNextWallpaper(
        startIndex: Int? = null, isManual: Boolean = false, ruleId: Long? = null
    ): NextWallpaper? {
        val index = startIndex ?: nextWallpaper.value?.index ?: -1
        val tmpRuleId = ruleId ?: this.currentRuleId.value

        if (tmpRuleId < 0) return null

        if (wallpapers.isEmpty()) return null

        var nextIndex = nextIndex(index)
        var wallpaper = wallpapers[nextIndex]

        val useLiveWallpaper =
            settingsPreferencesRepository.settingsPreferencesFlow.first().useLiveWallpaper
        while (!useLiveWallpaper && wallpaper.type == WallpaperType.VIDEO) {
            nextIndex = nextIndex(nextIndex)
            wallpaper = wallpapers[nextIndex]
        }

        return NextWallpaper(
            index = nextIndex,
            wallpaper = wallpapers[nextIndex],
            isManual = isManual,
        )
    }

    /**
     * 与 generateNextWallpaper 对称：生成"上一张"壁纸的预览。
     *
     * 注意：nextWallpaper.value 始终代表"下一次触发换壁纸时将要展示的那一张"，
     * 也就是比当前正在显示的壁纸(lastIndex)要靠前 1 位。所以退回上一张时，
     * 我们需要把基准索引往回退 2 位（退回到"当前正在显示的那一张"的前一张），
     * 而不是简单地对 nextWallpaper 的 index 做 -1。
     */
    suspend fun generatePrevWallpaper(
        startIndex: Int? = null, isManual: Boolean = true, ruleId: Long? = null
    ): NextWallpaper? {
        val tmpRuleId = ruleId ?: this.currentRuleId.value
        if (tmpRuleId < 0) return null
        if (wallpapers.isEmpty()) return null

        // 当前"待展示"的预览索引（即将成为下一张的那个 index）
        val previewIndex = startIndex ?: nextWallpaper.value?.index
        // 当前"正在显示"的索引 = previewIndex - 1（对称于 doWork 里
        // "生成 next 预览时，base 是刚应用的那一张"的关系）
        val currentDisplayedIndex = when {
            previewIndex != null -> prevIndex(previewIndex)
            else -> null
        }

        var index = if (currentDisplayedIndex != null) {
            prevIndex(currentDisplayedIndex)
        } else {
            // 没有内存态可用（例如刚重启），退化为 wallpapers 最后一张
            wallpapers.size - 1
        }

        val useLiveWallpaper =
            settingsPreferencesRepository.settingsPreferencesFlow.first().useLiveWallpaper
        var wallpaper = wallpapers[index]
        var guard = 0
        while (!useLiveWallpaper && wallpaper.type == WallpaperType.VIDEO && guard < wallpapers.size) {
            index = prevIndex(index)
            wallpaper = wallpapers[index]
            guard++
        }

        return NextWallpaper(
            index = index,
            wallpaper = wallpaper,
            isManual = isManual,
        )
    }

    /**
     * 与 nextIndex 对称的"回退一格"。
     *
     * 说明：nextIndex 在越界折返(index+1>=size)时，若规则开启了随机播放会
     * reshuffle 一次列表；这一步是不可逆的（reshuffle 之后原来的顺序就丢失了）。
     * prevIndex 不做 reshuffle，只做索引折返，这意味着：在非随机模式下，
     * "下一张"再"上一张"一定能精确复原；在随机模式下，只要没有跨越"一整轮"
     * 的边界（即没有触发过 reshuffle），也能精确复原，但一旦跨越了 reshuffle
     * 边界，"上一张"回退到的就是新顺序下的上一张，而不是历史上真实播放过的
     * 那一张——这是随机模式下退回上一张的固有局限，建议在 UI 上对随机模式
     * 用户做说明，或者见下面"更彻底的方案"。
     */
    private fun prevIndex(currentIndex: Int): Int {
        return if (currentIndex - 1 < 0) {
            wallpapers.size - 1
        } else {
            currentIndex - 1
        }
    }

    suspend fun handleBitmapStyle(bitmap: Bitmap): Bitmap {
        var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 双击桌面锁定"无滤镜"状态时，直接返回原图，跳过下面所有滤镜处理。
        // 放在这里而不是只在 LiveWallpaperService 里判断，是因为静态壁纸模式
        // （WallpaperWorker）也会调用这个方法，这样两条路径都会遵守锁定状态。
        val running = runningPreferencesRepository.runningPreferencesFlow.first()
        if (running.styleLockedOff) {
            return result
        }

        val settings = settingsPreferencesRepository.settingsPreferencesFlow.first()

        val defaultStyle = styleRepository.findById(settings.defaultStyleId)
        val ruleWithRelation = currentRuleWithRelation.first() ?: return result

        if (!ruleWithRelation.rule.noStyle) {
            val style = defaultStyle ?: ruleWithRelation.style
            style?.let {
                if (style.blurRadius > 0) {
                    result = bitmap.blur(style.blurRadius)
                }
                if (style.noisePercent > 0) {
                    result = bitmap.noise(style.noisePercent)
                }
                result = result.effect(
                    brightness = style.brightness,
                    contrast = style.contrast,
                    saturation = style.saturation
                )
            }
        }

        return result
    }

    private fun nextIndex(currentIndex: Int): Int {
        val ruleWithRelation = runBlocking {
            currentRuleWithRelation.first()
        }

        if (currentIndex + 1 >= wallpapers.size) {
            if (ruleWithRelation?.rule?.random == true) {
                wallpapers = wallpapers.shuffled()
            }
            return 0
        } else {
            return currentIndex + 1
        }
    }
}