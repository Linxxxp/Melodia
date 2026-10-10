package com.lin0721.linmusic

import android.Manifest
import android.content.Context
import android.content.Intent
import com.lin0721.linmusic.feature.recognition.service.ACTION_OPEN_RECOGNITION_RESULT
import com.lin0721.linmusic.feature.recognition.service.PlaybackRecognitionState
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lin0721.linmusic.core.player.FloatingLyricService
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.ui.theme.MelodiaTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    companion object {
        @JvmField
        var sBaseDpi: Int = 0
    }

    private val settingsPreferences: SettingsPreferences by inject()
    private val playbackRecognitionState: PlaybackRecognitionState by inject()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        try {
            val resources = newBase.resources
            val config = resources.configuration
            if (sBaseDpi == 0) {
                sBaseDpi = config.densityDpi
            }
            var displayId = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayId = try {
                    newBase.getDisplay()?.displayId ?: 0
                } catch (e: Exception) {
                    0
                }
            }
            val scale = if (displayId == 4) 1.85f else 2.25f
            val targetDpi = Math.round(sBaseDpi * scale)
            val newConfig = Configuration(config)
            newConfig.densityDpi = targetDpi
            val dm = resources.displayMetrics
            resources.updateConfiguration(newConfig, dm)
        } catch (e: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开启宽色域，提升暗部与渐变渲染位深
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        // App 全局强制暗色主题，系统栏样式固定按暗色处理，不跟随系统亮暗色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        requestNotificationPermissionIfNeeded()
        handleRecognitionIntent(intent)

        // 监听悬浮歌词开关
        lifecycleScope.launch {
            settingsPreferences.showDesktopLrc.collectLatest { enabled ->
                if (enabled) {
                    startFloatingLyricOrRequestPermission()
                } else {
                    stopService(Intent(this@MainActivity, FloatingLyricService::class.java))
                }
            }
        }

        setContent {
            MelodiaTheme {
                MelodiaApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRecognitionIntent(intent)
    }

    // 点开识曲结果通知：交给界面层打开识别页展示该结果
    private fun handleRecognitionIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_RECOGNITION_RESULT) {
            playbackRecognitionState.requestOpenResult()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        lifecycleScope.launch {
            val enabled = settingsPreferences.showDesktopLrc.first()
            if (enabled && android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                startService(Intent(this@MainActivity, FloatingLyricService::class.java))
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val window = window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // Android 13+ 需要运行时授权才能显示系统通知
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 有悬浮窗权限则直接启动桌面歌词，否则需要前往系统设置授权
    private fun startFloatingLyricOrRequestPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingLyricService::class.java))
            return
        }
        android.widget.Toast.makeText(
            this,
            "请开启悬浮窗权限以显示桌面歌词",
            android.widget.Toast.LENGTH_LONG
        ).show()
        startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }
}
