package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    for (line in File(args.single()).readLines()) {
        val fixture = JSONObject(line)
        val key = fixture.getString("case")
        val base = fixture.getString("base")
        val local = fixture.getString("local")
        val remote = fixture.getString("remote")
        val wanted = fixture.getString("expected")

        val historical = mergeBothWays(base, local, remote)
        val liveBase = fixture.getString("baseDecoded")
        val localDraft = NoteEditorDraft(liveBase, preferredSourceMode = true)
        check(localDraft.sourceMode)
        val localText = fixture.getString("localDecoded")
        localDraft.editSource(localText)
        val localSaved = localDraft.storageMarkup()
        check(localSaved == localText) { "The editor altered the local body of $key" }
        val reopened = NoteEditorDraft(localSaved, preferredSourceMode = localDraft.sourceMode)
        check(reopened.sourceMode && reopened.sourceText == localText)

        val remoteDraft = NoteEditorDraft(liveBase, preferredSourceMode = true)
        remoteDraft.editSource(fixture.getString("remoteDecoded"))
        val remoteSaved = remoteDraft.storageMarkup()
        val live = mergeBothWays(liveBase, localSaved, remoteSaved)
        if (live is MarkdownMergeResult.Merged) {
            val view = NoteEditorDraft(live.markdown, preferredSourceMode = true)
            check(view.sourceText == wanted) { "Merged body differs for $key" }
        }

        println(JSONObject().put("case", key)
            .put("outcome", outcome(historical))
            .put("mergedMarkdown", (historical as? MarkdownMergeResult.Merged)?.markdown ?: JSONObject.NULL)
            .put("liveOutcome", outcome(live))
            .put("localSaved", localSaved)
            .put("remoteSaved", remoteSaved)
            .put("liveMergedMarkdown", (live as? MarkdownMergeResult.Merged)?.markdown ?: JSONObject.NULL)
            .toString())
    }
}

private fun mergeBothWays(base: String, local: String, remote: String): MarkdownMergeResult =
    MarkdownThreeWayMerge.merge(base, local, remote).also {
        check(it == MarkdownThreeWayMerge.merge(base, remote, local))
    }

private fun outcome(result: MarkdownMergeResult) =
    if (result is MarkdownMergeResult.Merged) "merged" else "conflict"
