package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

/** The Room writer serializes commits and authenticated head changes. Capture on
 * Ready, check again on its pinned connection, and advance only for our own writes. */
internal class B2CommitWindow(
    private val prepareInbox: (suspend (InboxInput, check: () -> Unit) -> PendingInbox?)? = null,
    private val prepareLegacy: ((LegacyInput, check: () -> Unit) -> PendingInbox?)? = null,
    private val currentHead: ((Head) -> Boolean)? = null,
    private val read: () -> Version
) {
    data class InboxInput(
        val peer: String, val direction: String, val job: String, val plan: String,
        val descriptor: org.json.JSONObject, val file: java.io.File,
        val bookClosure: suspend () -> org.json.JSONObject? = { null }
    )

    data class LegacyInput(val peer: String, val version: Int, val action: String, val payload: org.json.JSONObject)

    class PendingInbox(val commit: () -> Unit, val finish: (Boolean) -> Unit)

    suspend fun prepareInbox(input: InboxInput, check: () -> Unit): PendingInbox? = prepareProtected { prepareInbox?.invoke(input, check) }

    fun prepareLegacy(input: LegacyInput, check: () -> Unit): PendingInbox? = prepareProtected { prepareLegacy?.invoke(input, check) }

    private inline fun prepareProtected(block: () -> PendingInbox?): PendingInbox? = try {
        block()
    } catch (e: IllegalStateException) {
        val mapped = B2Exception("BUSINESS_REJECTED")
        mapped.initCause(e)
        throw mapped
    }

    data class Head(val scope: String, val oid: String, val revision: String, val rollbackGeneration: String)

    data class Version(val head: Head, val selection: String, val business: String)

    private var expected = read()
    private val head = expected.head
    val bindingId: String = B2Digests.d("wrss.library-binary.v2/local-head",
        listOf(head.scope, head.oid, head.revision, head.rollbackGeneration))

    fun checkHead() {
        if (currentHead?.invoke(head) == false) fail("BUSINESS_REJECTED")
    }

    fun check() {
        checkHead()
        if (read() != expected) fail("BUSINESS_REJECTED")
    }

    fun afterBusinessWrite(): Version {
        val current = read()
        if (current.head != expected.head || current.selection != expected.selection) fail("BUSINESS_REJECTED")
        return current
    }

    fun committed(version: Version) {
        expected = version
    }
}
