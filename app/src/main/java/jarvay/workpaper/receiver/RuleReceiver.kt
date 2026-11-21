package jarvay.workpaper.receiver

import android.app.AlarmManager
import android.app.Service.ALARM_SERVICE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.blankj.utilcode.util.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.AlarmType
import jarvay.workpaper.NextWallpaper
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.album.AlbumWithWallpapers
import jarvay.workpaper.data.preferences.RunningPreferencesKeys
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.data.rule.Rule
import jarvay.workpaper.data.rule.RuleRepository
import jarvay.workpaper.data.wallpaper.Wallpaper
import jarvay.workpaper.others.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RuleReceiver : BroadcastReceiver() {
    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var runningPreferencesRepository: RunningPreferencesRepository

    @Inject
    lateinit var workpaper: Workpaper

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val ruleId = intent.getLongExtra(RULE_ID_KEY, -1)
        // 只有 WorkpaperService 在"冷启动/进程刚被系统拉起"时才会带上这个标记，
        // 代表这次是"恢复上次状态"而不是"真正切换到一个新规则"。
        // 通过 NextRuleReceiver（按时间表调度）或用户在规则列表里手动点击切换的
        // 情况，都不会带这个标记，行为保持和以前一样：从第一张开始。
        val isRestore = intent.getBooleanExtra(IS_RESTORE_KEY, false)

        val ruleWithRelation = ruleRepository.findRuleById(ruleId)

        if (ruleWithRelation == null) {
            LogUtils.i(LOG_TAG, "Can not find rule where id=$ruleId")
            return
        }

        workpaper.currentRuleId.value = ruleId

        workpaper.cancelAlarm(type = AlarmType.REPEAT)

        workpaper.wallpapers =
            ruleWithRelation.albums.fold<AlbumWithWallpapers, MutableList<Wallpaper>>(
                mutableListOf()
            ) { acc, album ->
                acc.addAll(album.wallpapers.map { it })
                acc
            }.apply {
                if (ruleWithRelation.rule.random) shuffle()
            }

        MainScope().launch(Dispatchers.IO) {
            val running = runningPreferencesRepository.runningPreferencesFlow.first()
            val sameRuleAsBefore = running.currentRuleId == ruleId

            // 只有"标记为恢复" + "上次记录的正是这个规则" + "能在当前壁纸列表里
            // 找到上次显示的那个文件" 三个条件都满足时，才尝试恢复到原来那张壁纸；
            // 否则一律按老逻辑，从第一张（index -1 -> 0）开始。
            val restoredIndex = if (isRestore && sameRuleAsBefore && running.lastWallpaper.isNotEmpty()) {
                workpaper.wallpapers.indexOfFirst { it.contentUri == running.lastWallpaper }
                    .takeIf { it >= 0 }
            } else null

            runningPreferencesRepository.update(RunningPreferencesKeys.CURRENT_RULE_ID, ruleId)

            if (restoredIndex != null) {
                sendRestoreWallpaperBroadcast(context, restoredIndex)
            } else {
                runningPreferencesRepository.update(RunningPreferencesKeys.LAST_INDEX, -1)
                sendWallpaperBroadcast(context)
            }

            if (ruleWithRelation.rule.changeByTiming) {
                startRepeatAlarm(context, ruleWithRelation.rule)
            }
        }

        val i = Intent(context, NextRuleReceiver::class.java)
        context.sendBroadcast(i)
    }

    private suspend fun sendWallpaperBroadcast(context: Context) {
        val i = Intent(context, WallpaperReceiver::class.java)
        val nextWallpaper = workpaper.generateNextWallpaper()
        if (nextWallpaper != null) {
            workpaper.setNextWallpaper(nextWallpaper)
        }
        context.sendBroadcast(i)
    }

    /**
     * 恢复场景专用：直接把"待展示"的壁纸钉死为重启前正在显示的那一张
     * (index = restoredIndex)，而不像 sendWallpaperBroadcast 那样往前推进一格，
     * 这样 WallpaperWorker 应用之后，屏幕上显示的还是重启前的那张壁纸。
     */
    private suspend fun sendRestoreWallpaperBroadcast(context: Context, restoredIndex: Int) {
        val wallpaper = workpaper.wallpapers.getOrNull(restoredIndex) ?: run {
            sendWallpaperBroadcast(context)
            return
        }

        workpaper.setNextWallpaper(
            NextWallpaper(
                index = restoredIndex,
                wallpaper = wallpaper,
                isManual = false
            )
        )

        val i = Intent(context, WallpaperReceiver::class.java)
        context.sendBroadcast(i)
    }

    private fun startRepeatAlarm(context: Context, rule: Rule) {
        val pendingIntent = workpaper.getPendingIntent(AlarmType.REPEAT)

        val interval = rule.interval * 60 * 1000
        val alarmManager: AlarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + interval,
                interval.toLong(),
                pendingIntent
            )
            LogUtils.i(LOG_TAG, "startRepeatAlarm")
        } catch (e: SecurityException) {
            LogUtils.e(LOG_TAG, "Can not set wallpaper alarm", e.toString())
        }
    }

    companion object {
        const val RULE_ID_KEY = "ruleId"
        const val IS_RESTORE_KEY = "isRestore"
    }
}