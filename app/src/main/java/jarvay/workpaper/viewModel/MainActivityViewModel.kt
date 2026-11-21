package jarvay.workpaper.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import jarvay.workpaper.data.preferences.RunningPreferencesRepository
import jarvay.workpaper.data.preferences.SettingsPreferencesRepository
import javax.inject.Inject

/**
 * "检查更新/仓库镜像/打赏"整个功能已经删除，这里不再持有 RetrofitClient、
 * 不再做自更新检测。
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    settingsPreferencesRepository: SettingsPreferencesRepository,
    val runningPreferencesRepository: RunningPreferencesRepository
) : ViewModel() {
    val settings =
        settingsPreferencesRepository.settingsPreferencesFlow.asLiveData()

    val runningPreferences = runningPreferencesRepository.runningPreferencesFlow.asLiveData()
}
