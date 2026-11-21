package jarvay.workpaper.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.blankj.utilcode.util.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.Workpaper
import jarvay.workpaper.others.LOG_TAG
import jarvay.workpaper.worker.WallpaperWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class WallpaperReceiver : BroadcastReceiver() {
    @Inject
    lateinit var workpaper: Workpaper

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        MainScope().launch {
            // direction 非空时，代表这是"桌面快捷方式"或"通知栏按钮"发来的、
            // 需要立即生效的明确指令（下一张/上一张）。
            //
            // 注意：WallpaperWorker.doWork() 自己就会维护"预览状态永远比当前
            // 显示的壁纸领先 1 步"这个关系——它应用完 getNextWallpaper() 之后，
            // 会自动再调一次 generateNextWallpaper() 生成下一次待命的预览。
            // 所以"下一张"这里只需要复用当前已经待命的预览（getNextWallpaper），
            // 交给 doWork() 自己去推进，不能在这里再手动 generateNextWallpaper()
            // 一次——那样会导致应用的时候多推进一步，变成"跳过一张"。
            // "上一张"则不同：需要主动算出目标（往回退），所以用
            // generatePrevWallpaper()，doWork() 事后再往前推 1 步算出的新预览
            // 刚好落回正确位置，是对称的。
            val direction = intent.getStringExtra(FLAG_DIRECTION)
            val isChangeByManual = intent.getBooleanExtra(FLAG_CHANGE_BY_MANUAL, false)

            if (direction == DIRECTION_PREV) {
                val next = workpaper.generatePrevWallpaper()
                if (next == null) {
                    LogUtils.w(LOG_TAG, "Prev wallpaper is null")
                    return@launch
                }
                workpaper.setNextWallpaper(next.copy(isManual = true))
            } else if (direction == DIRECTION_NEXT || isChangeByManual) {
                val next = workpaper.getNextWallpaper()
                if (next == null) {
                    LogUtils.w(LOG_TAG, "Next wallpaper is null")
                    return@launch
                }
                workpaper.setNextWallpaper(next.copy(isManual = true))
            }

            val workManager = WorkManager.getInstance(context)
            if (workpaper.lastWallpaperWorkerId != null) {
                val lastWorkInfo = runBlocking {
                    workManager.getWorkInfoByIdFlow(workpaper.lastWallpaperWorkerId!!).first()
                }
                if (!lastWorkInfo.state.isFinished) {
                    LogUtils.i(LOG_TAG, "Last work not finished, skip")
                    return@launch
                }
            }

            val wallpaperWorkRequest: WorkRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .build()
            workpaper.lastWallpaperWorkerId = wallpaperWorkRequest.id
            WorkManager.getInstance(context).enqueue(wallpaperWorkRequest)
            LogUtils.i(LOG_TAG, "WallpaperWork enqueue")
        }
    }

    companion object {
        const val FLAG_CHANGE_BY_MANUAL = "flagChangeByManual"
        const val FLAG_DIRECTION = "flagDirection"
        const val DIRECTION_NEXT = "next"
        const val DIRECTION_PREV = "prev"
    }
}