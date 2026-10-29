package com.lightningstudio.watchrss.phone.data.book

import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/** Local proof only. No chapter-size threshold or cross-device fields. */
object BodyAuthority {
    private fun compact(text:String)=text.replace(Regex("[ \t\n\r\u000c\u000b]+")," ").trim(' ')
    fun previewEquivalent(text:String,excerpt:String)=excerpt.isNotEmpty() && compact(text)==compact(excerpt)
    fun authoritative(fileImport:Boolean,text:String,excerpt:String,bodyEvidence:Boolean,digestMatches:Boolean,bodyAvailable:Boolean=true):Boolean {
        if(!bodyAvailable)return false
        if(fileImport && previewEquivalent(text,excerpt))return digestMatches
        return digestMatches || bodyEvidence
    }
    fun matchingBodyDigest(text:String,html:String?,expected:String):Boolean {
        if(!expected.matches(Regex("[a-f0-9]{64}")))return false
        fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        if(sha(text)==expected)return true
        if(html.isNullOrEmpty() || sha(html)!=expected)return false
        val body=Jsoup.parseBodyFragment(html).body()
        body.select("script,style,noscript,template,svg,form,button").remove()
        body.select("p,div,article,section,h1,h2,h3,h4,h5,h6,blockquote,pre,li,tr").forEach{it.after(TextNode("\n"))}
        return previewEquivalent(body.wholeText(),text)
    }
    fun accepts(row:PhoneArticleEntity,bodyAvailable:Boolean=true):Boolean {
        val imported=ImportedContentIds.isImportedContentUrl(row.url)
        if(!imported)return bodyAvailable
        if(row.contentText.isEmpty() && row.contentHtml.isNullOrEmpty())return false
        val suspect=previewEquivalent(row.contentText,row.excerpt) || row.contentText.isEmpty()
        val matches=suspect && matchingBodyDigest(row.contentText,row.contentHtml,row.contentHash)
        return authoritative(imported,row.contentText,row.excerpt,row.contentText.isNotEmpty() || !row.contentHtml.isNullOrEmpty(),matches,bodyAvailable)
    }
}
