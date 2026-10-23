package com.milan.music.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.milan.music.data.media.LibraryCacheStore
import com.milan.music.data.media.MediaStoreRepository
import com.milan.music.data.model.SortMode
import com.milan.music.data.model.Track
import com.milan.music.data.prefs.SettingsStore
import com.milan.music.data.userdata.UserData
import com.milan.music.data.userdata.UserDataStore
import com.milan.music.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val tracks: List<Track> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val sortMode: SortMode = SortMode.TITLE,
    val loading: Boolean = true,
)

/** Intermediate carrier so user-data changes never re-enter the sort pipeline. */
private data class SortedLibrary(
    val tracks: List<Track> = emptyList(),
    val sortMode: SortMode = SortMode.TITLE,
)

/**
 * Decorate-sort-undecorate: `sortedBy` recomputes its selector on every comparison
 * (O(n log n) calls), so lowercase keys are materialised once per element instead.
 */
private inline fun <T, K : Comparable<K>> List<T>.sortByCached(key: (T) -> K): List<T> =
    map { it to key(it) }.sortedBy { it.second }.map { it.first }

/**
 * 排序键：折叠大小写，缺失字段统一排到最后。
 *
 * 原先排序用的是 `Track.displayTitle` 这类**显示**字段，而它会把空标题折叠成"未知曲目"——
 * 于是排序结果依赖界面语言。现在模型只给原值，缺失项用一个语言无关的哨兵顶到末尾，
 * 位置与旧行为一致。
 */
private fun String.sortKeyOrLast(): String = ifBlank { "\uFFFF" }.lowercase()

class LibraryViewModel(
    private val mediaStore: MediaStoreRepository,
    private val libraryCache: LibraryCacheStore,
    private val userData: UserDataStore,
    private val settings: SettingsStore,
) : ViewModel() {

    private val allTracks = MutableStateFlow<List<Track>>(emptyList())
    private val queryInput = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private var cacheLoaded = false

    /** The scan currently in flight, if any. See [launchScan] for why it exists. */
    private var scanJob: Job? = null

    /** Immediate text, used by the search field so typing never feels laggy. */
    val query: StateFlow<String> = queryInput.asStateFlow()

    /**
     * Filtering runs off the debounced value: without it every keystroke would
     * re-filter and re-sort the whole library.
     */
    @OptIn(FlowPreview::class)
    private val debouncedQuery: StateFlow<String> = queryInput
        .debounce(QUERY_DEBOUNCE_MS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val userDataFlow = userData.data
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserData())

    private val sortModeFlow = settings.sortMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortMode.TITLE)

    /**
     * Filter + sort pipeline, isolated from user data.
     *
     * Toggling a favorite only changes [userDataFlow]; keeping the sort in its own
     * flow means a like tap re-runs a single O(n) favorite filter instead of the
     * full filter-and-sort over the whole library.
     */
    private val sortedLibrary: StateFlow<SortedLibrary> = combine(
        allTracks,
        debouncedQuery,
        sortModeFlow,
    ) { tracks, currentQuery, sort ->
        val filtered = if (currentQuery.isBlank()) {
            tracks
        } else {
            tracks.filter { track ->
                track.title.contains(currentQuery, ignoreCase = true) ||
                    track.artist.contains(currentQuery, ignoreCase = true) ||
                    track.album.contains(currentQuery, ignoreCase = true)
            }
        }

        val sorted = when (sort) {
            SortMode.TITLE -> filtered.sortByCached { it.title.sortKeyOrLast() }
            // Chained stable sorts: sort by the secondary key first, then the
            // primary — TimSort's stability keeps the secondary ordering intact.
            SortMode.ARTIST -> filtered
                .sortByCached { it.title.sortKeyOrLast() }
                .sortByCached { it.artist.sortKeyOrLast() }
            SortMode.ALBUM -> filtered
                .sortByCached { it.trackNumber }
                .sortByCached { it.album.sortKeyOrLast() }
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
            SortMode.DURATION -> filtered.sortedByDescending { it.duration }
        }

        SortedLibrary(sorted, sort)
    }
        // `viewModelScope` runs on Dispatchers.Main: without this, a 5k-track
        // filter + lowercase-keyed sort would block the UI thread on every
        // keystroke that survives the debounce and on every sort-mode change.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortedLibrary())

    val state: StateFlow<LibraryUiState> = combine(
        sortedLibrary,
        userDataFlow,
        loading,
    ) { library, user, isLoading ->
        val favoriteIds = user.favorites.toHashSet()
        LibraryUiState(
            tracks = library.tracks,
            favorites = library.tracks.filter { it.id in favoriteIds },
            sortMode = library.sortMode,
            loading = isLoading,
        )
    }
        // Favouriting a track rebuilds a HashSet and re-filters the whole library;
        // keep that off the main thread as well.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /**
     * Paints the cached snapshot instantly, then reconciles it against the media
     * index — but only when the index actually changed.
     *
     * The reconciliation used to run on every cold start, so the cache only saved the
     * first frame, not the scan: a 5k-track library paid 200-800 ms of cursor work on
     * each launch even when nothing had been added. Comparing the provider's
     * generation counter makes the common case (nothing changed) cost one binder
     * call.
     */
    fun ensureLoaded() {
        if (cacheLoaded) return
        launchScan {
            // Rows and watermark from a single read: two separate reads can straddle a
            // concurrent save, which would pair the rows of one generation with the
            // watermark of another.
            val snapshot = libraryCache.snapshot()
            val cached = snapshot.tracks
            // Taken once, before any row is read, then reused for the write below.
            //
            // Two reasons the *pre-read* value is the correct one to store. First
            // cost: a second `stamp()` after the scan is another binder call, and on
            // API 26-29 it degrades to a full-table aggregate, so the old code paid
            // the most expensive query twice. Second, and the actual bug: a stamp
            // taken after the read labels the snapshot with an index state it may
            // not contain. If a track were added while the cursor walked, the stored
            // generation would already include it while `fresh` does not, so the
            // next launch would match and skip — the new track invisible until a
            // manual refresh. Storing the earlier stamp makes that launch rescan
            // instead, which heals in one pass.
            val stamp = mediaStore.stamp()
            if (cached.isNotEmpty()) {
                allTracks.value = cached
                loading.value = false

                // The stamp is written in the same commit as the rows, so a match
                // means the snapshot still describes what the provider has indexed.
                val stored = snapshot.stamp
                if (stamp.sameAs(stored)) {
                    Log.i(TAG, "library unchanged (${stored?.describe()}), rescan skipped")
                    return@launchScan
                }
            }

            val fresh = mediaStore.loadTracks()

            // An empty result while a non-empty cache exists almost always means the
            // index was momentarily unavailable; do not wipe the user's library.
            if (keepsCachedLibraryOnEmptyScan(fresh.size, cached.size)) {
                Log.w(
                    TAG,
                    "rescan returned 0 tracks while ${cached.size} are cached; " +
                        "keeping the cache and forcing a rescan next launch",
                )
                loading.value = false
                return@launchScan
            }

            // Compare whole rows, not id sets. Re-tagging a file rewrites title,
            // artist and album in place: the ids — and the row count — stay identical,
            // so an id comparison reads "nothing changed" and throws away the very
            // metadata the generation check just sent us here to fetch. Worse, the
            // save below still runs, stamping the *old* rows as current, so the next
            // launch matches and skips too: the change never surfaces without a manual
            // refresh. `Track` is a data class over the same columns the cache
            // serialises, so list equality is exactly "what the user sees differs".
            if (cached != fresh) {
                allTracks.value = fresh
            }
            // Saved even when the rows match: the payload also carries the stamp, and
            // this is what lets the next launch skip the scan. A cache written before
            // the stamp existed has none, so it fails the comparison exactly once and
            // heals here.
            libraryCache.save(fresh, stamp)

            loading.value = false
        }
    }

    /** Forces a fresh MediaStore scan and rewrites the cache. */
    fun refresh() {
        launchScan { scan() }
    }

    /**
     * Runs [block] as *the* scan for this moment, or drops it if one is already in
     * flight.
     *
     * The dedupe is not cosmetic. `HomeScreen` has two independent triggers for the
     * first library read — the permission callback calls `refresh()`, and the
     * `LaunchedEffect` keyed on the grant calls `ensureLoaded()`. Both fire on the
     * same frame, so the first launch walked the whole MediaStore twice, and the
     * first launch is the one the user waits on.
     */
    private fun launchScan(block: suspend () -> Unit) {
        cacheLoaded = true
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Put the flag back: it is set before the work starts, so a throwing
                // read used to leave this device convinced the library was loaded and
                // every later `ensureLoaded()` would no-op until the process died.
                cacheLoaded = false
                loading.value = false
                Log.w(TAG, "library scan failed; the next request retries", e)
            }
        }
    }

    private suspend fun scan() {
        loading.value = true
        // Same ordering rule as [ensureLoaded]: stamp before the rows, so a change
        // that lands mid-scan forces the next launch to rescan rather than going
        // unnoticed.
        val stamp = mediaStore.stamp()
        val tracks = mediaStore.loadTracks()
        // The explicit-refresh path needs the same guard as [ensureLoaded], and it is
        // the path that runs when the user already believes something is wrong. A
        // provider that answers with zero rows while it rebuilds would empty the
        // library *and* stamp that emptiness as current, so the next launch would
        // match the stamp and skip the rescan that could have healed it.
        if (keepsCachedLibraryOnEmptyScan(tracks.size, allTracks.value.size)) {
            Log.w(
                TAG,
                "rescan returned 0 tracks while ${allTracks.value.size} are cached; " +
                    "keeping the library and forcing a rescan next launch",
            )
            loading.value = false
            return
        }
        allTracks.value = tracks
        libraryCache.save(tracks, stamp)
        loading.value = false
    }

    fun setQuery(value: String) {
        queryInput.value = value
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settings.setSortMode(mode) }
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { userData.toggleFavorite(trackId) }
    }

    companion object {
        private const val QUERY_DEBOUNCE_MS = 180L
        private const val TAG = "LibraryViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    container.mediaStore,
                    container.libraryCache,
                    container.userData,
                    container.settings,
                )
            }
        }
    }
}

/**
 * Whether a scan that returned [freshCount] rows must leave the current library alone.
 *
 * MediaProvider answers a query with zero rows while it is restarting, rebuilding its
 * index, or when the permission check races the read — indistinguishable from an
 * emptied library at this level. Treating that as "the user has no music" clears the
 * list *and* stamps the empty snapshot as current, so the next launch matches the
 * stamp and skips the rescan that could have healed it. Only a genuinely empty
 * library (nothing cached, nothing returned) may replace what is on screen.
 *
 * Kept as a free function so the decision is unit-testable without Android: the
 * `LibraryViewModel` around it needs DataStore, a ContentResolver and a main looper.
 */
internal fun keepsCachedLibraryOnEmptyScan(freshCount: Int, cachedCount: Int): Boolean =
    freshCount == 0 && cachedCount > 0
