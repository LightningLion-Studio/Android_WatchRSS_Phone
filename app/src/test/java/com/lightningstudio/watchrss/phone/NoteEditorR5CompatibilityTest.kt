package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorR5CompatibilityTest {
    @Test fun legacyLiteralFinalLineSurvivesUnrelatedEdit() {
        val input = "6. numbered\nplain\n<!--watchrss:source-mode:v2-->"
        val draft = NoteEditorDraft(input)
        assertTrue(draft.sourceMode)
        assertEquals(input, draft.sourceText)
        val edited = input.replace("numbered", "NUMBERED")
        draft.editSource(edited)
        assertEquals(edited, draft.storageMarkup())
        assertEquals(edited, NoteEditorDraft(draft.storageMarkup()).sourceText)
    }

    @Test fun deletingANumberedLineDoesNotAddAnyBytesForOldClients() {
        val base = "6. prefix\nplain\nend"
        val draft = NoteEditorDraft(base)
        assertTrue(draft.sourceMode)
        draft.editSource("plain\nend")
        val stored = draft.storageMarkup()
        assertEquals("plain\nend", stored)
        assertEquals(MarkdownNoteCodec.sha256("plain\nend"), MarkdownNoteCodec.sha256(stored))
    }

    @Test fun storedRichPreferenceTakesPriorityOverSourceInference() {
        val body = "6. numbered"
        assertTrue(NoteEditorDraft(body).sourceMode)
        val draft = NoteEditorDraft(body, preferredSourceMode = false)
        assertEquals(false, draft.sourceMode)
        assertEquals(body, draft.storageMarkup { error("Untouched body was exported") })
    }

    @Test fun sourceEditorKeepsUntouchedHtmlAndWritesNewTextVerbatim() {
        val html = "<span>existing</span>"
        val draft = NoteEditorDraft("first\r\n$html\r\nlast")
        draft.editSource("FIRST\r\n$html\r\nLAST")
        assertEquals("FIRST\r\n$html\r\nLAST", draft.storageMarkup())
        draft.editSource("FIRST\r\nnew line\r\nLAST")
        assertEquals("FIRST\r\nnew line\r\nLAST", draft.storageMarkup())
    }
}
