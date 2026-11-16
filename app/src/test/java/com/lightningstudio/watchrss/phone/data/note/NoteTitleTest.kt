package com.lightningstudio.watchrss.phone.data.note

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTitleTest {
    @Test fun englishTakesFirstThreeWordsKeepingOriginalSpacing() {
        assertEquals("republic 2. postpone3", deriveNoteTitle("republic 2. postpone3. data 4. charge5. standout"))
    }

    @Test fun skipsBlankLeadingLinesAndPunctuation() {
        assertEquals("Hello, big world", deriveNoteTitle("\n  \n  Hello, big world again\nsecond"))
    }

    @Test fun shortLineKeepsWholeLine() {
        assertEquals("only two", deriveNoteTitle("only two"))
    }

    @Test fun chineseIsCutByWordsNotWholeLine() {
        val title = deriveNoteTitle("今天天气很好我们一起去公园散步然后回家吃饭")
        assert(title.isNotEmpty() && title.length < "今天天气很好我们一起去公园散步然后回家吃饭".length) { title }
        assert("今天天气很好我们一起去公园散步然后回家吃饭".startsWith(title)) { title }
    }

    @Test fun emptyInputGivesEmptyTitle() {
        assertEquals("", deriveNoteTitle("   \n\t"))
    }
}
