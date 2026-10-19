package com.milan.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.milan.music.di.AppContainer
import com.milan.music.ui.MilanApp

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as MilanMusicApp).container
        setContent {
            MilanApp(container = container)
        }
    }

    override fun onStop() {
        super.onStop()
        // A configuration change tears the activity down but not the app, so the
        // controller must survive it; `onUiStopped` only releases when there is no
        // queue to preserve.
        if (!isChangingConfigurations) container.player.onUiStopped()
    }
}
