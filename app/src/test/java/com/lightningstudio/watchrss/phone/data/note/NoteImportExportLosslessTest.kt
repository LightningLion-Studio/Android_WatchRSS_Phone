package com.lightningstudio.watchrss.phone.data.note

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.NoteLosslessSamples
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoteImportExportLosslessTest {
    @Test fun NA03ZipAndMarkdownServiceRoundTripsKeepManualTitleAndSourceBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for ((name, body) in NoteLosslessSamples.all + listOf("mixed-no-LF" to "x\r\ny\rz\nlast  ")) {
            for (zip in listOf(false, true)) {
                var repo = NoteRepository(NoteRepositoryTest.FakeNoteDao(), "origin", now = { 123L })
                repo.save("stable-note", "Manual title：手填 👨‍👩‍👧‍👦", body, "folder")
                repeat(6) { round ->
                    val previous = repo.allNotes().single()
                    val archive = if (zip) NoteImportExportService(context, repo).exportZip() else
                        MarkdownNoteCodec.export(previous).toByteArray(Charsets.UTF_8)
                    val next = NoteRepository(NoteRepositoryTest.FakeNoteDao(), "imported", now = { 456L })
                    val service = NoteImportExportService(context, next)
                    if (zip) assertEquals(1, service.importZip(archive))
                    else service.importMarkdown("filename-fallback.md", "text/markdown", archive)
                    val loaded = next.allNotes().single()
                    assertEquals("$name zip=$zip round=$round", previous.title, loaded.title)
                    assertEquals("stable-note", loaded.noteId)
                    assertEquals("folder", loaded.folderId)
                    assertArrayEquals(body.toByteArray(Charsets.UTF_8), loaded.markdown.toByteArray(Charsets.UTF_8))
                    assertEquals(MarkdownNoteCodec.sha256(body), loaded.contentHash)
                    repo = next
                }
            }
        }
    }
}
