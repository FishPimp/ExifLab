package io.github.fishpimp.exiflab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ContrastPreference
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _language = MutableStateFlow(AppLanguage.current())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.setDynamicColor(enabled) }
    fun setPalette(palette: BrandPalette) = viewModelScope.launch { repository.setPalette(palette) }
    fun setContrast(contrast: ContrastPreference) = viewModelScope.launch { repository.setContrast(contrast) }

    fun setLanguage(language: AppLanguage) {
        _language.value = language
        AppLanguage.apply(language)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel(this[APPLICATION_KEY]!!.appGraph.settingsRepository) }
        }
    }
}
