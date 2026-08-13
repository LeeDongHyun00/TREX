package com.example.trex_kotlin.store

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * The store lives in its own directory under the app's private `filesDir`, rather than as a loose
 * file beside whatever else lands there.
 *
 * The directory is the unit the backup rules exclude. `save` writes through a sibling temp file
 * holding an identical copy of the snapshot, and Android matches a file-domain `path` by exact
 * name — so excluding only the final file would leave a crash-orphaned `.tmp` full of the user's
 * body metrics eligible for cloud backup. Excluding the directory covers the temp file and
 * anything the store may write later, by construction.
 */
internal const val TREX_STORE_DIRECTORY_NAME = "trex"
internal const val TREX_STORE_FILE_NAME = "store.v1.txt"

/**
 * How the store reaches the composition. This is the entire injection story.
 *
 * The app has no ViewModel, no DI container and no `Application` subclass, and this change
 * deliberately introduces none of them. Note that `remember` is scoped to the composition, so a
 * configuration change *does* build a second [FileTrexStore] over the same path — correctness
 * across that overlap comes from the store's process-wide file lock, not from instance identity.
 */
@Composable
internal fun rememberTrexStore(): TrexStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        FileTrexStore(
            File(File(context.filesDir, TREX_STORE_DIRECTORY_NAME), TREX_STORE_FILE_NAME),
        )
    }
}
