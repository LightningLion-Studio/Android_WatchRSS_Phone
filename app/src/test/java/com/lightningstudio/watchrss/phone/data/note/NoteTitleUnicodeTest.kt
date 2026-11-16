package com.lightningstudio.watchrss.phone.data.note
import org.junit.Assert.assertEquals
import org.junit.Test
class NoteTitleUnicodeTest {
 @Test fun unicode17ExtendedGraphemeConformanceAtEveryCodePointCut() {
  var cases=0; var cuts=0
  requireNotNull(javaClass.getResourceAsStream("/GraphemeBreakTest-17.0.0.txt")).bufferedReader().useLines { lines ->
   lines.forEach { line ->
    val raw=line.substringBefore('#').trim()
    if(raw.isNotEmpty()) {
     val points=ArrayList<Int>(); val ends=ArrayList<Int>()
     for(token in raw.split(Regex("\\s+"))) when(token) { "÷" -> ends.add(points.size); "×" -> Unit; else -> points.add(token.toInt(16)) }
     fun text(n:Int)=buildString { for(i in 0 until n)appendCodePoint(points[i]) }
     val source=text(points.size)
     for(limit in 0..points.size) { val end=ends.last { it<=limit };assertEquals(raw+" limit "+limit,text(end),truncateNoteTitle(source,limit));cuts++ };cases++
    }
   }
  }
  println("GRAPHEME-CONFORMANCE cases=$cases cuts=$cuts")
 }
}
