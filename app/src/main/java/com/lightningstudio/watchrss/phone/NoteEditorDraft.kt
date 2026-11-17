package com.lightningstudio.watchrss.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lightningstudio.watchrss.phone.data.note.NoteEditorStorage
import com.mohamedrejeb.richeditor.model.RichTextState

/** The stored source is authoritative; rich text is only an editing projection. */
internal class NoteEditorDraft(private val original: String, preferredSourceMode: Boolean? = null) {
    val editor = RichTextState()
    private val decodedSource: String? = NoteEditorStorage.sourceText(original)
    private val originalSource: String = decodedSource ?: original
    val sourceMode: Boolean = preferredSourceMode == true ||
        (preferredSourceMode == null && decodedSource != null) ||
        loadRichAndCheckLoss(preferredSourceMode)

    private fun loadRichAndCheckLoss(preferredSourceMode: Boolean?): Boolean {
        val encoded = decodedSource == null && NoteVisualDocument.isEncoded(original)
        if (encoded && runCatching { NoteVisualDocument.decode(original, editor) }.isSuccess) return false
        return runCatching {
            val markup = originalSource.toNoteEditorMarkup()
            // A legacy editor can change visible HTML without refreshing its native snapshot.
            if (encoded || originalSource.isNoteRichHtmlMarkup()) editor.setHtml(markup)
            else editor.setMarkdown(markup)
            preferredSourceMode ?: (!encoded && !editor.toNoteStorageMarkup().toByteArray(Charsets.UTF_8)
                .contentEquals(original.toByteArray(Charsets.UTF_8)))
        }.getOrDefault(preferredSourceMode ?: true)
    }
    // annotatedString flattens paragraph breaks to spaces in rc10. Use the actual logical
    // text (including paragraph/list boundaries), never a Markdown/HTML export, for edits.
    private val initialEditorText = editor.toText()

    var sourceText by mutableStateOf(originalSource)
        private set
    var bodyEdited by mutableStateOf(false)
        private set

    fun editSource(text: String) {
        if (text != sourceText) {
            sourceText = text
            markBodyEdited()
        }
    }

    fun markBodyEdited() {
        bodyEdited = true
    }

    fun previewMarkup(): String = if (sourceMode) sourceText else editor.toNoteStorageMarkup()

    /** Authoritative save path. Never export an untouched body. */
    fun storageMarkup(exportRich: (RichTextState) -> String = { NoteVisualDocument.rich(it, original) }): String {
        // Read synchronously too: Save can run before snapshotFlow observes an IME edit.
        val textEdited = !sourceMode && editor.toText() != initialEditorText
        return when {
            !bodyEdited && !textEdited -> original
            sourceMode -> sourceText
            else -> exportRich(editor)
        }
    }
}
