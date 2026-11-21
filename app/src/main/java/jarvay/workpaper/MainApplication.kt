package jarvay.workpaper

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.blankj.utilcode.util.LogUtils
import dagger.hilt.android.HiltAndroidApp
import jarvay.workpaper.data.preferences.RunningPreferencesKeys
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.data.preferences.SettingsPreferencesKeys
import jarvay.workpaper.data.preferences.SettingsPreferencesRepository
import jarvay.workpaper.receiver.UnlockReceiver
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject


@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var runningPreferencesRepository: RunningPreferencesRepository

    @Inject
    lateinit var settingsPreferencesRepository: SettingsPreferencesRepository

    @Inject
    lateinit var workpaper: Workpaper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    private fun initLogUtils() {
        LogUtils.getConfig().apply {
            saveDays = 7
            setConsoleSwitch(true)
            isLog2FileSwitch = runBlocking {
                settingsPreferencesRepository.settingsPreferencesFlow.first().enableLog
            }
        }
    }

    /**
     * "以动态壁纸模式运行"开关已经删除，默认直接当成开启。这里唯一还要处理的
     * 是真的不支持 OpenGL ES 2.0 的设备（正常在用的手机基本不存在这种情况，
     * 这是十几年前遗留的防御性判断）：检测到不支持时自动把 useLiveWallpaper
     * 改回 false（退回静态壁纸模式），并提示一次。
     */
    private fun checkLiveWallpaperSupport() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        val supportsEs2 = configInfo.reqGlEsVersion >= 0x20000

        if (!supportsEs2) {
            MainScope().launch {
                settingsPreferencesRepository.update(
                    SettingsPreferencesKeys.USE_LIVE_WALLPAPER, false
                )
            }
            Toast.makeText(
                this,
                getString(R.string.settings_opengles2_not_supported),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate() {
        super.onCreate()

        checkLiveWallpaperSupport()

        MainScope().launch {
            settingsPreferencesRepository.settingsPreferencesFlow.collect {
                LogUtils.getConfig().apply {
                    isLog2FileSwitch = it.enableLog
                }
            }
        }

        initLogUtils()

        val unlockReceiver = UnlockReceiver()
        val unlockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(unlockReceiver, unlockFilter)

        MainScope().launch {
            val runningPreferences = runningPreferencesRepository.runningPreferencesFlow.first()
            val running = runningPreferences.running
            if (running) {
                // 进程是刚被系统重新拉起的（开机自启动 / 后台被杀后复活），
                // Workpaper 单例里 videoUri 等内存状态全部是空的，LiveWallpaperService
                // 的引擎也是全新创建、还没有播放任何视频。
                // 但 CURRENT_VIDEO_CONTENT_URI 这个持久化字段还残留着重启前的值，
                // WallpaperWorker 里"内容没变就跳过"的去重逻辑会因此误判为
                // "已经在播放了，不用管"，导致视频壁纸永远不会被真正推送给引擎去播放。
                // 这里先清空它，确保恢复流程一定会把视频重新推送一次。
                runningPreferencesRepository.update(
                    RunningPreferencesKeys.CURRENT_VIDEO_CONTENT_URI,
                    ""
                )
                // 这里是自动恢复，不是用户手动操作，不能再弹系统"更换动态壁纸"确认界面
                workpaper.start(requestLiveWallpaper = false)
            }
        }
    }
}
