package com.mazharansari.axion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mazharansari.axion.ui.ChatViewModel
import com.mazharansari.axion.ui.Route
import com.mazharansari.axion.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handleShare(intent)
        setContent { AppRoot(vm) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: Intent?) {
        if (i?.getBooleanExtra(com.mazharansari.axion.update.AppUpdater.EXTRA_OPEN_ABOUT, false) == true) { vm.debugStart = Route.About; return }   // "update available" notification
        if (i?.getBooleanExtra(com.mazharansari.axion.wakeword.WakeWordService.EXTRA_START_CALL, false) == true) { vm.debugStart = Route.VoiceCall; return }   // Phase B: "Hey Axion" wake word opens the Live call
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { vm.addAttachment(it) }
        i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.draft = it }
    }
}
