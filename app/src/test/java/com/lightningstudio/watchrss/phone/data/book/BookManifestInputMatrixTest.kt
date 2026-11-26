package com.lightningstudio.watchrss.phone.data.book

import com.lightningstudio.watchrss.phone.data.importer.LocalContentImporter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.nio.charset.Charset

class BookManifestInputMatrixTest {
    private val importer=LocalContentImporter()
    private val text="前言：这是一份原创最小样本，用于排除正文编码与目录标题造成的清单错误。\n\n第1章 同名\n这是足够长的第一章正文。\n\n第2章 同名\n这是足够长的第二章正文。"
    private suspend fun fingerprint(bytes:ByteArray):String {
        val imported=importer.importFile("样本.txt","text/plain",bytes)
        val source=importer.buildTxtChapterSource(requireNotNull(imported.txtChapterPlan))!!
        return BookSync.fingerprint(source.items.mapIndexed{i,c->BookSync.chapterHash(i+1,c.title,c.contentText)})
    }
    @Test fun utf8BomUtf16AndGb18030ProduceTheSameIdentity()=runBlocking<Unit> {
        val expected=fingerprint(text.toByteArray())
        val encodings=listOf(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+text.toByteArray(),
            byteArrayOf(0xff.toByte(),0xfe.toByte())+text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(),0xff.toByte())+text.toByteArray(Charsets.UTF_16BE),
            text.toByteArray(Charset.forName("GB18030")),text.replace("\n","\r\n").toByteArray())
        for(bytes in encodings)assertEquals(expected,fingerprint(bytes))
    }
    @Test fun duplicateNamesAreValidAndEmptyInputNeverProducesAManifest()=runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java){importer.importFile("empty.txt","text/plain",byteArrayOf())}
        assertThrows(IllegalArgumentException::class.java){importer.importFile("empty.txt","text/plain"," \n".toByteArray())}
        val repeated="第1章 同名\n这是相同名称的第一章正文。\n第1章 同名\n这是相同名称的第二章正文。"
        val source=importer.buildTxtChapterSource(importer.importFile("repeat.txt","text/plain",repeated.toByteArray()).txtChapterPlan!!)!!
        assertEquals(2,source.items.size);assertEquals(source.items[0].title,source.items[1].title)
        assertNotEquals(BookSync.chapterHash(1,"same","same"),BookSync.chapterHash(2,"same","same"))
        val longTitle="标题".repeat(10000)+".txt"
        assertEquals(longTitle.removeSuffix(".txt"),importer.importFile(longTitle,"text/plain","正文".toByteArray()).source.items.single().title)
    }
    @Test fun discardedShortHeadingsExplainSparseDirectoryBeforeAnyManifestValidation()=runBlocking<Unit> {
        val input="前言：这是一份原创最小样本，用于目录间隙测试，正文长度足够。\n第1章 同名\n第一章有效正文足够长。\n第2章\n第3章 同名\n第三章有效正文足够长。"
        val source=importer.buildTxtChapterSource(importer.importFile("sparse.txt","text/plain",input.toByteArray()).txtChapterPlan!!)!!
        val indices=source.items.map{Regex("/chapter/(\\d+)").find(it.url)!!.groupValues[1].toInt()}
        assertEquals(3,indices.size);assertNotEquals((1..indices.size).toList(),indices)
    }
}
