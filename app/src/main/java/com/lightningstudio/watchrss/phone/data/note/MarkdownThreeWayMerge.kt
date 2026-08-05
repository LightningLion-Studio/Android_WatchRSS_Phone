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
        else -> mergeIndependentSingleHunks(base, local, remote)
    }

    private fun mergeIndependentSingleHunks(base: String, local: String, remote: String): MarkdownMergeResult {
        val source = base.lines()
        val localHunk = singleHunk(source, local.lines()) ?: return MarkdownMergeResult.Conflict
        val remoteHunk = singleHunk(source, remote.lines()) ?: return MarkdownMergeResult.Conflict
        if (localHunk.end > remoteHunk.start && remoteHunk.end > localHunk.start) return MarkdownMergeResult.Conflict
        val merged = source.toMutableList()
        listOf(localHunk, remoteHunk).sortedByDescending { it.start }.forEach { hunk ->
            merged.subList(hunk.start, hunk.end).clear()
            merged.addAll(hunk.start, hunk.replacement)
        }
        return MarkdownMergeResult.Merged(merged.joinToString("\n"))
    }

    private fun singleHunk(base: List<String>, changed: List<String>): Hunk? {
        var prefix = 0
        while (prefix < base.size && prefix < changed.size && base[prefix] == changed[prefix]) prefix++
        var suffix = 0
        while (suffix < base.size - prefix && suffix < changed.size - prefix && base[base.lastIndex - suffix] == changed[changed.lastIndex - suffix]) suffix++
        return Hunk(prefix, base.size - suffix, changed.subList(prefix, changed.size - suffix))
    }

    private data class Hunk(val start: Int, val end: Int, val replacement: List<String>)
}
