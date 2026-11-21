package jarvay.workpaper.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settingsPreferencesFlow: Flow<SettingsPreferences> =
        dataStore.data.map { preferences ->
            SettingsPreferences(
                startWithPrevRule = preferences[SettingsPreferencesKeys.START_WITH_PREV_RULE]
                    ?: DEFAULT_SETTINGS.startWithPrevRule,
                enableNotification = preferences[SettingsPreferencesKeys.ENABLE_NOTIFICATION]
                    ?: DEFAULT_SETTINGS.enableNotification,
                notificationOngoing = preferences[SettingsPreferencesKeys.NOTIFICATION_ONGOING]
                    ?: DEFAULT_SETTINGS.notificationOngoing,
                alsoSetLockWallpaper = preferences[SettingsPreferencesKeys.ALSO_SET_LOCK_WALLPAPER]
                    ?: DEFAULT_SETTINGS.alsoSetLockWallpaper,
                hideInRecentTask = preferences[SettingsPreferencesKeys.HIDE_IN_RECENT_TASK]
                    ?: DEFAULT_SETTINGS.hideInRecentTask,
                enableDynamicColor = preferences[SettingsPreferencesKeys.ENABLE_DYNAMIC_COLOR]
                    ?: DEFAULT_SETTINGS.enableDynamicColor,
                disableWhenPlayingAudio = preferences[SettingsPreferencesKeys.DISABLE_WHEN_PLAYING_AUDIO]
                    ?: DEFAULT_SETTINGS.disableWhenPlayingAudio,
                useLiveWallpaper = preferences[SettingsPreferencesKeys.USE_LIVE_WALLPAPER]
                    ?: DEFAULT_SETTINGS.useLiveWallpaper,
                videoResetProgressOnScreenOff = preferences[SettingsPreferencesKeys.VIDEO_RESET_PROGRESS_ON_SCREEN_OFF]
                    ?: DEFAULT_SETTINGS.videoResetProgressOnScreenOff,
                defaultStyleId = preferences[SettingsPreferencesKeys.DEFAULT_STYLE_ID]
                    ?: DEFAULT_SETTINGS.defaultStyleId,
                forcedUsedRuleId = preferences[SettingsPreferencesKeys.FORCED_USED_RULE_ID]
                    ?: DEFAULT_SETTINGS.forcedUsedRuleId,
                doubleTapEvent = preferences[SettingsPreferencesKeys.DOUBLE_TAP_EVENT]
                    ?: DEFAULT_SETTINGS.doubleTapEvent,
                enableLog = preferences[SettingsPreferencesKeys.ENABLE_LOG]
                    ?: DEFAULT_SETTINGS.enableLog,
                wallpaperScrollable = preferences[SettingsPreferencesKeys.WALLPAPER_SCROLLABLE]
                    ?: DEFAULT_SETTINGS.wallpaperScrollable,
                imageTransition = preferences[SettingsPreferencesKeys.IMAGE_TRANSITION]
                    ?: DEFAULT_SETTINGS.imageTransition,
                widgetColorScheme = preferences[SettingsPreferencesKeys.WIDGET_COLOR_SCHEME]
                    ?: DEFAULT_SETTINGS.widgetColorScheme,
                scaleType = preferences[SettingsPreferencesKeys.SCALE_TYPE]
                    ?: DEFAULT_SETTINGS.scaleType,
                scalePercent = preferences[SettingsPreferencesKeys.SCALE_PERCENT]
                    ?: DEFAULT_SETTINGS.scalePercent,
                readerMode = preferences[SettingsPreferencesKeys.READER_MODE]
                    ?: DEFAULT_SETTINGS.readerMode,
                readerUndersizeVerticalAlign = preferences[SettingsPreferencesKeys.READER_UNDERSIZE_VERTICAL_ALIGN]
                    ?: DEFAULT_SETTINGS.readerUndersizeVerticalAlign,
                readerSectionPaging = preferences[SettingsPreferencesKeys.READER_SECTION_PAGING]
                    ?: DEFAULT_SETTINGS.readerSectionPaging,
                readerCarryOverTransform = preferences[SettingsPreferencesKeys.READER_CARRY_OVER_TRANSFORM]
                    ?: DEFAULT_SETTINGS.readerCarryOverTransform,
                readerCarryOverPanXMode = preferences[SettingsPreferencesKeys.READER_CARRY_OVER_PAN_X_MODE]
                    ?: DEFAULT_SETTINGS.readerCarryOverPanXMode,
                readerCarryOverPanYMode = preferences[SettingsPreferencesKeys.READER_CARRY_OVER_PAN_Y_MODE]
                    ?: DEFAULT_SETTINGS.readerCarryOverPanYMode,
                readerDoubleTapReset = preferences[SettingsPreferencesKeys.READER_DOUBLE_TAP_RESET]
                    ?: DEFAULT_SETTINGS.readerDoubleTapReset,
                readerReadingDirection = preferences[SettingsPreferencesKeys.READER_READING_DIRECTION]
                    ?: DEFAULT_SETTINGS.readerReadingDirection,
                scanSubfolders = preferences[SettingsPreferencesKeys.SCAN_SUBFOLDERS]
                    ?: DEFAULT_SETTINGS.scanSubfolders,
            )
        }.distinctUntilChanged { old, new ->
            old == new
        }

    suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        dataStore.edit {
            it[key] = value
        }
    }
}