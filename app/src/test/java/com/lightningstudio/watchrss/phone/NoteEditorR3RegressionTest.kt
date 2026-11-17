package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.*
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.*
import org.junit.Test

/** Regression reproducers from RVNP-R2-01 and RVNP-R2-02. */
class NoteEditorR3RegressionTest {
    @Test fun generatedRichOrderedListKeepsParentsTitleProjection() = kotlinx.coroutines.runBlocking {
        val editor = RichTextState().setMarkdown("1. 中文 e\u0301clair next\n2. tail")
        val legacy = editor.toNoteStorageMarkup()
        val snapshot = NoteVisualDocument.rich(editor)
        val legacyProjected = MarkdownNoteCodec.titlePlainText(legacy)
        val nativeProjected = MarkdownNoteCodec.titlePlainText(snapshot)
        val expected = deriveNoteTitle(legacyProjected)
        val repository = NoteRepository(NoteRepositoryTest.FakeNoteDao(), "phone", now = { 123L })
        val actual = repository.save(title = "", markdown = snapshot).title
        println("rich title: legacy=$legacy, legacyProjected=$legacyProjected, snapshotProjected=$nativeProjected, expected=$expected, actual=$actual")
        assertTrue(legacy.startsWith("1. "))
        assertEquals("Auto title must preserve the numeric first word from an ordinary ordered list", expected, actual)
        assertArrayEquals(legacyProjected.toByteArray(), nativeProjected.toByteArray())
    }

    @Test fun deletingANumberedLineKeepsSourceModeInTheLocalDatabase() = kotlinx.coroutines.runBlocking {
        val base = "6. prefix\nplain"
        val repository = NoteRepository(NoteRepositoryTest.FakeNoteDao(), "phone")
        val first = repository.save("source", "Title", base)
        val draft = NoteEditorDraft(first.markdown, repository.editorSourceMode(first.noteId))
        assertTrue(draft.sourceMode)
        draft.editSource("plain")
        val saved = repository.save(first.noteId, first.title, draft.storageMarkup(),
            editorSourceMode = draft.sourceMode)
        assertEquals("plain", saved.markdown)
        val reopened = NoteEditorDraft(saved.markdown, repository.editorSourceMode(saved.noteId))
        assertTrue(reopened.sourceMode)
        assertEquals("plain", reopened.sourceText)
    }
}
