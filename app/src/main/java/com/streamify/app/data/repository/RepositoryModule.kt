package com.streamify.app.data.repository

import com.streamify.app.data.local.LocalDataSource
import com.streamify.app.data.remote.RemoteDataSource
import okhttp3.OkHttpClient

/**
 * Phase 2 · Step 2.4 → Phase 5 · Step 5.5 v2 — Repository DI seam.
 *
 * Lazy singletons over the data sources, parallel to NetworkModule
 * and LocalModule. Step 2.5 ViewModels reach these via
 * `app.repository.mainRepository` / `favoritesRepository` /
 * `playlistRepository` / `noticeRepository` (v2).
 *
 * Construction order: the constructor takes the data sources already
 * resolved, so [com.streamify.app.StreamifyApp] only needs to
 * instantiate this once after `network` + `local` are wired up.
 *
 * The v2 seam adds noticeRepository with an [OkHttpClient] + [Context]
 * + [com.streamify.app.data.local.NoticeDao] triple — see class
 * note on the repository itself for why these are injected directly
 * (NoticeRepository needs to call arbitrary URLs that don't pass
 * through the typed ApiService, and Room writes need Context-aware
 * scheduling).
 */
class RepositoryModule(
    remoteDataSource: RemoteDataSource,
    localDataSource: LocalDataSource,
    httpClient: OkHttpClient,
    noticeDaoProvider: () -> com.streamify.app.data.local.NoticeDao,
) {
    val mainRepository: MainRepository by lazy { MainRepository(remoteDataSource) }
    val favoritesRepository: FavoritesRepository by lazy { FavoritesRepository(localDataSource) }
    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(localDataSource) }

    /**
     * v2: pass-through to [NoticeRepository]. Lazy so the NoticeDao
     * lambda defers library init until first use (matches the existing
     * Module pattern).
     */
    val noticeRepository: NoticeRepository by lazy {
        NoticeRepository(
            httpClient = httpClient,
            noticeDao = noticeDaoProvider(),
        )
    }
}
