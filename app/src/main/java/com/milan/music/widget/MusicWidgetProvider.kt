package com.milan.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.scale
import com.milan.music.MainActivity
import com.milan.music.MilanMusicApp
import com.milan.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 2×2 home screen widget: artwork, title and a play/pause toggle.
 *
 * Everything here runs in RemoteViews (no Compose), and the artwork crosses a process
 * boundary through Binder, so the bitmap is downscaled first — a 512×512 ARGB bitmap
 * already sits at the Binder transaction limit and larger covers would silently fail.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { widgetId ->
                    render(context, appWidgetManager, widgetId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> handleToggle(context)

            ACTION_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, MusicWidgetProvider::class.java),
                )
                if (ids.isNotEmpty()) onUpdate(context, manager, ids)
            }
        }
        super.onReceive(context, intent)
    }

    /**
     * Toggle playback, or start one when there is nothing queued.
     *
     * Without the empty-queue branch the button is dead on a cold start: the
     * controller has zero media items, so `play()` has nothing to play.
     */
    private fun handleToggle(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val app = context.applicationContext as? MilanMusicApp ?: return@launch
                val player = app.container.player

                if (player.state.value.queueSize == 0) {
                    val tracks = app.container.libraryCache.load()
                    if (tracks.isNotEmpty()) {
                        player.playQueue(tracks, Random.nextInt(tracks.size))
                    }
                } else {
                    player.togglePlayPause()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val app = context.applicationContext as? MilanMusicApp ?: return
        val state = app.container.player.state.value
        val track = state.currentTrack

        val views = RemoteViews(context.packageName, R.layout.widget_music)

        if (track != null) {
            val bitmap = app.container.artwork.load(track)?.asAndroidBitmap()
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_cover, bitmap.scale(COVER_SIZE, COVER_SIZE))
            }
            views.setTextViewText(R.id.widget_title, track.displayTitle)
        } else {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
        }

        views.setImageViewResource(
            R.id.widget_play,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_play, toggleIntent(context))

        manager.updateAppWidget(widgetId, views)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PENDING_FLAGS)
    }

    private fun toggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, MusicWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(context, REQUEST_TOGGLE, intent, PENDING_FLAGS)
    }

    companion object {
        const val ACTION_TOGGLE = "com.milan.music.widget.TOGGLE"
        const val ACTION_REFRESH = "com.milan.music.widget.REFRESH"

        private const val COVER_SIZE = 256
        private const val REQUEST_OPEN = 100
        private const val REQUEST_TOGGLE = 101
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Broadcast to every widget instance; safe to call when none exist. */
        fun refresh(context: Context) {
            val intent = Intent(context, MusicWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .setPackage(context.packageName)
            context.sendBroadcast(intent)
        }
    }
}
