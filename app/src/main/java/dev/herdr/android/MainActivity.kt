package dev.herdr.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import dev.herdr.android.ui.HerdrAppScreen
import dev.herdr.android.ui.HerdrMainViewModel
import dev.herdr.android.ui.theme.HerdrAndroidTheme
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.zerotier.AndroidZeroTierOverlayTransport

class MainActivity : ComponentActivity() {
    private val mainViewModel: HerdrMainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        mainViewModel.bindZeroTierTransport(AndroidZeroTierOverlayTransport(applicationContext))
        mainViewModel.initializeFromContext(applicationContext, intent)
        setContent {
            val appSettings by mainViewModel.appSettings.collectAsState()
            HerdrAndroidTheme(
                preset = appSettings.themePreset,
                triad = appSettings.themeTriad
            ) {
                val palette = HerdrColors.currentPalette
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = palette.chrome.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = palette.chrome.toArgb()
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = palette.isLight
                    controller.isAppearanceLightNavigationBars = palette.isLight
                }
                HerdrAppScreen(viewModel = mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel.initializeFromContext(applicationContext, intent)
    }
}
