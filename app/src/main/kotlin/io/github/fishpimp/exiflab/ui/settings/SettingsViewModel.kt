package io.github.fishpimp.exiflab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.data.backup.BackupManager
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
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

/**
 * @param backups usage and deletion of edit backups.
 * @param requestPrune asks for a prune soon, e.g. after the retention got shorter.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val backups: BackupManager,
    private val requestPrune: () -> Unit,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Bytes the backups take, or null until measured. */
    val backupUsage: StateFlow<Long?> = backups.usedBytes

    /** The total size backups are pruned down to. */
    val backupSizeCap: Long get() = backups.sizeCap

    init {
        viewModelScope.launch { backups.refreshUsage() }
    }

    private val _language = MutableStateFlow(AppLanguage.current())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.setDynamicColor(enabled) }
    fun setPalette(palette: BrandPalette) = viewModelScope.launch { repository.setPalette(palette) }
    fun setContrast(contrast: ContrastPreference) = viewModelScope.launch { repository.setContrast(contrast) }
    fun setOfflineMode(enabled: Boolean) = viewModelScope.launch { repository.setOfflineMode(enabled) }

    fun setBackupRetention(retention: BackupRetention) = viewModelScope.launch {
        repository.setBackupRetention(retention)
        requestPrune()
    }

    fun setSidecarNaming(naming: SidecarNaming) = viewModelScope.launch { repository.setSidecarNaming(naming) }

    fun deleteAllBackups() = viewModelScope.launch { backups.deleteAll() }

    fun setLanguage(language: AppLanguage) {
        _language.value = language
        AppLanguage.apply(language)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                SettingsViewModel(graph.settingsRepository, graph.backupManager, graph::requestBackupPrune)
            }
        }
    }
}
