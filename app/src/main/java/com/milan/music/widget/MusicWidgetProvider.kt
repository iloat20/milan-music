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
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import com.milan.music.MainActivity
import com.milan.music.MilanMusicApp
import com.milan.music.R
import com.milan.music.data.media.softwareCopy
import com.milan.music.data.model.Track
import com.milan.music.play.toTrack
import com.milan.music.ui.text.titleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        providerScope.launch {
            try {
                appWidgetIds.forEach { widgetId ->
                    // Per-instance isolation: a failure here escapes into a
                    // SupervisorJob coroutine with no handler, which takes the whole
                    // process down — the user sees the launcher crash, not just a
                    // blank widget. One misbehaving instance must not abort the rest.
                    runCatching { render(context, appWidgetManager, widgetId) }
                        .onFailure { Log.w(TAG, "render failed for widget $widgetId", it) }
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
     * Re-checks the live queue before executing the button's rendered action.
     *
     * RemoteViews and its PendingIntent survive process death. A broadcast that was
     * rendered as a toggle can therefore arrive in a new process whose queue is still
     * empty; opening the activity is the only path that retains while-in-use playback
     * capability after Android 17's background-audio hardening.
     */
    private fun handleToggle(context: Context) {
        val pending = goAsync()
        providerScope.launch {
            try {
                runCatching {
                    val app = context.applicationContext as? MilanMusicApp ?: return@runCatching
                    val player = app.container.player
                    when (widgetToggleAction(player.state.value.hasQueue)) {
                        WidgetPlayAction.TOGGLE -> {
                            // Controller calls are main-thread bound; see PlayerController.
                            withContext(Dispatchers.Main) { player.togglePlayPause() }
                        }

                        WidgetPlayAction.OPEN_APP_AND_PLAY -> {
                            Log.i(TAG, "toggle has no live queue: opening the app to play")
                            openAppIntent(context, playOnStart = true).send()
                        }
                    }
                }.onFailure { Log.w(TAG, "toggle failed", it) }
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
        val live = app.container.player.state.value
        // The session is deliberately not bound at process start (see MilanMusicApp):
        // the library screen owns the connect. The launcher's own `onUpdate` after a
        // reboot or a low-memory kill therefore ran with an empty state and drew the
        // bare app name over a play icon, even though a queue was waiting in the
        // snapshot to be resumed. Read it only in that case — this is a file read.
        val snapshot = if (live.currentTrack == null) {
            runCatching { app.container.playbackSnapshot.load() }
                .onFailure { Log.w(TAG, "snapshot fallback unreadable", it) }
                .getOrNull()
        } else {
            null
        }
        val display = resolveWidgetDisplay(
            hasLiveTrack = live.currentTrack != null,
            livePlaying = live.isPlaying,
            snapshotAvailable = snapshot?.current != null,
            snapshotWasPlaying = snapshot?.wasPlaying == true,
        )
        val track = live.currentTrack ?: snapshot?.current?.toTrack()

        val views = RemoteViews(context.packageName, R.layout.widget_music)

        if (track != null) {
            val cover = coverFor(context, track, cardCornerRadiusPx(manager, widgetId))
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover)
            views.setTextViewText(R.id.widget_title, track.titleText(context))
        } else {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
        }

        views.setImageViewResource(
            R.id.widget_play,
            if (display.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        // The transport intent reads the **live** queue, never the snapshot: a snapshot
        // is not a bound session, so a TOGGLE broadcast in a process without one would
        // bind a fresh, empty queue and do nothing. Cold start belongs to the visible
        // activity — see [widgetPlayAction].
        views.setOnClickPendingIntent(
            R.id.widget_play,
            when (widgetPlayAction(live.hasQueue)) {
                WidgetPlayAction.TOGGLE -> toggleIntent(context)
                WidgetPlayAction.OPEN_APP_AND_PLAY -> openAppIntent(context, playOnStart = true)
            },
        )

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
     * [roundedCover] — for artwork that only changes when the track does. The
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
        // 512 px 而不是 192 px 的列表缩略图：目标是 256 px 的卡片封面，用缩略图
        // 就是 1.33 倍放大，描边和文字都糊。这里宁可多解一次再缩下来。
        val bitmap = app.container.artwork.loadPlayerArtwork(track)?.asAndroidBitmap()
            ?: return null
        return runCatching { roundedCover(bitmap, COVER_SIZE, radiusPx) }
            .onFailure { Log.w(TAG, "cover render failed for album ${track.albumId}", it) }
            .getOrNull()
            ?.also { coverCache.put(key, it) }
    }

    override fun onEnabled(context: Context) {
        instanceFlag = true
    }

    override fun onDisabled(context: Context) {
        // Called when the last instance is removed.
        instanceFlag = false
        coverCache.evictAll()
    }

    /**
     * Scales [source] to a [size] square and clips it to rounded corners in one
     * allocation.
     *
     * The previous chain was `scale()` followed by a second full bitmap for the
     * round-rect mask — two 256 KB allocations per track change to produce one.
     * Drawing the source straight into the rounded output with a scaling paint
     * removes the intermediate bitmap and the GC pressure that came with it.
     *
     * Returns null when the source cannot be drawn. On API 29+ the artwork comes from
     * `ContentResolver.loadThumbnail`, which is free to hand back a
     * `Bitmap.Config.HARDWARE` bitmap; `Canvas.drawBitmap` rejects those on a software
     * canvas with `IllegalArgumentException: Software rendering doesn't support
     * hardware bitmaps`. [ArtworkRepository] pays the same copy for its pixel scan, so
     * the widget pays it too — and a null here means "show the placeholder", which is
     * the same outcome as missing artwork.
     */
    private fun roundedCover(source: Bitmap, size: Int, radiusPx: Float): Bitmap? {
        // 取可读像素的副本；换不出来就返回 null，与"没有封面"走同一条占位图路径。
        val drawable = source.softwareCopy() ?: return null
        val output = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (radiusPx > 0f) {
            canvas.drawRoundRect(
                RectF(0f, 0f, size.toFloat(), size.toFloat()),
                radiusPx,
                radiusPx,
                paint,
            )
            // SRC_IN keeps pixels inside the rounded rect and clears the rest, so the
            // corners end up transparent instead of a hard-coded colour.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(
            drawable,
            Rect(0, 0, drawable.width, drawable.height),
            Rect(0, 0, size, size),
            paint,
        )
        return output
    }

    private fun openAppIntent(context: Context, playOnStart: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PLAY_ON_START, playOnStart)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Separate request codes: FLAG_UPDATE_CURRENT would otherwise make the play
        // button's extra leak into the card-tap PendingIntent, or vice versa.
        val requestCode = if (playOnStart) REQUEST_OPEN_PLAY else REQUEST_OPEN
        return PendingIntent.getActivity(context, requestCode, intent, PENDING_FLAGS)
    }

    private fun toggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, MusicWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(context, REQUEST_TOGGLE, intent, PENDING_FLAGS)
    }

    companion object {
        const val ACTION_TOGGLE = "com.milan.music.widget.TOGGLE"
        const val ACTION_REFRESH = "com.milan.music.widget.REFRESH"

        private const val TAG = "MusicWidget"

        /**
         * One scope for every broadcast this provider handles.
         *
         * `onUpdate` and `handleToggle` used to build their own
         * `CoroutineScope(SupervisorJob() + ...)` per call — a fresh job tree per
         * broadcast, owned by nobody and never cancelled, and the two disagreed on the
         * dispatcher for no stated reason. One process-lifetime scope keeps the work
         * structured; `goAsync().finish()` in `finally` still bounds each broadcast, and
         * the process dying is the only way this scope ends, exactly like
         * `AppContainer.scope`. Work runs on `Default` and hops to `Main` for the
         * controller calls, which are main-thread bound.
         */
        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val COVER_SIZE = 256

        /** Must match the `corners` radius in `drawable/widget_card_bg.xml`. */
        private const val CARD_RADIUS_DP = 26f

        /** Must match `android:minWidth` in `xml/music_widget_info.xml`. */
        private const val FALLBACK_CARD_WIDTH_DP = 110

        private const val REQUEST_OPEN = 100
        private const val REQUEST_TOGGLE = 101
        private const val REQUEST_OPEN_PLAY = 102
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
