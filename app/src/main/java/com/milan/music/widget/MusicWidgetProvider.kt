package com.milan.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.milan.music.MainActivity
import com.milan.music.MilanMusicApp
import com.milan.music.R
import com.milan.music.data.model.Track
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
        instanceFlag = true
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
            val cover = coverFor(context, track, cardCornerRadiusPx(manager, widgetId))
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover)
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

    /**
     * Corner radius of the card, expressed in the cover bitmap's own pixel space.
     *
     * The cover fills the card, so the visual radius maps to `radiusDp / widthDp` of
     * the bitmap width. Reading the real width from the widget options keeps the
     * shape correct on launchers whose 2x2 cell is wider than the declared 110dp
     * minimum; when the launcher reports nothing usable, the declared minimum is
     * assumed.
     */
    private fun cardCornerRadiusPx(manager: AppWidgetManager, widgetId: Int): Float {
        val widthDp = manager.getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val referenceWidthDp = (widthDp.takeIf { it > 0 } ?: FALLBACK_CARD_WIDTH_DP).toFloat()
        return (CARD_RADIUS_DP / referenceWidthDp * COVER_SIZE)
            .coerceIn(0f, COVER_SIZE / 2f)
    }

    /**
     * 256 px rounded cover for one album, reused across refreshes.
     *
     * Corners are rounded here, in the app process, because on API 26-30
     * `clipToOutline` is a no-op in RemoteViews and the only reflective fix
     * (setClipToOutline) is not on the launcher's method whitelist — calling it fails
     * the whole update. Pre-clipping keeps the shape identical on every version.
     *
     * The result is cached per (albumId, corner radius): a refresh previously
     * allocated twice — `scale()` and then a second full bitmap inside
     * [withRoundedCorners] — for artwork that only changes when the track does. The
     * radius is part of the key because it derives from the launcher's cell width,
     * which differs between instances.
     */
    private suspend fun coverFor(
        context: Context,
        track: Track,
        radiusPx: Float,
    ): Bitmap? {
        val key = coverKey(track.albumId, radiusPx)
        coverCache.get(key)?.let { return it }
        val app = context.applicationContext as? MilanMusicApp ?: return null
        val bitmap = app.container.artwork.load(track)?.asAndroidBitmap() ?: return null
        return bitmap.scale(COVER_SIZE, COVER_SIZE)
            .withRoundedCorners(radiusPx)
            .also { coverCache.put(key, it) }
    }

    override fun onEnabled(context: Context) {
        instanceFlag = true
    }

    override fun onDisabled(context: Context) {
        // Called when the last instance is removed.
        instanceFlag = false
        coverCache.evictAll()
    }

    private fun Bitmap.withRoundedCorners(radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return this
        val output = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radiusPx,
            radiusPx,
            paint,
        )
        // SRC_IN keeps pixels inside the rounded rect and clears the rest, so the
        // corners end up transparent instead of a hard-coded colour.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(this, 0f, 0f, paint)
        return output
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

        /** Must match the `corners` radius in `drawable/widget_card_bg.xml`. */
        private const val CARD_RADIUS_DP = 26f

        /** Must match `android:minWidth` in `xml/music_widget_info.xml`. */
        private const val FALLBACK_CARD_WIDTH_DP = 110

        private const val REQUEST_OPEN = 100
        private const val REQUEST_TOGGLE = 101
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** 8 × 256 px ARGB = 2 MB; a launcher shows one instance, sometimes two. */
        private const val COVER_CACHE_BYTES = 2 * 1024 * 1024

        /** Low 7 bits of the cache key; the album id owns the rest. */
        private const val RADIUS_BUCKETS = 128

        private val coverCache = object : LruCache<Long, Bitmap>(COVER_CACHE_BYTES) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
        }

        private fun coverKey(albumId: Long, radiusPx: Float): Long =
            albumId * RADIUS_BUCKETS + radiusPx.toInt().coerceIn(0, RADIUS_BUCKETS - 1)

        /**
         * Whether a launcher holds an instance.
         *
         * `null` means "not determined yet": the provider lifecycle seeds it
         * (`onEnabled` / `onDisabled`) and the first call after a process start falls
         * back to a single `getAppWidgetIds` query.
         */
        @Volatile
        private var instanceFlag: Boolean? = null

        /**
         * Broadcast to every widget instance, or nothing when none exists.
         *
         * This used to fire on every track change, waking the receiver (`goAsync()`
         * plus a coroutine scope) even with zero widgets on any home screen.
         */
        fun refresh(context: Context) {
            if (!hasInstances(context)) return
            val intent = Intent(context, MusicWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .setPackage(context.packageName)
            context.sendBroadcast(intent)
        }

        private fun hasInstances(context: Context): Boolean {
            instanceFlag?.let { return it }
            val present = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
                .isNotEmpty()
            instanceFlag = present
            return present
        }
    }
}
