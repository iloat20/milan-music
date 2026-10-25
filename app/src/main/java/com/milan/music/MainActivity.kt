package com.milan.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.milan.music.di.AppContainer
import com.milan.music.ui.MilanApp
import com.milan.music.ui.library.nextPlayRequest
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /**
     * One-shot play intents delivered by the home-screen widget.
     *
     * A cold launch starts at 1; `singleTop` re-delivery increments it. HomeScreen
     * compares the value with the one it already handled, so a later library refresh
     * cannot replay a stale "play" command over a user pause.
     */
    private val playRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as MilanMusicApp).container
        val requestsPlay = intent?.getBooleanExtra(EXTRA_PLAY_ON_START, false) == true
        playRequests.value = nextPlayRequest(playRequests.value, requestsPlay)
        if (requestsPlay) {
            // Consume it: an activity recreated from this Intent must not replay a
            // play command that the user already got. This runs even when Android
            // restores saved state together with a fresh widget intent.
            intent?.removeExtra(EXTRA_PLAY_ON_START)
        }
        setContent {
            val playRequest by playRequests.collectAsState()
            MilanApp(container = container, playRequest = playRequest)
        }
    }

    /**
     * `launchMode="singleTop"` keeps the existing task, so a widget play tap that
     * arrives while the app is open lands here instead of in [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestsPlay = intent.getBooleanExtra(EXTRA_PLAY_ON_START, false)
        playRequests.value = nextPlayRequest(playRequests.value, requestsPlay)
        if (requestsPlay) intent.removeExtra(EXTRA_PLAY_ON_START)
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

    companion object {
        /**
         * Explicit "start playing after the UI is visible" request from the widget.
         *
         * It exists so the Android 17 background-audio path does not have to start a
         * queue inside a broadcast, while a tap on the widget's play button still
         * overrides a paused snapshot.
         */
        const val EXTRA_PLAY_ON_START = "com.milan.music.extra.PLAY_ON_START"
    }
}
