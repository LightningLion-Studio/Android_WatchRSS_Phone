package com.lightningstudio.watchrss.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private fun literalSpan(content: String): String =
    """<span data-watchrss-source-v2="1" style="white-space:pre-wrap">$content</span>"""

@RunWith(Parameterized::class)
class NoteEditorR6LiteralTest(private val content: String, private val position: Int) {
    @Test fun literalSpanRemainsVisibleAfterSaveAndFirstLineEdit() {
        val rows = mutableListOf("6. numbered", "plain")
        rows.add(position, literalSpan(content))
        val input = rows.joinToString("\n")
        val draft = NoteEditorDraft("6. numbered\nplain")
        assertTrue(draft.sourceMode)
        draft.editSource(input)
        val stored = draft.storageMarkup()
        assertEquals("First save must preserve the user's bytes", input, stored)
        val reopened = NoteEditorDraft(stored, preferredSourceMode = draft.sourceMode)
        assertTrue(reopened.sourceMode)
        assertEquals("The entire literal line must be visible after reopening", input, reopened.sourceText)

        val edited = input.replace("6. numbered", "6. NUMBERED")
        reopened.editSource(reopened.sourceText.replace("6. numbered", "6. NUMBERED"))
        assertEquals(edited, reopened.storageMarkup())
        assertEquals(edited, NoteEditorDraft(reopened.storageMarkup(), preferredSourceMode = true).sourceText)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index} [{0}, line {1}]")
        fun samples(): List<Array<Any>> = listOf("literal", "中文", "x &amp; y", "👩🏽‍💻", "尾部  ")
            .flatMap { content -> (0..2).map { position -> arrayOf<Any>(content, position) } }
    }
}

class NoteEditorR6SecondSaveTest {
    @Test fun multilineEditPreservesLiteralSpanOnSecondSave() {
        val literal = literalSpan("literal")
        val draft = NoteEditorDraft("6. numbered\nplain")
        val input = "6. numbered\n$literal\nplain"
        draft.editSource(input)
        val stored = draft.storageMarkup()
        assertEquals(input, stored)

        val reopened = NoteEditorDraft(stored, preferredSourceMode = true)
        reopened.editSource(reopened.sourceText.replace("6. numbered", "6. NUMBERED\nextra")
            .replace("\nplain", "\nPLAIN"))
        val expected = "6. NUMBERED\nextra\n$literal\nPLAIN"
        val secondSave = reopened.storageMarkup()
        assertEquals("An untouched literal line must survive the second save", expected, secondSave)
        assertEquals(expected, NoteEditorDraft(secondSave, preferredSourceMode = true).sourceText)
    }
}
