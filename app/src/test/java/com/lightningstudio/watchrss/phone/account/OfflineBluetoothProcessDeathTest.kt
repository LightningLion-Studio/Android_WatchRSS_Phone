package com.lightningstudio.watchrss.phone.account

import android.content.Context
import android.content.SharedPreferences
import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncProtocol
import com.lightningstudio.watchrss.phone.data.ble.BleDomainStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Explicit harness only: halt this isolated test worker, then reopen real fixture files in a
 * different worker. The persistence adapter is atomic and unencrypted; it tests product transaction
 * boundaries, not Android Keystore or the platform's SharedPreferences crash implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class OfflineBluetoothProcessDeathTest {
    @Test fun isolatedWorkerDeathAndRestart()=runBlocking<Unit> {
        val phase=System.getenv("RVOFFLINE3_PHASE")
        assumeTrue(!phase.isNullOrBlank())
        val root=File(requireNotNull(System.getenv("RVOFFLINE3_CRASH_DIR"))).canonicalFile
        require(root.name.startsWith("wrss-rvoffline3-"))
        root.mkdirs()
        val context=RuntimeEnvironment.getApplication()
        val prefs=R3DiskPreferences(File(root,"preferences.bin"))
        val store=EncryptedAccountSessionStore(context,"r3-crash").also {ReflectionHelpers.setField(it,"prefsRef",prefs)}
        val origin="https://crash.invalid"
        val scope=BleDomainStore.hash("$origin\nowner")
        val first=BleDomainStore.hash("$origin\nowner\nfirst")
        val second=BleDomainStore.hash("$origin\nowner\nsecond")
        fun grant(label:String)=JSONObject().put("action","syncAccount").put("account",JSONObject()
            .put("userId","owner").put("backendBaseUrl",origin).put("phoneMasked","masked")
            .put("watchAccessToken","synthetic-$label").put("accessTokenExpiresAt",Long.MAX_VALUE))
        fun halt(point:String):Nothing {
            File(root,"crash-point.txt").writeText(point)
            println("RVOFFLINE3 isolated worker halt: $point")
            System.out.flush()
            Runtime.getRuntime().halt(73)
            error("halt returned")
        }
        fun saveSnapshot(snapshot:EncryptedAccountSessionStore.BluetoothGrantSnapshot) {
            // Synthetic test data only; never load application or user preferences.
            File(root,"old-snapshot.json").writeText(JSONObject().put("key",snapshot.key).put("scope",snapshot.scope)
                .put("authorizationVersion",snapshot.authorizationVersion).put("grantVersion",snapshot.grantVersion)
                .put("raw",snapshot.raw).toString())
        }
        if(phase=="child") {
            check(!File(root,"crash-point.txt").exists())
            store.save(PhoneAccountSession("owner","masked","synthetic-account","",Long.MAX_VALUE))
            store.saveBluetoothGrant(first,grant("old-first"));store.saveBluetoothGrant(second,grant("old-second"))
            saveSnapshot(store.bluetoothGrantSnapshot(first,scope)!!)
            when(val scenario=requireNotNull(System.getenv("RVOFFLINE3_SCENARIO"))) {
                "refusal" -> {
                    prefs.afterCommit={halt("refusal-committed")}
                    store.rejectOfflineAuthorization(scope,"owner",origin)
                }
                "recovery" -> {
                    store.rejectOfflineAuthorization(scope,"owner",origin)
                    prefs.afterCommit={halt("recovery-committed")}
                    store.confirmOnlineAuthorization(scope,store.offlineAuthorizationVersion(scope))
                }
                "replacement" -> {
                    prefs.afterCommit={halt("replacement-committed")}
                    store.saveAuthorizedBluetoothGrant(first,scope,store.offlineAuthorizationVersion(scope),grant("new-first"))
                }
                "partial-frame" -> {
                    val snapshot=store.bluetoothGrantSnapshot(first,scope)!!
                    val out=FileOutputStream(File(root,"partial-frame.bin"))
                    var written=0
                    val dying=object:OutputStream() {
                        override fun write(value:Int) {
                            out.write(value);written++
                            if(written==4) {out.flush();out.fd.sync();halt("length-prefix-written")}
                        }
                    }
                    store.withCurrentBluetoothGrant(snapshot) {BluetoothSyncProtocol.writeFrame(dying,JSONObject(snapshot.raw))}
                }
                else -> error("unknown scenario $scenario")
            }
            error("crash hook did not execute")
        }
        check(phase=="restore")
        assertTrue(File(root,"crash-point.txt").exists())
        assertEquals("owner",store.load()?.userId)
        val raw=JSONObject(File(root,"old-snapshot.json").readText())
        val old=EncryptedAccountSessionStore.BluetoothGrantSnapshot(raw.getString("key"),raw.getString("scope"),
            raw.getLong("authorizationVersion"),raw.getLong("grantVersion"),raw.getString("raw"))
        fun refuseOld() {
            val bytes=ByteArrayOutputStream()
            val failure=runCatching {store.withCurrentBluetoothGrant(old) {BluetoothSyncProtocol.writeFrame(bytes,JSONObject(old.raw))}}.exceptionOrNull()
            assertTrue(failure is PhoneAccountNetworkRequired);assertEquals(0,bytes.size())
        }
        when(requireNotNull(System.getenv("RVOFFLINE3_SCENARIO"))) {
            "refusal" -> {
                assertTrue(store.offlineAuthorizationBlocked(scope));assertNull(store.bluetoothGrant(first));assertNull(store.bluetoothGrant(second));refuseOld()
            }
            "recovery" -> {
                assertFalse(store.offlineAuthorizationBlocked(scope));assertNull(store.bluetoothGrant(first));assertNull(store.bluetoothGrant(second));refuseOld()
                store.saveAuthorizedBluetoothGrant(second,scope,store.offlineAuthorizationVersion(scope),grant("restored-second"))
                assertNotNull(store.bluetoothGrantSnapshot(second,scope));refuseOld()
            }
            "replacement" -> {
                assertFalse(store.offlineAuthorizationBlocked(scope));refuseOld()
                assertEquals("synthetic-new-first",store.bluetoothGrant(first)!!.getJSONObject("account").getString("watchAccessToken"))
                assertNotNull(store.bluetoothGrantSnapshot(second,scope))
            }
            "partial-frame" -> {
                assertEquals(4L,File(root,"partial-frame.bin").length())
                assertFalse(store.offlineAuthorizationBlocked(scope))
                val snapshot=store.bluetoothGrantSnapshot(first,scope)!!
                val out=ByteArrayOutputStream()
                store.withCurrentBluetoothGrant(snapshot) {BluetoothSyncProtocol.writeFrame(out,JSONObject(snapshot.raw))}
                assertEquals("syncAccount",BluetoothSyncProtocol.readFrame(ByteArrayInputStream(out.toByteArray())).getString("action"))
                assertNotNull(store.bluetoothGrantSnapshot(second,scope))
            }
        }
        File(root,"restore-passed.txt").writeText("verified")
    }
}

/** Test-only durable adapter; commit uses fsync + atomic rename before the crash hook. */
internal class R3DiskPreferences(private val file:File):SharedPreferences {
    @Suppress("UNCHECKED_CAST")
    private var values:MutableMap<String,Any> = if(file.exists()) ObjectInputStream(FileInputStream(file)).use {it.readObject() as HashMap<String,Any>} else hashMapOf()
    var afterCommit:(()->Unit)?=null
    @Synchronized override fun getAll():MutableMap<String,*> = HashMap(values)
    @Synchronized override fun getString(key:String?,defValue:String?):String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key:String?,defValue:Int)=values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key:String?,defValue:Long)=values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key:String?,defValue:Float)=values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key:String?,defValue:Boolean)=values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key:String?)=values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?)=Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?)=Unit
    override fun edit():SharedPreferences.Editor=object:SharedPreferences.Editor {
        val changes=hashMapOf<String,Any?>();var clear=false
        override fun putString(key:String?,value:String?)=apply {changes[requireNotNull(key)]=value}
        override fun putStringSet(key:String?,values:MutableSet<String>?)=apply {changes[requireNotNull(key)]=values?.let {HashSet(it)}}
        override fun putInt(key:String?,value:Int)=apply {changes[requireNotNull(key)]=value}
        override fun putLong(key:String?,value:Long)=apply {changes[requireNotNull(key)]=value}
        override fun putFloat(key:String?,value:Float)=apply {changes[requireNotNull(key)]=value}
        override fun putBoolean(key:String?,value:Boolean)=apply {changes[requireNotNull(key)]=value}
        override fun remove(key:String?)=apply {changes[requireNotNull(key)]=null}
        override fun clear()=apply {clear=true}
        override fun apply() {commit()}
        override fun commit():Boolean=synchronized(this@R3DiskPreferences) {
            val next=if(clear)hashMapOf() else HashMap(values)
            changes.forEach {(k,v)->if(v==null)next.remove(k) else next[k]=v}
            val pending=File(file.parentFile,file.name+".pending")
            FileOutputStream(pending).use {stream->ObjectOutputStream(stream).apply {writeObject(next);flush()};stream.fd.sync()}
            Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            values=next
            afterCommit?.invoke()
            true
        }
    }
}
