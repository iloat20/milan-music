package com.milan.music.di

import android.content.Context
import com.milan.music.data.media.ArtworkRepository
import com.milan.music.data.media.LibraryCacheStore
import com.milan.music.data.media.MediaStoreRepository
import com.milan.music.data.prefs.SettingsStore
import com.milan.music.data.userdata.UserDataStore
import com.milan.music.play.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. A DI framework would add build time and
 * indirection that this app size does not justify.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mediaStore = MediaStoreRepository(appContext)
    val libraryCache = LibraryCacheStore(appContext)
    val artwork = ArtworkRepository(appContext)
    val userData = UserDataStore(appContext)
    val settings = SettingsStore(appContext)
    val player = PlayerController(appContext, scope)
}
