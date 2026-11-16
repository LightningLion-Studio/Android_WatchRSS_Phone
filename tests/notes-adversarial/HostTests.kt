package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.data.note.*
import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import java.lang.reflect.Proxy
import java.nio.file.*
import kotlinx.coroutines.runBlocking

private class Skipped: RuntimeException()
private class Store(device:String="phone") {
 val receipts=mutableSetOf<String>()
 val rows=linkedMapOf<String,NoteEntity>();val conflicts=linkedMapOf<String,NoteConflictEntity>();var clock=100L
 var beforeWrite:(()->Unit)?=null
 val dao=Proxy.newProxyInstance(NoteDao::class.java.classLoader,arrayOf(NoteDao::class.java)){_,m,a->when(m.name){
  "note"->rows[a!![0]]
  "hasRemoteReceipt"->a!![0] in receipts
  "conflictsFor"->conflicts.values.filter{it.noteId==a!![0]}
  "commitNote", "commitRemoteNote"->{
   beforeWrite?.also{beforeWrite=null;it()}
   val id=a!![0] as String
   if(rows[id]!=a[1] || m.name=="commitNote" && a[4]!=null &&
       (a[3]!=conflicts[a[4]] || (a[3] as NoteConflictEntity).resolvedAt!=0L || (a[1] as NoteEntity).deleted))false else {
    (a[3] as NoteConflictEntity?)?.let{c->if(conflicts.values.none{sameConflictContent(it,c)})conflicts[c.conflictId]=c}
    (a[2] as NoteEntity?)?.let{rows[id]=it}
    if(m.name=="commitRemoteNote")receipts.add((a[4] as NoteRemoteReceiptEntity).snapshotHash)
    else (a[4] as String?)?.let{conflicts[it]=conflicts.getValue(it).copy(resolvedAt=a[5] as Long)}
    true
   }
  }
  "upsertNotes"->{beforeWrite?.also{beforeWrite=null;it()};(a!![0] as List<NoteEntity>).forEach{rows[it.noteId]=it};Unit}
  "upsertConflicts"->{(a!![0] as List<NoteConflictEntity>).forEach{conflicts[it.conflictId]=it};Unit}
  "resolveConflict"->{val id=a!![0] as String;conflicts[id]=conflicts.getValue(id).copy(resolvedAt=a[1] as Long);Unit}
  else->error("Unexpected DAO ${m.name}")
 }} as NoteDao
 val repo=NoteRepository(dao,device,{clock++})
 suspend fun seed(body:String="a")=repo.save("n","Manual title",body)
}
private fun changed(n:NoteEntity,text:String,time:Long)=n.copy(markdown=text,contentHash=MarkdownNoteCodec.sha256(text),plainText=text,updatedAt=time,modifiedBy="peer")
private fun equal(want:Any?,got:Any?){check(want==got){"expected=<$want> actual=<$got>"}}
private fun render(e:RichTextState):String {
 val a=e.annotatedString
 val styles=(0 until a.length).map { i ->
  val s=a.spanStyles.filter{it.start<=i&&i<it.end}.fold(SpanStyle()){v,r->v.merge(r.item)}
  listOf(s.fontSize,s.fontWeight,s.fontStyle,s.letterSpacing,s.baselineShift,s.textDecoration,if(s.color.alpha>0)s.color else null,if(s.background.alpha>0)s.background else null).joinToString("|")
 }
 return "text="+a.text+"\nparagraphs="+a.paragraphStyles+"\ncharacterStyles="+styles
}
private fun load(s:String)=RichTextState().also{if(s.isNoteRichHtmlMarkup())it.setHtml(s.toNoteEditorMarkup()) else it.setMarkdown(s.toNoteEditorMarkup())}
private fun storage(draft:Any):String = draft.javaClass.declaredMethods.single{it.name=="storageMarkup\$default"}.invoke(null,draft,null,1,null) as String
// Reopen via the real draft when pC2 is selected; reflection keeps baseline compilable.
private fun reopen(s:String):String {
 val cls=runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteEditorDraft")}.getOrNull()?:return render(load(s))
 val d=cls.getDeclaredConstructor(String::class.java).newInstance(s)
 return if(cls.getMethod("getSourceMode").invoke(d)==true)"SOURCE text="+s else render(cls.getMethod("getEditor").invoke(d) as RichTextState)
}
fun main(args:Array<String>)=runBlocking {
 if(args[0]=="--wire"){Files.write(Path.of(args[2]),NoteCloudStateCodec.encode(NoteCloudStateCodec.decode(Files.readAllBytes(Path.of(args[1])))));return@runBlocking}
 val out=Path.of(args[0]);var fail=0;var pass=0;var skip=0
 val strings=listOf("", "a", "b", "x\na", "y\na", "a\nx", "a\ny", "A\nb", "a\nB", "a\nb", "x\u0000y", "😀")
 val matrix=org.json.JSONArray()
 for(base in strings)for(local in strings)for(remote in strings){
  val r=MarkdownThreeWayMerge.merge(base,local,remote)
  matrix.put(org.json.JSONArray(listOf(base,local,remote,if(r is MarkdownMergeResult.Merged)r.markdown else org.json.JSONObject.NULL)))
 }
 val ids=org.json.JSONArray()
 for(t in listOf(listOf("n","a","x\u0000y","z","peer"),listOf("😀","a:b","x","\u0000z","")))ids.put(org.json.JSONArray(t+noteConflictId(t[0],t[1],t[2],t[3],t[4])))
 Files.writeString(out.resolve("merge-matrix.json"),org.json.JSONObject().put("cases",matrix).put("identities",ids).toString())
 suspend fun case(id:String,block:suspend()->Unit){if(System.getenv("NOTES_ADVERSARIAL_ALL")==null && Regex("^NA-(03|11|15|18|19) ").containsMatchIn(id)){println("SKIP $id (outside T-NOTES-MERGE-FIX)");skip++;return};try{block();println("PASS $id");pass++}catch(t:Throwable){if(t is Skipped){println("SKIP $id");skip++}else{println("FAIL $id ${t.message?.take(180)}");fail++}}}
 case("NA-CONTROL conservative repository vectors") {
  val fixture=System.getenv("NOTES_REPOSITORY_VECTORS") ?: throw Skipped()
  val vectors=org.json.JSONObject(Files.readString(Path.of(fixture))).getJSONArray("apply")
  fun decode(row:org.json.JSONObject)=NoteCloudStateCodec.decode(org.json.JSONObject().put("format","watchrss-notes").put("version",1).put("notes",org.json.JSONArray().put(row)).toString().toByteArray()).single()
  for(i in 0 until vectors.length()) {
   val v=vectors.getJSONObject(i);val f=Store("mini");f.clock=999
   if(!v.isNull("local")){val n=decode(v.getJSONObject("local"));f.rows[n.noteId]=n}
   val remote=decode(v.getJSONObject("remote"));f.repo.applyRemote(remote,remote.modifiedBy)
   val actual=org.json.JSONObject(String(NoteCloudStateCodec.encode(f.rows.values.toList()))).getJSONArray("notes").getJSONObject(0)
   check(actual.similar(v.getJSONObject("expected").getJSONObject("note"))){"repository vector ${v.getString("name")}: $actual"}
   val conflicts=org.json.JSONArray()
   for(c in f.conflicts.values)conflicts.put(org.json.JSONObject().put("conflictId",c.conflictId).put("noteId",c.noteId).put("baseMarkdown",c.baseMarkdown).put("localMarkdown",c.localMarkdown).put("remoteMarkdown",c.remoteMarkdown).put("remoteDeviceId",c.remoteDeviceId).put("createdAt",c.createdAt))
   check(conflicts.similar(v.getJSONObject("expected").getJSONArray("conflicts"))){"conflict vector ${v.getString("name")}: $conflicts"}
  }
 }
 case("NA-CONTROL repository direction matrix") {
  val seed=Store().seed()
  val rows=listOf("a" to "a","b" to "a","a" to "b","c" to "z","A\nb" to "a\nb","a\nB" to "a\nb").mapIndexed {i,(body,base)->
   changed(seed,body,200L+i).copy(baseMarkdown=base,baseContentHash=MarkdownNoteCodec.sha256(base),folderId="f$i",pinned=i%2==0)
  }.let { it + it[0].copy(deleted=true,deletedAt=300) + it[1].copy(deleted=true,deletedAt=301) + it[2].copy(modifiedBy="") }
  val vectors=org.json.JSONArray()
  fun wire(n:NoteEntity)=org.json.JSONObject(String(NoteCloudStateCodec.encode(listOf(n)))).getJSONArray("notes").getJSONObject(0)
  for(local in rows)for(remote in rows) {
   val f=Store();f.clock=999;f.rows[local.noteId]=local;f.repo.applyRemote(remote,remote.modifiedBy)
   val n=f.rows.getValue(local.noteId)
   vectors.put(org.json.JSONObject().put("local",wire(local)).put("remote",wire(remote)).put("expected",org.json.JSONObject()
    .put("markdown",n.markdown).put("deleted",n.deleted).put("title",n.title).put("folder",n.folderId ?: org.json.JSONObject.NULL).put("pinned",n.pinned).put("conflict",f.conflicts.isNotEmpty())))
  }
  Files.writeString(out.resolve("repository-matrix.json"),vectors.toString())
 }
 case("NA-01 insertion at replacement start must retain inserted text"){
  val r=MarkdownThreeWayMerge.merge("a","x\na","b")
  check(r==MarkdownMergeResult.Conflict||r==MarkdownMergeResult.Merged("x\nb")){"actual=$r"}
 }
 case("NA-02 equal-position insertions must converge or conflict"){
  equal(MarkdownThreeWayMerge.merge("a","x\na","y\na"),MarkdownThreeWayMerge.merge("a","y\na","x\na"))
 }
 case("NA-03 export/import retains explicit title"){
  val f=Store();val n=f.seed("body");equal(n.title,MarkdownNoteCodec.parse(MarkdownNoteCodec.export(n)).title)
 }
 case("NA-03 export/import retains body bytes independent of editor"){
  val n=Store().seed("x").copy(markdown="x\r\ny");equal(n.markdown,MarkdownNoteCodec.parse(MarkdownNoteCodec.export(n)).markdown)
 }
 case("NA-05 cloud apply must not overwrite interleaved local edit"){
  val f=Store();val n=f.seed();f.beforeWrite={f.rows["n"]=changed(n,"unsent local",300)}
  f.repo.applyRemote(changed(n,"remote",200),"peer");equal("unsent local",f.rows["n"]!!.markdown)
 }
 case("NA-06 delete versus changed body must delete or expose conflict"){
  val f=Store();val n=f.seed();f.repo.save("n",n.title,"local edit");f.repo.applyRemote(n.copy(deleted=true,deletedAt=300,updatedAt=300),"peer")
  check(f.rows["n"]!!.deleted||f.conflicts.isNotEmpty()){"live body retained, remote deletion silently ignored"}
 }
 case("NA-07 sequential peer edits must use shared immediate ancestor"){
  val f=Store();val n=f.seed();val b=f.repo.save("n",n.title,"b");val c=changed(b,"c",300).copy(baseMarkdown="b",baseContentHash=b.contentHash)
  equal(MarkdownMergeResult.Merged("c"),f.repo.applyRemote(c,"peer"))
 }
 case("NA-08 resolved remote replay must not reopen same conflict"){
  val f=Store();val n=f.seed();f.repo.save("n",n.title,"b");val remote=changed(n,"c",300);f.repo.applyRemote(remote,"peer")
  f.repo.resolveConflict(f.conflicts.values.single(),"resolved");f.repo.applyRemote(remote,"peer")
  equal(0,f.conflicts.values.count{it.resolvedAt==0L})
 }
 case("NA-09 clock rollback must not erase local metadata change"){
  val f=Store();f.clock=1000;val n=f.seed();f.clock=100;f.repo.setPinned("n",true);f.repo.applyRemote(n,"peer");equal(true,f.rows["n"]!!.pinned)
 }
 case("NA-10 independent body merge must carry uncontested peer folder"){
  val f=Store();val n=f.seed("a\nb");f.repo.save("n",n.title,"A\nb");val c=changed(n,"a\nB",300).copy(title="Peer title",folderId="peer-folder")
  f.repo.applyRemote(c,"peer");equal("peer-folder",f.rows["n"]!!.folderId)
 }
 case("NA-CONTROL JSON unicode and line-ending roundtrip"){
  val n=Store().seed().copy(markdown="\uFEFF\t\u00A0\u3000\u200B\r\n\r\u2028\u2029e\u0301é👨‍👩‍👧‍👦אבג\\\"\u0000")
  var x=n;repeat(6){x=NoteCloudStateCodec.decode(NoteCloudStateCodec.encode(listOf(x))).single()};equal(n.markdown,x.markdown)
 }

 case("NA-16 NUL-separated conflict IDs must not erase another conflict"){
  val f=Store();val n=f.seed();f.repo.save("n",n.title,"x\u0000y");f.repo.applyRemote(changed(n,"z",300),"peer")
  f.repo.save("n",n.title,"x");f.repo.applyRemote(changed(n,"y\u0000z",400),"peer");equal(2,f.conflicts.size)
 }
 case("NA-15 title cap must not split a Unicode surrogate pair"){
  val cls=runCatching{Class.forName("com.lightningstudio.watchrss.phone.data.note.NoteTitleKt")}.getOrNull()
  if(cls==null)throw Skipped()
  if(cls!=null){val m=cls.getDeclaredMethod("deriveNoteTitle",String::class.java,Int::class.javaPrimitiveType);val title=m.invoke(null,".".repeat(79)+"😀",3) as String;check(!title.last().isHighSurrogate()){"title ends with an unpaired high surrogate"}}
 }
 val operations=listOf<Pair<String,(RichTextState)->Unit>>(
  "paste-number" to {it.replaceSelectedText("1. a\n6. b")},
  "paste-heading" to {it.replaceSelectedText("# a")},
  "paste-link" to {it.replaceSelectedText("[a](https://example.invalid)")},
  "paste-hardbreak" to {it.replaceSelectedText("a  \nb")},
  "underline" to {it.setText("a");it.selection=TextRange(0,1);it.toggleSpanStyle(SpanStyle(textDecoration=TextDecoration.Underline))},
  "alignment-center" to {it.setText("a");it.selection=TextRange(0,1);it.addParagraphStyle(ParagraphStyle(textAlign=TextAlign.Center))},
  "highlight" to {it.setText("a");it.selection=TextRange(0,1);it.addSpanStyle(SpanStyle(background=Color.Yellow))},
  "bold" to {it.setText("a");it.selection=TextRange(0,1);it.toggleSpanStyle(SpanStyle(fontWeight=FontWeight.Bold))},
  "heading" to {it.setText("a");it.selection=TextRange(0,1);it.addSpanStyle(SpanStyle(fontSize=2.em,fontWeight=FontWeight.Bold))},
  "ordered-enter" to {it.setText("a");it.selection=TextRange(1);it.toggleOrderedList();it.addTextAfterSelection("\nb")},
  "ordered-delete-item" to {it.setMarkdown("1. a\n2. b\n3. c");it.removeTextRange(TextRange(5,10))},
  "ordered-remove-format" to {it.setMarkdown("1. a\n2. b\n3. c");it.selection=TextRange(0,it.annotatedString.length);it.removeOrderedList()},
  "empty-enter" to {it.setText("a");it.selection=TextRange(1);it.addTextAfterSelection("\n")}
 )
 for((name,operation) in operations)case("NA-11 render/$name"){
  val draftClass=runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteEditorDraft")}.getOrNull()
  val draft=draftClass?.getDeclaredConstructor(String::class.java)?.newInstance("")
  val e=if(draft==null)load("") else draftClass!!.getMethod("getEditor").invoke(draft) as RichTextState
  if(draft!=null)check(draftClass!!.getMethod("getSourceMode").invoke(draft)==false){"new draft unexpectedly starts in source mode"}
  operation(e);draftClass?.getMethod("markBodyEdited")?.invoke(draft)
  val before=render(e)
  val stored=if(draft==null)e.toNoteStorageMarkup() else storage(draft)
  val after=reopen(stored)
  Files.writeString(out.resolve("$name-before.txt"),before);Files.writeString(out.resolve("$name-storage.txt"),stored);Files.writeString(out.resolve("$name-after.txt"),after)
  equal(before,after)
 }
 case("NA-18 peer visible-HTML edit must not be overwritten by stale embedded editor state"){
  val cls=runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteEditorDraft")}.getOrNull()?:throw Skipped()
  if(runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteVisualDocument")}.isFailure)throw Skipped()
  val first=cls.getDeclaredConstructor(String::class.java).newInstance("")
  val e=cls.getMethod("getEditor").invoke(first) as RichTextState;e.replaceSelectedText("a");cls.getMethod("markBodyEdited").invoke(first)
  val encoded=storage(first);check(encoded.contains(">a<")){"fixture visible fallback missing"}
  val peer=encoded.replace(">a<",">b<")
  val next=cls.getDeclaredConstructor(String::class.java).newInstance(peer);val n=cls.getMethod("getEditor").invoke(next) as RichTextState
  n.selection=TextRange(n.annotatedString.length);n.replaceSelectedText("c");cls.getMethod("markBodyEdited").invoke(next)
  val saved=storage(next);Files.writeString(out.resolve("NA-18-peer.html"),peer);Files.writeString(out.resolve("NA-18-resaved.html"),saved)
  equal("bc",n.annotatedString.text);check(saved.contains(">bc<"))
 }
 case("NA-19 malformed visual wrapper must not open as an empty rich document"){
  val cls=runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteEditorDraft")}.getOrNull()?:throw Skipped()
  if(runCatching{Class.forName("com.lightningstudio.watchrss.phone.NoteVisualDocument")}.isFailure)throw Skipped()
  val text="<div data-watchrss-editor-v1=\"";val d=cls.getDeclaredConstructor(String::class.java).newInstance(text)
  val e=cls.getMethod("getEditor").invoke(d) as RichTextState
  check(cls.getMethod("getSourceMode").invoke(d)==true||e.annotatedString.text.isNotEmpty()){"nonempty malformed body opened as empty rich document"}
 }
 println("TOTAL pass=$pass fail=$fail skip=$skip");if(fail>0)kotlin.system.exitProcess(1)
}
