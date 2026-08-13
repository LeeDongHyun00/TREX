package com.example.trex_kotlin.store

/**
 * Where the app's memory lives.
 *
 * Deliberately two methods over one value. There is no query, no partial read and no transaction
 * in this app's data, so a store that reads and writes the whole snapshot is not a simplification
 * of a richer interface — it is the shape of the problem. If a screen ever needs a filtered or
 * ranged read it cannot get by folding the list, that is the signal to put a database behind this
 * interface; [TrexSnapshot] is then the importer's input and the call sites do not move.
 */
internal interface TrexStore {

    /** The last saved snapshot, or null on first launch and for anything unreadable. */
    fun load(): TrexSnapshot?

    fun save(snapshot: TrexSnapshot)
}

/** A store for tests and previews. Counts writes so debounce behaviour can be asserted. */
internal class InMemoryTrexStore(initial: TrexSnapshot? = null) : TrexStore {

    private var snapshot: TrexSnapshot? = initial

    var saveCount: Int = 0
        private set

    override fun load(): TrexSnapshot? = snapshot

    override fun save(snapshot: TrexSnapshot) {
        this.snapshot = snapshot
        saveCount++
    }
}
