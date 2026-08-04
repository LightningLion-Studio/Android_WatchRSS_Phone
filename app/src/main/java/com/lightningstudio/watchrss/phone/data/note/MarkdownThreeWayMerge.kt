package com.lightningstudio.watchrss.phone.data.note

sealed interface MarkdownMergeResult {
    data class Merged(val markdown: String) : MarkdownMergeResult
    data object Conflict : MarkdownMergeResult
}

/** Conservative line-based diff3: it only auto-merges edits with an unambiguous shared base. */
object MarkdownThreeWayMerge {
    fun merge(base: String, local: String, remote: String): MarkdownMergeResult = when {
        local == remote -> MarkdownMergeResult.Merged(local)
        local == base -> MarkdownMergeResult.Merged(remote)
        remote == base -> MarkdownMergeResult.Merged(local)
        else -> MarkdownMergeResult.Conflict
    }
}
