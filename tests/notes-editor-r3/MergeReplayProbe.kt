package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import com.lightningstudio.watchrss.phone.data.note.NoteEditorStorage
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    for (row in File(args[0]).readLines()) {
        val input = JSONObject(row)
        val base = input.getString("base")
        val local = input.getString("local")
        val remote = input.getString("remote")
        if (args.getOrNull(1) == "source-fixtures") {
            // Only the compiled r2 reference can interpret its experimental encoding.
            println(input.put("baseDecoded", NoteEditorStorage.sourceText(base) ?: base)
                .put("remoteDecoded", NoteEditorStorage.sourceText(remote) ?: remote))
            continue
        }
        val result = MarkdownThreeWayMerge.merge(base, local, remote)
        check(result == MarkdownThreeWayMerge.merge(base, remote, local))
        println(JSONObject().put("case", input.getString("case"))
            .put("outcome", if (result is MarkdownMergeResult.Merged) "merged" else "conflict")
            .put("mergedMarkdown", (result as? MarkdownMergeResult.Merged)?.markdown ?: JSONObject.NULL)
            .toString())
    }
}
