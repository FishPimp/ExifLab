package io.github.fishpimp.exiflab

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.ExifLabApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single activity. Extends AppCompatActivity so per-app language changes apply on
 * Android 10-12 as well. Also the share target for images: a share lands directly on the photo,
 * and backing out of it returns to the app that shared it.
 */
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A restored activity already showed its share (the back stack holds it), unless the
        // process died while the share was still resolving.
        if (savedInstanceState?.getBoolean(KEY_SHARE_HANDLED) != true) viewModel.onLaunchIntent(intent)

        // Hold the splash screen until settings are loaded so the first frame already uses
        // the user's theme instead of flashing the default one, and until a shared photo is
        // ready so the app opens directly on it.
        val settingsState: StateFlow<AppSettings?> = appGraph.settingsRepository.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        splash.setKeepOnScreenCondition { settingsState.value == null || viewModel.awaitingLaunchShare.value }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.failures.collect { failure ->
                    val message = if (failure.error == PhotoAccessError.Unsupported) {
                        R.string.photo_error_unsupported
                    } else {
                        R.string.share_open_failed
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    if (failure.closeApp) finish()
                }
            }
        }

        setContent {
            val settings by settingsState.collectAsStateWithLifecycle()
            val awaitingShare by viewModel.awaitingLaunchShare.collectAsStateWithLifecycle()
            val externalOpen by viewModel.pendingOpen.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            if (awaitingShare) return@setContent
            ExifLabTheme(
                themeMode = current.themeMode,
                dynamicColor = current.dynamicColor,
                palette = current.palette,
                contrast = current.contrast,
            ) {
                val dark = ExifLabTheme.isDark
                DisposableEffect(dark) {
                    val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose { }
                }
                ExifLabApp(
                    externalOpen = externalOpen,
                    onExternalOpenHandled = viewModel::onExternalOpenHandled,
                    onExitSharedPhotos = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARE_HANDLED, !viewModel.hasUnhandledShare)
    }

    private companion object {
        const val KEY_SHARE_HANDLED = "io.github.fishpimp.exiflab.SHARE_HANDLED"
    }
}
