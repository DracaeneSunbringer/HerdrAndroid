package dev.herdr.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.herdr.android.ui.HerdrAppScreen
import dev.herdr.android.ui.HerdrMainViewModel
import dev.herdr.android.ui.theme.HerdrAndroidTheme
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
            HerdrAndroidTheme {
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
