package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.nio.ByteBuffer

/** Adversarial inputs use real envelopes, signatures and the product closure entry. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2C7ReviewTest {
    private val identity=SyncV2Fixtures.identity()
    private val key=ByteArray(32){7}
    private val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(7),"aa".repeat(16),"ab".repeat(16))
    private fun code(block:()->Unit)=try{block();null}catch(e:IllegalStateException){e.message}
    @Test fun pruningStagedObjectsBeforeAddingTheCommitKeepsEveryReachableObject()=SyncV2P5Fixture().use{w->
        SyncV2Spool(w.sql).use{objects->
            objects["first"]=byteArrayOf(1);objects["discarded"]=byteArrayOf(2);objects["last"]=byteArrayOf(3)
            objects.retain(setOf("first","last"));objects["commit"]=byteArrayOf(4)
            assertArrayEquals(byteArrayOf(1),objects.getValue("first"))
            assertArrayEquals(byteArrayOf(3),objects.getValue("last"))
            assertArrayEquals(byteArrayOf(4),objects.getValue("commit"))
            assertFalse(objects.containsKey("discarded"))
        }
    }
    private fun value(id:String="note",text:String="a".repeat(300000))=JSONObject().put("noteId",id).put("folderId",JSONObject.NULL).put("title","t")
        .put("markdown",text).put("plainText","").put("baseMarkdown","").put("contentHash","ab".repeat(32)).put("baseContentHash","cd".repeat(32))
        .put("pinned",false).put("createdAt",0).put("updatedAt",0).put("modifiedBy","synthetic").put("deleted",false).put("deletedAt",0)
        .put("noteConflicts",SyncV2NoteConflicts.phone(JSONArray(),id))
    private inner class Input(text:String="a".repeat(300000)) {
        val entity=identity.entity("note","note")
        val pair=SyncV2Business.split("note","note",entity,value(text=text),null)
        val objects=linkedMapOf<String,ByteArray>()
        val mappings=linkedMapOf<String,JSONObject>()
        val children=mutableListOf<JSONObject>()
        val ids=mutableListOf<String>()
        var parent=SyncV2NoteSegments.encode(entity,pair.state.getJSONObject("payload"),pair.state.getJSONArray("requiredFeatures"),"ff".repeat(16),identity.scope,Long.MAX_VALUE){v,_->
            children.add(v);val sealed=seal(v);ids.add(sealed.first);sealed.first}.state
        fun seal(v:JSONObject):Pair<String,ByteArray> {
            val s=SyncV2Envelope.seal(identity,key,1,"state",SyncV2Codec.utf8(SyncV2Codec.canonical(v)))
            objects[s.oid]=s.bytes;return s.oid to s.bytes
        }
        fun refs(oid:String,children:List<String>) {
            val r=SyncV2Index.refs(identity,signer,oid,JSONArray(children));val id=SyncV2Codec.oid("state-refs",r)
            val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(r));objects[id]=raw
            mappings[oid]=JSONObject().put("scope",identity.scope).put("parentOid",oid).put("refsOid",id)
                .put("authorDeviceId",signer.deviceId).put("keyId",signer.keyId).put("totalBytes",raw.size)
        }
        fun forged(index:Int,change:(JSONObject)->Unit,gzip:Boolean=false) {
            val old=objects.getValue(ids[index]);val n=ByteBuffer.wrap(old,4,4).int
            val header=SyncV2Wire.parse(old.copyOfRange(8,8+n),4096,true)
            val plain=SyncV2Codec.utf8(SyncV2Codec.canonical(children[index]))
            val encoded=if(gzip)java.io.ByteArrayOutputStream().also{out->java.util.zip.GZIPOutputStream(out).use{it.write(plain)}}.toByteArray()else plain
            if(gzip)header.put("encoding","gzip").put("encodedBytes",encoded.size)
            change(header)
            val aad=SyncV2Codec.utf8(SyncV2Codec.canonical(header))
            val derived=SyncV2Codec.hkdf(key,SyncV2Codec.decode64(header.getString("salt")),SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/aead"),SyncV2Codec.sha(aad)))
            val bytes="WRS2".toByteArray()+ByteBuffer.allocate(4).putInt(aad.size).array()+aad+SyncV2Codec.encrypt(derived,SyncV2Codec.decode64(header.getString("nonce")),aad,encoded)
            val oid="state:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}";objects[oid]=bytes;ids[index]=oid
        }
        fun read(readGate:Boolean=true,rowFeatures:Boolean=false,refsHook:(()->Unit)?=null,objectHook:(String)->Unit={}):SyncV2ControlClosure.Verified {
            parent.put("noteSegments",JSONArray(ids))
            val state=seal(parent).first;for(id in ids)refs(id,emptyList());refs(state,ids)
            refsHook?.invoke()
            val meta=SyncV2Envelope.seal(identity,key,1,"meta",SyncV2Codec.utf8(SyncV2Codec.canonical(pair.meta)));objects[meta.oid]=meta.bytes
            val row=JSONObject().put("entityKey",entity).put("metaOid",meta.oid).put("stateOid",state).put("addedAtMillis",0).put("rowSequence","1").put("deleted",false)
                .put("bodyRevision",JSONObject.NULL).put("promiseId",JSONObject.NULL).put("recipeOid",JSONObject.NULL).put("opaqueContentId",JSONObject.NULL).put("contentBytes",JSONObject.NULL)
                .put("requiredFeatures",if(rowFeatures)JSONArray().put(SyncV2NoteSegments.feature)else JSONArray())
            val tree=SyncV2Index.build(identity.scope,listOf(row));objects.putAll(tree.objects)
            val signed=signer.commit(JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("treeRoot",tree.root))
            val authors=SyncV2Authors(identity){true};authors.accept(identity.signingBinding(signer,100,"0"),"0")
            return SyncV2ControlClosure(identity,authors,{key},readGate).verify(signed,{objectHook(it);objects[it]?:SyncV2Codec.fail("object_missing")},{mappings[it]?:SyncV2Codec.fail("object_missing")})
        }
    }
    @Test fun nearBudgetAsciiNotesPublishedByTheCodecRemainReadable() {
        // The phone requires all three text fields. Empty plainText/baseMarkdown plus
        // J punctuation cost 48 bytes; one ASCII byte above the remaining budget fails.
        for(size in listOf(2_085_171,2_097_100,2_097_104)) {
            val input=Input("a".repeat(size))
            val meter=SyncV2NoteSegments.measure(input.entity,input.pair.state.getJSONObject("payload"),input.pair.state.getJSONArray("requiredFeatures"))
            assertTrue(meter.streamBytes<=SyncV2NoteSegments.maxNoteBytes)
            val row=input.read().rows.single();assertFalse(row.has(SyncV2NoteWiring.ISOLATED_KEY))
            assertEquals(size,row.getJSONObject("state").getJSONObject("payload").getString("markdown").length)
        }
        val over=Input("a".repeat(2_097_105))
        assertEquals("storage_pressure",code{SyncV2NoteSegments.encode(over.entity,over.pair.state.getJSONObject("payload"),over.pair.state.getJSONArray("requiredFeatures"),"ff".repeat(16),identity.scope,SyncV2NoteSegments.maxNoteBytes)})
        assertEquals("storage_pressure",over.read().rows.single().getString(SyncV2NoteWiring.ISOLATED_KEY))
    }
    @Test fun rowLevelSegmentsFeatureReadsWhenEnabledAndIsolatesWhenDisabled() {
        assertFalse(Input().read(rowFeatures=true).rows.single().has(SyncV2NoteWiring.ISOLATED_KEY))
        assertEquals("unsupported_feature",Input().read(false,true).rows.single().getString(SyncV2NoteWiring.ISOLATED_KEY))
    }
    @Test fun authenticatedScopeFingerprintAndOriginalLengthErrorsReachTheProductEntry() {
        for((expected,mutate,gzip)in listOf(
            Triple("scope_mismatch",{h:JSONObject->h.put("scope",JSONObject(identity.scope.toString()).put("repoId","ff".repeat(16)));Unit},false),
            Triple("fingerprint_mismatch",{h:JSONObject->h.put("opaqueContentId","00".repeat(32));Unit},false),
            Triple("invalid_schema",{h:JSONObject->h.put("plainBytes",h.getInt("plainBytes")+1);Unit},true))) {
            val input=Input();input.forged(0,mutate,gzip)
            assertEquals(expected,code{input.read()})
        }
    }
    @Test fun invalidRefsOidAndUnknownRefsSignerAreProductFailures() {
        val invalid=Input();assertEquals("invalid_oid",code{invalid.read(refsHook={invalid.mappings.getValue(invalid.ids[0]).put("refsOid","bad")})})
        val unknown=Input();assertEquals("identity_conflict",code{unknown.read(refsHook={unknown.mappings.getValue(unknown.ids[0]).put("keyId","ff".repeat(16))})})
    }
    @Test fun refsHashPrecedesScopeAndBudgetErrors() {
        val input=Input()
        assertEquals("hash_mismatch",code{input.read(refsHook={
            val mapping=input.mappings.getValue(input.ids[0]);mapping.put("scope",JSONObject(identity.scope.toString()).put("repoId","ff".repeat(16)))
            val oid=mapping.getString("refsOid");input.objects[oid]=input.objects.getValue(oid).copyOf().also{it[it.size-3]=if(it[it.size-3]=='A'.code.toByte())'B'.code.toByte()else'A'.code.toByte()}
        })})
        val large=Input("a".repeat(2_100_000))
        assertEquals("hash_mismatch",code{large.read(refsHook={val id=large.ids[0];large.objects[id]=large.objects.getValue(id).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}})})
    }
    @Test fun childRefsLengthHashFailurePrecedesTheParentRefsScopeFailure() {
        val input=Input()
        assertEquals("hash_mismatch",code{input.read(refsHook={
            val parent=input.mappings.keys.single{it !in input.ids}
            input.mappings.getValue(parent).put("scope",JSONObject(identity.scope.toString()).put("repoId","ff".repeat(16)))
            val child=input.mappings.getValue(input.ids[0]);child.put("totalBytes",child.getInt("totalBytes")+1)
        })})
    }
    @Test fun oversizeSiblingPrecedesMissingSiblingAndOnlyIsolatesTheNote() {
        val input=Input();input.forged(0,{it.put("plainBytes",SyncV2NoteSegments.LIMIT+1)})
        val row=input.read(refsHook={input.objects.remove(input.ids[1])}).rows.single()
        assertEquals("object_too_large",row.getString(SyncV2NoteWiring.ISOLATED_KEY))
    }
    @Test fun transportObjectTooLargeIsIsolatedRatherThanTreatedAsMissing() {
        val input=Input()
        val row=input.read(objectHook={if(it==input.ids[0])SyncV2Codec.fail("object_too_large")}).rows.single()
        assertEquals("object_too_large",row.getString(SyncV2NoteWiring.ISOLATED_KEY))
    }
    @Test fun textThresholdIsSchemaFailureAfterEnvelopeAuthentication() {
        val input=Input();val first=input.children[0]
        first.getJSONObject("payload").put("text","a".repeat(SyncV2NoteSegments.TEXT+1))
        input.ids[0]=input.seal(first).first
        assertEquals("invalid_schema",code{input.read()})
    }
    @Test fun rawEncodingPrecedesTheSizeGate() {
        assertEquals("invalid_encoding",code{SyncV2Wire.parse(byteArrayOf(0xc0.toByte(),0xaf.toByte()),1,true)})
        assertEquals("invalid_encoding",code{SyncV2Wire.parse("{\"a\":1,\"a\":2}".toByteArray(),1,true)})
        assertEquals("object_too_large",code{SyncV2Wire.parse("{\"a\":1}".toByteArray(),1,true)})
    }
    @Test fun sealedMemoryCapNeverHidesAHashFailure() {
        val large=Input("a".repeat(7*1024*1024))
        assertEquals("storage_pressure",large.read().rows.single().getString(SyncV2NoteWiring.ISOLATED_KEY))
        val damaged=Input("a".repeat(7*1024*1024))
        assertEquals("hash_mismatch",code{damaged.read(refsHook={
            val oid=damaged.ids.last();damaged.objects[oid]=damaged.objects.getValue(oid).copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
        })})
    }
    @Test fun rawRefsEncodingAndSizeErrorsUseTheAuthenticatedProductEntry() {
        for(raw in listOf(byteArrayOf(0xc0.toByte(),0xaf.toByte()),"{\"x\":1,\"x\":2}".toByteArray(),byteArrayOf(0xc0.toByte())+ByteArray(262144))) {
            val input=Input()
            assertEquals("invalid_encoding",code{input.read(refsHook={
                val map=input.mappings.getValue(input.ids[0]);input.objects[map.getString("refsOid")]=raw;map.put("totalBytes",raw.size)
            })})
        }
        val input=Input();val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("pad","a".repeat(262144))))
        val row=input.read(refsHook={val map=input.mappings.getValue(input.ids[0]);input.objects[map.getString("refsOid")]=raw;map.put("totalBytes",raw.size)}).rows.single()
        assertEquals("object_too_large",row.getString(SyncV2NoteWiring.ISOLATED_KEY))
        val missing=Input();assertEquals("closure_incomplete",code{missing.read(refsHook={missing.objects.remove(missing.ids[1])})})
    }
    @Test fun nonNoteFeaturesAndSegmentsStillAbortTheWholeRound() {
        for(segments in listOf(false,true))SyncV2P5Fixture().use{w->
            val folder=JSONObject().put("folderId","folder").put("name","n").put("sortOrder",0).put("updatedAt",0).put("modifiedBy","synthetic").put("deleted",false).put("deletedAt",0)
            w.stateHook={id,s->if(id=="folder")if(segments)s.put("noteSegments",JSONArray().put("state:sha256:"+"aa".repeat(32)))else s.put("requiredFeatures",JSONArray().put(SyncV2NoteConflicts.feature))}
            val oid=w.commit(listOf(w.note("title"),folder),w.current.getString("commitOid"),"0","normal",null);w.setCurrent(oid,"3")
            assertEquals("unsupported_feature",code{w.actions().refresh()})
            assertEquals(0,w.ackCalls)
            assertEquals(0L,w.sql.query("SELECT COUNT(*) FROM notes").use{it.moveToFirst();it.getLong(0)})
        }
    }
    @Test fun duplicateHashKeysAreOnlyExemptForNotes() {
        val pair=SyncV2Business.split("note","note",identity.entity("note","note"),value(text="a"),null)
        pair.meta.getJSONObject("payload").put("contentHash",pair.state.getJSONObject("payload").get("contentHash"))
        assertNotNull(SyncV2Business.join("note",pair.meta,pair.state))
        assertEquals("invalid_schema",code{SyncV2Business.join("article",pair.meta,pair.state)})
    }
    @Test fun quotaIncludesOldNewGraphsSealedOverheadAndAttachments() {
        assertEquals(16384+4*100+2*200+2*300,SyncV2NoteBudget.required(100,200,300).toInt())
        val directory=java.nio.file.Files.createTempDirectory("c7-budget").toFile()
        try {
            assertEquals("storage_pressure",code{SyncV2NoteBudget.reserve(directory,1024,1023)})
            SyncV2NoteBudget.reserve(directory,1024,1024).use{assertEquals(1024L,directory.listFiles()!!.single().length())}
            assertTrue(directory.listFiles()!!.isEmpty())
        }finally{directory.deleteRecursively()}
    }
}
