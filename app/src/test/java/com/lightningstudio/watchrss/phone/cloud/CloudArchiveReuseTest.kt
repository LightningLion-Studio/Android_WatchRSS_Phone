package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudArchiveReuseTest {
    private fun reader()=JSONObject().put("presets",JSONArray()).put("fonts",JSONArray()).put("backgrounds",JSONArray()).put("deletions",JSONArray())
    private fun archive(body: String="full body", hash: String="h", deleted: Boolean=false, settings: JSONObject=reader(), html: String?=null): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name:String,text:String) { zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() }
            val row=JSONObject().put("articleId","a").put("contentHash",hash).put("independentSaved",true).put("deleted",deleted).put("contentTextEntry","bodies/a.txt")
            if (html != null) row.put("contentHtmlEntry","bodies/a.html")
            entry("articles.json",JSONArray().put(row).toString())
            entry("reader/manifest.json",settings.toString())
            entry("app_meta.json",JSONArray().put(JSONObject().put("key","first_use_at").put("value","1")).toString())
            entry("bodies/a.txt",body)
            if (html != null) entry("bodies/a.html",html)
        }
        return output.toByteArray()
    }
    @Test fun matchingBodiesAndReaderCanReuseWithoutDuplicatingArchive() {
        assertTrue(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null)),reader(),mapOf("first_use_at" to "2")))
    }
    @Test fun emptyHtmlIsEquivalentToAbsentHtmlButNonemptyHtmlIsNot() {
        val local=mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null))
        assertTrue(CloudArchiveReuse.matches(archive(html=""),local,reader(),emptyMap()))
        assertTrue(CloudArchiveReuse.matches(archive(html=" \n\t"),local,reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(html="<p>full body</p>"),local,reader(),emptyMap()))
    }
    @Test fun editsAdditionsDeletionsAndEmptyBodiesRequireNewArchive() {
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("edited",false,"full body",null)),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"changed body",null)),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"full body","<p>new HTML</p>")),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null),"b" to CloudArchiveReuse.version("h",false,"full body",null)),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",true,"full body",null)),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(body=""),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null)),reader(),emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(hash=""),mapOf("a" to CloudArchiveReuse.version("",false,"full body",null)),reader(),emptyMap()))
    }
    @Test fun changedReaderOrApplicationSettingsRequireNewArchive() {
        val changed=reader().put("presets",JSONArray().put(JSONObject().put("id","new")))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null)),changed,emptyMap()))
        assertFalse(CloudArchiveReuse.matches(archive(),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null)),reader(),mapOf("custom" to "new")))
    }
    @Test fun irrelevantCollectionOrderDoesNotForceAnotherFullBackup() {
        val a=JSONObject().put("id","a");val b=JSONObject().put("id","b")
        val left=reader().put("presets",JSONArray().put(a).put(b))
        val right=reader().put("presets",JSONArray().put(b).put(a))
        assertTrue(CloudArchiveReuse.matches(archive(settings=left),mapOf("a" to CloudArchiveReuse.version("h",false,"full body",null)),right,emptyMap()))
    }
}
