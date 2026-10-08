package io.github.fishpimp.exiflab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcon
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.core.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.feature.home.HomeScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ExifLabTheme {
                AppShell()
            }
        }
    }
}

@Composable
private fun AppShell() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(selected = selected == 0, onClick = { selected = 0 }, icon = { ExifIcon(ExifIcons.PhotoLibrary, null) }, label = { Text("Photos") })
            item(selected = selected == 1, onClick = { selected = 1 }, icon = { ExifIcon(ExifIcons.History, null) }, label = { Text("History") })
            item(selected = selected == 2, onClick = { selected = 2 }, icon = { ExifIcon(ExifIcons.Settings, null) }, label = { Text("Settings") })
        },
    ) {
        Scaffold { padding ->
            HomeScreen(Modifier.fillMaxSize().padding(padding))
        }
    }
}
