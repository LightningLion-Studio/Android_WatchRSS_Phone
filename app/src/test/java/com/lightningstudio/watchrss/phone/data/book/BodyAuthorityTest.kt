package com.lightningstudio.watchrss.phone.data.book

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BodyAuthorityTest {
    @Test fun sharedRedactedVectors() {
        val vector=JSONObject(requireNotNull(javaClass.getResource("/book/previewguard.json")).readText())
        val recipe=vector.getJSONObject("fullRecipe");val unit=recipe.getString("unit");val chars=recipe.getInt("chars")
        val full=unit.repeat((chars+unit.length-1)/unit.length).take(chars)
        val excerpt=full.replace(Regex("[ \t\n\r\u000c\u000b]+")," ").trim().take(vector.getInt("excerptChars"))
        fun expand(value:String)=when(value){"full"->full;"excerpt"->excerpt;"short"->"短".repeat(vector.getInt("shortChars"));"htmlShort"->"<p>"+"短".repeat(vector.getInt("shortChars"))+"</p>";"htmlFull"->"<p>"+full+"</p>";else->value}
        val cases=vector.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val text=expand(c.getString("text"))
            assertEquals(c.getString("name"),c.getBoolean("expected"),BodyAuthority.authoritative(c.getBoolean("fileImport"),text,expand(c.getString("excerpt")),c.getBoolean("bodyEvidence"),BodyAuthority.matchingBodyDigest(text,if(c.isNull("contentHtml"))null else expand(c.getString("contentHtml")),c.getString("contentHash")),c.getBoolean("bodyAvailable")))
        }
    }
}
