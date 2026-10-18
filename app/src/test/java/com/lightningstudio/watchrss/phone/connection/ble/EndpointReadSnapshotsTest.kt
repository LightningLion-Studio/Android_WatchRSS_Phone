package com.lightningstudio.watchrss.phone.connection.ble
import org.junit.Assert.*
import org.junit.Test
class EndpointReadSnapshotsTest {
 @Test fun `legacy MTU 23 long reads keep one descriptor and start a fresh next read`() {
  val snapshots=EndpointReadSnapshots();var issued=0
  val source=ByteArray(341){it.toByte()};val chunks=ArrayList<Byte>()
  for(offset in 0 until source.size step 22) snapshots.chunk("bonded-watch",offset,22){issued++;source.copyOf().also{it[0]=issued.toByte()}}!!.forEach(chunks::add)
  assertEquals(1,issued);assertArrayEquals(source.copyOf().also{it[0]=1},chunks.toByteArray())
  snapshots.chunk("bonded-watch",0,22){issued++;source};assertEquals(2,issued)
 }
 @Test fun `reads are isolated expire and reject offsets without revealing an old value`() {
  var time=10L;val snapshots=EndpointReadSnapshots{time}
  assertNull(snapshots.chunk("a",1,22){byteArrayOf(1)})
  snapshots.chunk("a",0,22){byteArrayOf(1,2)}
  assertNull(snapshots.chunk("b",1,22){byteArrayOf(1)})
  assertNull(snapshots.chunk("a",3,22){byteArrayOf(1)})
  time+=30001;assertNull(snapshots.chunk("a",1,22){byteArrayOf(1)})
  snapshots.chunk("a",0,22){byteArrayOf(1,2)};snapshots.remove("a");assertNull(snapshots.chunk("a",1,22){byteArrayOf(1)})
 }
 @Test fun `snapshot and returned chunks own their bytes and clear releases every connection`() {
  val snapshots=EndpointReadSnapshots();val input=byteArrayOf(1,2,3)
  val first=snapshots.chunk("a",0,1){input}!!;input[1]=9;first[0]=9
  assertArrayEquals(byteArrayOf(2,3),snapshots.chunk("a",1,22){input})
  snapshots.clear();assertNull(snapshots.chunk("a",1,22){input})
 }
 @Test fun `failed new reads invalidate the old snapshot and cache stays bounded`() {
  val snapshots=EndpointReadSnapshots()
  snapshots.chunk("a",0,1){byteArrayOf(1,2)}
  assertNull(snapshots.chunk("a",0,1){null})
  assertNull(snapshots.chunk("a",1,1){byteArrayOf(9)})
  for(i in 0 until 17)snapshots.chunk("watch-$i",0,1){byteArrayOf(1,2)}
  assertNull(snapshots.chunk("watch-0",1,1){byteArrayOf(9)})
  assertArrayEquals(byteArrayOf(2),snapshots.chunk("watch-16",1,1){byteArrayOf(9)})
  assertNull(snapshots.chunk("watch-16",0,1){ByteArray(65537)})
  assertNull(snapshots.chunk("watch-16",1,1){byteArrayOf(9)})
 }
}
