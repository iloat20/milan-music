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

    /**
     * The controller is told the UI left the foreground — and this hook stays in the
     * Activity on purpose (review P3-4 asked to move it down; that is the wrong target).
     *
     * A ViewModel cannot stand in for `onStop`. `onCleared` means "this ViewModel is
     * finished", which is neither necessary nor sufficient for "the UI is gone": a
     * configuration change keeps the ViewModel alive (fine) but a backgrounded app
     * keeps it alive too (so it would never fire), while a `finish()` from a
     * non-configuration teardown clears it without the app going to the background.
     *
     * `ProcessLifecycleOwner` would be semantically closer (it fires once, when the
     * last Activity stops) but costs a dependency and a 700 ms idle delay to express
     * the same thing this single line already says. It becomes worth it the day a
     * second Activity appears and `isChangingConfigurations` stops being a complete
     * answer to "is the app still in front".
     */
    override fun onStop() {
        super.onStop()
        // A configuration change tears the activity down but not the app, so the
        // controller must survive it; `onUiStopped` only releases when there is no
        // queue to preserve.
        if (!isChangingConfigurations) container.player.onUiStopped()
    }
}
