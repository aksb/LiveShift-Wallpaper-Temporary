package jarvay.workpaper.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.preferences.SettingsPreferencesRepository
import jarvay.workpaper.others.NotificationHelper
import jarvay.workpaper.others.addWallpaperActions
import jarvay.workpaper.others.setBigPicture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsPreferencesRepository: SettingsPreferencesRepository

    @Inject
    lateinit var workpaper: Workpaper

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        MainScope().launch {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val settingsPreferences = settingsPreferencesRepository.settingsPreferencesFlow.first()
            val notificationBuilder = notificationHelper.notificationBuilder(
                ongoing = settingsPreferences.enableNotification
            )

            val titleAndText = notificationHelper.titleAndText()
            notificationBuilder.setContentTitle(titleAndText.first)
                .setContentText(titleAndText.second)

            // 按钮跟"缩略图生不生成得出来"彻底解耦：不管下面这一步成不成功，
            // 按钮都要正常显示，用户能一直用"上一张/下一张"。
            notificationBuilder.addWallpaperActions(context)

            workpaper.getNextWallpaper()?.let {
                val bitmapPair = notificationHelper.createPictures(it.wallpaper.contentUri)
                if (bitmapPair != null) {
                    notificationBuilder.setBigPicture(
                        background = bitmapPair.first,
                        bitmap = bitmapPair.second,
                    )
                }
            }

            val notification = notificationBuilder.build()
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_UPDATE = "workpaper_action_notification_update"
    }
}