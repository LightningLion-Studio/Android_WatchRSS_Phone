package com.lightningstudio.watchrss.phone.connection.watchs

import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchSBleSecurityTest {
    private val nonce = ByteArray(16) { (it + 16).toByte() }
    private val counter = "0000000000000001"
    private val code = ByteArray(16) { it.toByte() }
    private val key = WatchSBleSecurity.pairKey(code)
    private val pairId = WatchSBleSecurity.pairId(key)
    private val hash = WatchSBleSecurity.transcript(pairId, counter, nonce, 247, 247, "sync")

    @Test fun pairingProofsMatchContractAndCommitOnlyAfterValidAuth() {
        assertEquals("2037a90385aaa899fffad5604306195be1d9af3c3acb8a0fb1386134067e62f2", SyncV2Codec.hex(key))
        assertEquals("a0b2bee30ee1eb56c0811aa3e014aa3b", pairId)
        assertEquals("c78aa57d5bf91d0ab5e7ae29078405b330de60af21a1af932ba540fc89b963ea", SyncV2Codec.hex(WatchSBleSecurity.resetKey(key)))
        assertEquals("4da248f0a3d024568753dcb7b11acd8baf9a06e1e9f9977410826c6f4e4a8170", SyncV2Codec.hex(hash))

        val storage = PairingStorage()
        val pairings = storage.open()
        pairings.begin("owner")
        val shown = (pairings.ui.value as WatchSBlePairings.Status.Code).text
        val entered = SyncV2Codec.unhex(shown.replace(" ", "").lowercase(), 16)
        val codeKey = WatchSBleSecurity.pairKey(entered)
        val id = WatchSBleSecurity.pairId(codeKey)
        val client = hello(id)
        WatchSBleChallenge.open(client, 517, nonce) { pairings.lookup("owner", it) }.use { challenge ->
            val h = WatchSBleSecurity.transcript(id, counter, nonce, 247, 247, "sync")
            assertEquals(247, challenge.mtu)
            assertTrue(WatchSBleSecurity.matches(WatchSBleSecurity.proof(codeKey, h, WatchSBleDirection.PHONE),
                challenge.hello.getString("proof")))
            val wrong = WatchSBleSecurity.proof(key, h, WatchSBleDirection.WATCH)
            assertThrows(IllegalStateException::class.java) { challenge.verify(auth(wrong)) }
            assertFalse(challenge.authenticated)
            assertFalse(storage.state.has("active"))

            challenge.verify(auth(WatchSBleSecurity.proof(codeKey, h, WatchSBleDirection.WATCH)))
            pairings.confirm("owner", challenge.id, challenge.mode, challenge.counter)
            assertEquals(WatchSBlePairings.Status.Paired, pairings.ui.value)
            val reconnect = storage.open()
            reconnect.lookup("owner", id).use { assertArrayEquals(codeKey, it.key) }
            assertThrows(IllegalStateException::class.java) {
                reconnect.confirm("owner", id, WatchSBlePairings.Mode.SYNC, counter)
            }
            reconnect.confirm("owner", id, WatchSBlePairings.Mode.SYNC, "0000000000000002")
        }
    }

    @Test fun ctrAndMacMatchContractWithDistinctKeysAndCounters() {
        val direction = WatchSBleDirection.PHONE
        assertEquals("079d6e0d2f66a49a650171b7702fe264e5c74503e13eca07ec8dfbb032b72c11", SyncV2Codec.hex(WatchSBleSecurity.derive(key, "enc", hash, direction.bytes)))
        assertEquals("1d7e73c7c804c127a7cff2847bef920c7bb109cbf6815456c449dcece01bf8aa", SyncV2Codec.hex(WatchSBleSecurity.derive(key, "mac", hash, direction.bytes)))
        assertEquals("f73403b240c8010197526cad51b4eaf00cb88f9dbd52062b2e912ba1a41413f8", SyncV2Codec.hex(WatchSBleSecurity.proof(key, hash, direction)))
        assertEquals("881aec3b02e4bb63852c92098df1b8d1087155aff5d800b87271300fe9892365", SyncV2Codec.hex(WatchSBleSecurity.proof(key, hash, direction.opposite)))
        val message = ByteArray(20) { (it + 1).toByte() }
        WatchSBleCipher(key, hash, direction).use { phone ->
            val first = phone.seal(2, message)
            assertEquals("1b538f4b8216993899c1bfd2f442aeecbb294e0af580ff091320645287866dfa088b4339d45ce6f40272f3ced55dd8c1084f5ac3", SyncV2Codec.hex(first))
            assertThrows(IllegalStateException::class.java) { phone.seal(2, message) }
            assertThrows(IllegalStateException::class.java) { phone.seal(4, message) }
            assertFalse(first.copyOfRange(0, message.size).contentEquals(phone.seal(3, message).copyOfRange(0, message.size)))
            val next = WatchSBleSecurity.transcript(pairId, "0000000000000002", nonce, 247, 247, "sync")
            WatchSBleCipher(key, next, direction).use {
                assertFalse(first.copyOfRange(0, message.size).contentEquals(it.seal(2, message).copyOfRange(0, message.size)))
            }
        }
    }

    @Test fun rejectsReplayTamperingAndWrongDirectionBeforeDecrypt() {
        val body = byteArrayOf(1, 2, 3)
        WatchSBleCipher(key, hash, WatchSBleDirection.PHONE).use { phone ->
            val sealed = phone.seal(2, body)
            WatchSBleCipher(key, hash, WatchSBleDirection.WATCH).use { watch ->
                val damaged = sealed.copyOf().also { it[0] = 7 }
                assertThrows(IllegalStateException::class.java) { watch.open(2, damaged) }
                assertThrows(IllegalStateException::class.java) { watch.open(3, sealed) }
                assertArrayEquals(body, watch.open(2, sealed))
                assertThrows(IllegalStateException::class.java) { watch.open(2, sealed) }
                assertThrows(IllegalStateException::class.java) { watch.open(3, sealed) }
                assertThrows(IllegalStateException::class.java) { phone.open(3, sealed) }
            }
            val next = WatchSBleSecurity.transcript(pairId, counter, ByteArray(16), 247, 247, "sync")
            WatchSBleCipher(key, next, WatchSBleDirection.WATCH).use {
                assertThrows(IllegalStateException::class.java) { it.open(2, sealed) }
            }
        }
    }

    @Test fun canceledExpiredOrReplacedPairingCannotCommit() {
        val storage = PairingStorage()
        val pairings = storage.open()
        pairings.begin("owner")
        val first = pendingId(pairings)
        pairings.cancel()
        assertThrows(IllegalStateException::class.java) { pairings.lookup("owner", first) }
        pairings.begin("owner")
        val second = pendingId(pairings)
        storage.time += 300_000
        assertThrows(IllegalStateException::class.java) { pairings.lookup("owner", second) }
        pairings.begin("owner")
        val third = pendingId(pairings)
        pairings.refresh("next")
        assertThrows(IllegalStateException::class.java) { pairings.lookup("next", third) }
        assertFalse(storage.state.has("active"))
    }

    @Test fun accountSwitchAndUnbindKeepOnlyResetCredentialAcrossRestart() {
        val storage = PairingStorage()
        val pairings = storage.open()
        pairings.begin("owner")
        val id = pendingId(pairings)
        val expectedReset = pairings.lookup("owner", id).use { WatchSBleSecurity.resetKey(it.key) }
        pairings.confirm("owner", id, WatchSBlePairings.Mode.SYNC, counter)

        val reopened = storage.open()
        reopened.refresh("new-account")
        assertEquals(WatchSBlePairings.Status.ResetPending, reopened.ui.value)
        assertFalse(storage.state.has("active"))
        assertThrows(IllegalStateException::class.java) { reopened.withActive("new-account", id) { } }

        val resetCounter = "0000000000000002"
        WatchSBleChallenge.open(hello(id).put("counter", resetCounter), 247, nonce) {
            reopened.lookup("new-account", it)
        }.use { challenge ->
            assertEquals(WatchSBlePairings.Mode.RESET, challenge.mode)
            val h = WatchSBleSecurity.transcript(id, resetCounter, nonce, 247, 247, "reset")
            challenge.verify(auth(WatchSBleSecurity.proof(expectedReset, h, WatchSBleDirection.WATCH)))
            assertThrows(IllegalStateException::class.java) {
                reopened.confirm("new-account", id, challenge.mode, counter)
            }
            reopened.confirm("new-account", id, challenge.mode, challenge.counter)
            reopened.forget("new-account", id, challenge.mode)
        }
        assertThrows(IllegalStateException::class.java) { storage.open().lookup("new-account", id) }

        reopened.begin("new-account")
        val replacement = pendingId(reopened)
        reopened.confirm("new-account", replacement, WatchSBlePairings.Mode.SYNC, counter)
        reopened.revoke()
        storage.open().lookup("new-account", replacement).use {
            assertEquals(WatchSBlePairings.Mode.RESET, it.mode)
        }
    }

    private fun pendingId(pairings: WatchSBlePairings): String {
        val text = (pairings.ui.value as WatchSBlePairings.Status.Code).text
        val raw = SyncV2Codec.unhex(text.replace(" ", "").lowercase(), 16)
        return WatchSBleSecurity.pairId(WatchSBleSecurity.pairKey(raw))
    }

    private fun hello(id: String) = JSONObject().put("op", "hello").put("version", 2)
        .put("pairId", id).put("counter", counter).put("mtu", 247)

    private fun auth(proof: ByteArray) = JSONObject().put("op", "auth").put("proof", SyncV2Codec.hex(proof))

    private class PairingStorage {
        var state = JSONObject()
        var time = 0L

        fun open() = WatchSBlePairings({ time }, { JSONObject(state.toString()) }, { state = JSONObject(it.toString()) })
    }
}
