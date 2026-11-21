package jarvay.workpaper.others

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.documentfile.provider.DocumentFile
import com.blankj.utilcode.util.LogUtils
import jarvay.workpaper.R
import jarvay.workpaper.data.preferences.RunningPreferences
import jarvay.workpaper.data.preferences.RunningPreferencesKeys
import jarvay.workpaper.data.rule.Rule
import jarvay.workpaper.data.rule.RuleWithRelation
import jarvay.workpaper.data.rule.RuleWithRelationToSort
import jarvay.workpaper.data.wallpaper.WallpaperType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar


fun formatTime(hour: Int, minute: Int): String {
    var hourString = hour.toString()
    hourString = if (hourString.length == 1) "0${hourString}" else hourString
    var minuteString = minute.toString()
    minuteString = if (minuteString.length == 1) "0${minuteString}" else minuteString
    return "${hourString}:${minuteString}"
}

fun getCalendarWithRule(rule: Rule, dayOfWeek: Int): Calendar {
    val calendar = Calendar.getInstance()
    return calendar.apply {
        set(Calendar.DAY_OF_WEEK, dayOfWeek)
        set(Calendar.HOUR_OF_DAY, rule.startHour)
        set(Calendar.MINUTE, rule.startMinute)
        set(Calendar.SECOND, 0)
    }
}

fun findRule(
    ruleWithRelationList: List<RuleWithRelation>,
    finder: (List<RuleWithRelationToSort>) -> RuleWithRelationToSort?,
): RuleWithRelationToSort? {
    val rules = ArrayList<RuleWithRelationToSort>()
    ruleWithRelationList.forEach {
        it.rule.days.forEach { day ->
            val minute = day * 24 * 60 + it.rule.startHour * 60 + it.rule.startMinute
            val millis = minute * 60 * 1000
            rules.add(
                RuleWithRelationToSort(
                    ruleWithRelation = it.copy(),
                    sortValue = millis.toLong(),
                    day = day
                )
            )
        }
    }

    return finder(rules)
}

fun currentMillis(): Long {
    val calendar = Calendar.getInstance()
    val minute =
        calendar.get(Calendar.DAY_OF_WEEK) * 24 * 60 + calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(
            Calendar.MINUTE
        )
    val millis =
        minute * 60 * 1000 + calendar.get(Calendar.SECOND) * 1000 + calendar.get(Calendar.MILLISECOND)
    return millis.toLong()
}

fun prevRule(list: List<RuleWithRelation>): RuleWithRelationToSort? {
    return findRule(ruleWithRelationList = list, finder = { l ->
        val sortedList = l.sortedByDescending { it.sortValue }
        val result = sortedList.find {
            it.sortValue <= currentMillis()
        }
        result ?: if (sortedList.isNotEmpty()) sortedList.first() else null
    })
}

fun nextRule(list: List<RuleWithRelation>): RuleWithRelationToSort? {
    val currentMillis = currentMillis()
    return findRule(ruleWithRelationList = list, finder = { l ->
        val sortedList = l.sortedBy { it.sortValue }
        val result = sortedList.find {
            it.sortValue >= currentMillis
        }
        result ?: if (sortedList.isNotEmpty()) sortedList.first() else null
    })
}

fun showToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

fun showToast(context: Context, @StringRes strId: Int) {
    showToast(context, context.getString(strId))
}

fun getScreenSize(): Size {
    val dm = Resources.getSystem().displayMetrics
    return Size(dm.widthPixels, dm.heightPixels)
}

fun getWallpaperSize(): Size {
    val screenSize = getScreenSize()
    val ratio = screenSize.width.toFloat() / screenSize.height.toFloat()
    return if (screenSize.width > MAX_WALLPAPER_WIDTH) {
        Size(MAX_WALLPAPER_WIDTH, (MAX_WALLPAPER_WIDTH / ratio).toInt())
    } else if (screenSize.height > MAX_WALLPAPER_HEIGHT) {
        Size((MAX_WALLPAPER_HEIGHT * ratio).toInt(), MAX_WALLPAPER_HEIGHT)
    } else {
        screenSize
    }
}

fun runningPreferencesFlow(dataStore: DataStore<Preferences>): Flow<RunningPreferences> {
    return dataStore.data.map { preferences ->
        val lastIndex = preferences[RunningPreferencesKeys.LAST_INDEX] ?: -1
        val lastWallpaper = preferences[RunningPreferencesKeys.LAST_WALLPAPER] ?: ""
        val running = preferences[RunningPreferencesKeys.RUNNING] ?: false
        val currentVideoContentUri =
            preferences[RunningPreferencesKeys.CURRENT_VIDEO_CONTENT_URI] ?: ""
        val currentRuleId = preferences[RunningPreferencesKeys.CURRENT_RULE_ID] ?: -1L
        val styleLockedOff = preferences[RunningPreferencesKeys.STYLE_LOCKED_OFF] ?: false
        val readerCarryScale = preferences[RunningPreferencesKeys.READER_CARRY_SCALE] ?: 1f
        val readerCarryPanX = preferences[RunningPreferencesKeys.READER_CARRY_PAN_X] ?: 0f
        val readerCarryPanY = preferences[RunningPreferencesKeys.READER_CARRY_PAN_Y] ?: 0f

        RunningPreferences(
            lastIndex = lastIndex,
            lastWallpaper = lastWallpaper,
            running = running,
            currentVideoContentUri = currentVideoContentUri,
            currentRuleId = currentRuleId,
            styleLockedOff = styleLockedOff,
            readerCarryScale = readerCarryScale,
            readerCarryPanX = readerCarryPanX,
            readerCarryPanY = readerCarryPanY,
        )
    }
}

fun isToday(calendar: Calendar): Boolean {
    val now = Calendar.getInstance()
    return ((calendar[Calendar.YEAR] == now[Calendar.YEAR])
            && (calendar[Calendar.MONTH] == now[Calendar.MONTH])
            && (calendar[Calendar.DAY_OF_MONTH] == now[Calendar.DAY_OF_MONTH]))
}

fun Context.audioManager(): AudioManager {
    return getSystemService(Context.AUDIO_SERVICE) as AudioManager
}

fun wallpaperType(type: String): WallpaperType {
    return if (type.startsWith("video")) {
        WallpaperType.VIDEO
    } else {
        WallpaperType.IMAGE
    }
}

fun Context.getOneWallpaperInDir(uri: Uri): DocumentFile? {
    val documentFile = DocumentFile.fromTreeUri(this, uri) ?: return null
    for (item in documentFile.listFiles()) {
        if (item.isFile) {
            val mimeType = item.type ?: continue
            val supported = SUPPORTED_WALLPAPER_TYPES_PREFIX.any {
                mimeType.startsWith(it)
            }
            if (!supported) continue

            return item
        }
    }

    return null
}