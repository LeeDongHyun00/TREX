package com.example.trex_kotlin.store

import java.io.File
import java.io.IOException

/**
 * A [TrexStore] over one UTF-8 text file in the app's private storage.
 *
 * This is the only file in the persistence layer that touches the filesystem, which is what keeps
 * `java.io` out of every other file and makes the package boundary auditable.
 *
 * Three properties, in the order they matter:
 *
 *  - **A write is all-or-nothing.** The snapshot goes to a sibling temp file and is renamed over
 *    the target. A process killed mid-write leaves either the previous file or the new one, never
 *    a half-written one that would decode to a plausible-looking partial plan.
 *  - **Nothing here throws.** A full disk, a revoked directory or a damaged file all degrade to a
 *    fresh app. Persistence is a convenience; it must never be able to break the launch path.
 *  - **Reads are bounded.** [maxBytes] caps what a corrupted or tampered file can make the launch
 *    path allocate, because [load] runs synchronously on the first composition.
 *
 * Both methods hold a process-wide lock rather than an instance one. An instance lock would look
 * sufficient and would not be: a configuration change builds a second [FileTrexStore] over the same
 * path — `remember` is scoped to the composition, which a recreation destroys whatever its key —
 * while the outgoing composition's last write may still be in flight. Two instances sharing one
 * temp path with two different locks is exactly how a half-written file reaches the target name.
 */
internal class FileTrexStore(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : TrexStore {

    override fun load(): TrexSnapshot? = synchronized(FILE_LOCK) {
        return try {
            if (!file.isFile) return null
            // A file past the cap is not truncated and parsed; it is refused. Half a plan restored
            // from a damaged file is worse than a clean start.
            if (file.length() > maxBytes) return null
            TrexSnapshotCodec.decode(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun save(snapshot: TrexSnapshot): Unit = synchronized(FILE_LOCK) {
        val temporary = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            file.parentFile?.mkdirs()
            temporary.writeText(TrexSnapshotCodec.encode(snapshot), Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                // Windows-style filesystems refuse a rename onto an existing name. Deleting first
                // opens a window where neither file is in place, so it is the fallback rather than
                // the default.
                file.delete()
                if (!temporary.renameTo(file)) temporary.delete()
            }
        } catch (_: IOException) {
            temporary.delete()
        } catch (_: SecurityException) {
            temporary.delete()
        }
    }

    private companion object {
        /**
         * Guards the file, not the instance. There is one store file in this app, so one lock is
         * enough; two stores over unrelated paths would contend harmlessly.
         */
        val FILE_LOCK = Any()

        const val TEMP_SUFFIX = ".tmp"

        /** Far above any real snapshot; this is a tamper and corruption bound, not a budget. */
        const val DEFAULT_MAX_BYTES = 1L * 1024 * 1024
    }
}
