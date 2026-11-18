package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Negotiation
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Routing
import org.json.JSONObject
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire

/** A v14 body carries article metadata. Keep those kinds together per direction.
 * A split v14 exchange must not commit a shared library cursor. */
internal data class B2KindRoute(val send: Set<String>, val receive: Set<String>) {
    val hasBinaryLibrary: Boolean get() = send.any { it in library } || receive.any { it in library }
    val needsLegacyLibrary: Boolean get() = !send.containsAll(library) || !receive.containsAll(library)
    val sendLegacyArticles: Boolean get() = B2_ARTICLE_BODY !in send
    val receiveLegacyArticles: Boolean get() = B2_ARTICLE_BODY !in receive
    val sendLegacySources: Boolean get() = B2_RSS_SOURCE !in send
    val hasBinaryAssets: Boolean get() = B2_NOTE_ASSET in send || B2_NOTE_ASSET in receive
    val hasBinaryBooks: Boolean get() = send.containsAll(B2Wire.bookKinds) && receive.containsAll(B2Wire.bookKinds)
    val isAssetOnly: Boolean get() = !hasBinaryLibrary && !hasBinaryBooks && hasBinaryAssets
    val isEmpty: Boolean get() = send.isEmpty() && receive.isEmpty()

    fun withoutLibrary() = B2KindRoute(send - library, receive - library)

    fun assetsOnly() = B2KindRoute(send.intersect(asset), receive.intersect(asset))

    companion object {
        private val library = setOf(B2_ARTICLE_BODY, B2_ARTICLE_METADATA, B2_RSS_SOURCE)
        private val asset = setOf(B2_NOTE_ASSET)
        val legacy = B2KindRoute(emptySet(), emptySet())

        fun select(probe: JSONObject, includeNoteAssets: Boolean, libraryReady: Boolean, booksReady: Boolean = false): B2KindRoute {
            if (B2Routing.classify(probe, B2Routing.Ready(true, true, true)) != B2Routing.Peer.B2) return legacy
            val available = (if (libraryReady) library else emptySet()) +
                (if (includeNoteAssets) asset else emptySet()) + (if (booksReady) B2Wire.bookKinds else emptySet())
            if (available.isEmpty()) return legacy
            val local = B2Negotiation.capability(available, available)
            val remote = probe.getJSONObject("libraryBinary")
            fun selected(direction: String): Set<String> {
                val rows = B2Negotiation.select(local, remote, direction)?.getJSONArray("kinds") ?: return emptySet()
                return (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getString("kind") }
            }
            fun wholeArticle(selection: Set<String>): Set<String> =
                if (B2_ARTICLE_BODY in selection) selection else selection - B2_ARTICLE_METADATA
            val outgoing = wholeArticle(selected("phoneToWatch"))
            val incoming = wholeArticle(selected("watchToPhone"))
            val books = outgoing.containsAll(B2Wire.bookKinds) && incoming.containsAll(B2Wire.bookKinds)
            val send = if (books) outgoing else outgoing - B2Wire.bookKinds
            val receive = if (books) incoming else incoming - B2Wire.bookKinds
            // v14 always returns its source inventory. Keep that direction on v14
            // whenever a legacy library exchange is still required.
            return B2KindRoute(send, if (send.containsAll(library) && receive.containsAll(library)) receive
                else receive - B2_RSS_SOURCE)
        }
    }
}
