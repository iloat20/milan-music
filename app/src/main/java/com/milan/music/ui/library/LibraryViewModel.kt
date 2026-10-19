package com.milan.music.ui.library

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
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
                track.displayTitle.contains(currentQuery, ignoreCase = true) ||
                    track.displayArtist.contains(currentQuery, ignoreCase = true) ||
                    track.displayAlbum.contains(currentQuery, ignoreCase = true)
            }
        }

        val sorted = when (sort) {
            SortMode.TITLE -> filtered.sortByCached { it.displayTitle.lowercase() }
            // Chained stable sorts: sort by the secondary key first, then the
            // primary — TimSort's stability keeps the secondary ordering intact.
            SortMode.ARTIST -> filtered
                .sortByCached { it.displayTitle.lowercase() }
                .sortByCached { it.displayArtist.lowercase() }
            SortMode.ALBUM -> filtered
                .sortByCached { it.trackNumber }
                .sortByCached { it.displayAlbum.lowercase() }
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
     * Paints the cached snapshot instantly, then silently reconciles it against the
     * media index. The list is only swapped when the track set actually changed, so
     * a normal launch neither waits on MediaStore nor flickers.
     */
    fun ensureLoaded() {
        if (cacheLoaded) return
        cacheLoaded = true
        viewModelScope.launch {
            val cached = libraryCache.load()
            if (cached.isNotEmpty()) {
                allTracks.value = cached
                loading.value = false
            }

            val fresh = mediaStore.loadTracks()

            // An empty result while a non-empty cache exists almost always means the
            // index was momentarily unavailable; do not wipe the user's library.
            if (fresh.isEmpty() && cached.isNotEmpty()) {
                loading.value = false
                return@launch
            }

            val cachedIds = cached.mapTo(HashSet()) { it.id }
            val freshIds = fresh.mapTo(HashSet()) { it.id }

            if (cachedIds != freshIds) {
                allTracks.value = fresh
                libraryCache.save(fresh)
            }

            loading.value = false
        }
    }

    /** Forces a fresh MediaStore scan and rewrites the cache. */
    fun refresh() {
        viewModelScope.launch { scan() }
    }

    private suspend fun scan() {
        loading.value = true
        val tracks = mediaStore.loadTracks()
        allTracks.value = tracks
        libraryCache.save(tracks)
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
