package com.lightningstudio.watchrss.phone.data.note

sealed interface MarkdownMergeResult {
    data class Merged(val markdown: String) : MarkdownMergeResult
    data object Conflict : MarkdownMergeResult
}

/** Conservative line-based diff3: it only auto-merges edits with an unambiguous shared base. */
object MarkdownThreeWayMerge {
    fun merge(base: String, local: String, remote: String): MarkdownMergeResult =
        merge(base, local, remote, syncV2 = false)

    fun mergeSyncV2(base: String, local: String, remote: String): MarkdownMergeResult =
        merge(base, local, remote, syncV2 = true)

    private fun merge(base: String, local: String, remote: String, syncV2: Boolean): MarkdownMergeResult = when {
        local == remote -> MarkdownMergeResult.Merged(local)
        local == base -> MarkdownMergeResult.Merged(remote)
        remote == base -> MarkdownMergeResult.Merged(local)
        syncV2 && base.isEmpty() -> MarkdownMergeResult.Conflict
        else -> mergeIndependentSingleHunks(base, local, remote, syncV2)
    }

    private fun mergeIndependentSingleHunks(base: String, local: String, remote: String, syncV2: Boolean): MarkdownMergeResult {
        val source = base.lines()
        fun editedLines(text: String) = if (syncV2 && text.isEmpty()) emptyList() else text.lines()
        val localHunk = singleHunk(source, editedLines(local)) ?: return MarkdownMergeResult.Conflict
        val remoteHunk = singleHunk(source, editedLines(remote)) ?: return MarkdownMergeResult.Conflict
        if (localHunk.end > remoteHunk.start && remoteHunk.end > localHunk.start) return MarkdownMergeResult.Conflict
        if (syncV2) {
            val (first, second) = listOf(localHunk, remoteHunk).sortedWith(
                compareBy<Hunk> { it.start }.thenBy { it.end }.thenComparator { a, b ->
                    compareInsertions(a.replacement, b.replacement)
                }
            )
            // A zero-width insertion at the same start precedes the replacement.
            return MarkdownMergeResult.Merged(buildList {
                addAll(source.subList(0, first.start))
                addAll(first.replacement)
                addAll(source.subList(first.end, second.start))
                addAll(second.replacement)
                addAll(source.subList(second.end, source.size))
            }.joinToString("\n"))
        }
        // A zero-width insertion touching either edge is ambiguous. Reject it
        // before applying offsets, including two inserts at the same position.
        if (localHunk.start == localHunk.end && localHunk.start in remoteHunk.start..remoteHunk.end ||
            remoteHunk.start == remoteHunk.end && remoteHunk.start in localHunk.start..localHunk.end) return MarkdownMergeResult.Conflict
        val merged = source.toMutableList()
        listOf(localHunk, remoteHunk).sortedByDescending { it.start }.forEach { hunk ->
            merged.subList(hunk.start, hunk.end).clear()
            merged.addAll(hunk.start, hunk.replacement)
        }
        return MarkdownMergeResult.Merged(merged.joinToString("\n"))
    }

    private fun compareInsertions(a: List<String>, b: List<String>): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val order = b[index].compareTo(a[index])
            if (order != 0) return order
        }
        return b.size.compareTo(a.size)
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
