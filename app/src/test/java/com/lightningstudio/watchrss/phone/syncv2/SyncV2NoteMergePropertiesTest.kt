package com.lightningstudio.watchrss.phone.syncv2

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncV2NoteMergePropertiesTest {
    private class Random32(private var state: Int) {
        fun next(limit: Int): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state.toUInt() % limit.toUInt()).toInt()
        }
    }

    private data class Edit(val start: Int, val end: Int, val added: List<String>, val text: String) {
        fun removes(index: Int) = index in start until end
    }

    @Test
    fun notesRetainBothEditsAndConvergeForEverySharedSeed() {
        val properties = SyncV2Fixtures.vector("notes-merge").getJSONObject("properties")
        val seeds = properties.getJSONArray("seeds")
        val digest = MessageDigest.getInstance("SHA-256")
        val prefixes = listOf("x", "z", "𐀀", "\uE000")
        for (seedIndex in 0 until seeds.length()) {
            val seed = seeds.getLong(seedIndex)
            val random = Random32(seed.toInt())
            repeat(properties.getInt("casesPerSeed")) { index ->
                val label = "seed $seed, case $index"
                val base = List(1 + random.next(8)) { "b$it" }
                fun edit(side: String): Edit {
                    val start = random.next(base.size + 1)
                    val removed = random.next(base.size - start + 1)
                    val count = if (removed == 0) 1 + random.next(3) else random.next(4)
                    val prefix = prefixes[random.next(prefixes.size)]
                    val added = List(count) { "$prefix$side$it" }
                    val end = start + removed
                    val text = (base.take(start) + added + base.drop(end)).joinToString("\n")
                    return Edit(start, end, added, text)
                }
                val left = edit("L")
                val right = edit("R")
                val ancestor = base.joinToString("\n")
                fun merge(local: String, remote: String) =
                    SyncV2Business.mergeNote(ancestor, local, remote).let {
                        if (it.has("conflict")) null else it.getString("markdown")
                    }
                val result = merge(left.text, right.text)
                assertEquals(label, result, merge(right.text, left.text))
                val identical = left.text == right.text
                val overlaps = left.start < right.end && right.start < left.end
                assertEquals(label, !identical && overlaps, result == null)
                if (result != null) {
                    val surviving = base.filterIndexed { row, _ -> !left.removes(row) && !right.removes(row) }
                    val expected = surviving + left.added + if (identical) emptyList() else right.added
                    val lines = if (result.isEmpty()) emptyList() else result.split('\n')
                    assertEquals(label, expected.sorted(), lines.sorted())
                }
                for (text in listOf(ancestor, left.text, right.text)) digest.update("$text\u0000".toByteArray())
                digest.update((if (result == null) "C\u0000" else "M$result\u0000").toByteArray())
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(properties.getString("outputSha256"), sha256)
    }
}
