package com.lightningstudio.watchrss.phone.connection.ble

/** One coherent value for all offsets of a GATT long read, per bonded connection. */
internal class EndpointReadSnapshots(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Snapshot(val at: Long, val bytes: ByteArray)
    private val values = LinkedHashMap<String, Snapshot>()
    @Synchronized fun chunk(device: String, offset: Int, maxBytes: Int, provider: () -> ByteArray?): ByteArray? {
        if (offset < 0 || maxBytes <= 0) return null
        val time = now()
        values.entries.removeAll { time < it.value.at || time - it.value.at > 30_000 }
        if (offset == 0) {
            values.remove(device)
            val bytes = provider() ?: return null
            if (bytes.isEmpty() || bytes.size > 65536) { values.remove(device); return null }
            if (!values.containsKey(device) && values.size >= 16) values.remove(values.keys.first())
            values[device] = Snapshot(time, bytes.copyOf())
        }
        val value = values[device]?.bytes ?: return null
        if (offset > value.size) return null
        return value.copyOfRange(offset, minOf(value.size, offset + maxBytes))
    }
    @Synchronized fun remove(device: String) { values.remove(device) }
    @Synchronized fun clear() { values.clear() }
}
