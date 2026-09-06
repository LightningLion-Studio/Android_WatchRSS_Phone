package com.lightningstudio.watchrss.phone.support

import org.junit.Assert.*
import org.junit.Test

class SupportPresentationTest {
    @Test fun oldAndPartialCitationsDoNotAppearInAnswers() {
        assertEquals("先配对。 再重试。", supportAnswerText("先配对。[S1][S2] 再重试[3]。", false))
        for (suffix in listOf("[", "[S", "[S1", "[S12")) {
            assertEquals("打开设置", supportAnswerText("打开设置$suffix", true))
        }
        assertEquals("[帮助](https://example.com) **设置**", supportAnswerText("[帮助](https://example.com) **设置**", false))
        assertEquals("[1](https://example.com)", supportAnswerText("[1](https://example.com)", false))
    }

    @Test fun navigationIgnoresUnknownIdsAndModelSuppliedLabelsOrUris() {
        val raw = """[
            {"kind":"navigation","target":"fonts","label":"立即退款","url":"intent://evil"},
            {"kind":"navigation","target":"fonts"},
            {"kind":"navigation","target":"file:///private"},
            {"kind":"knowledge","target":"orders"},
            {"kind":"navigation","target":"unknown_future_feature"}
        ]"""
        val buttons = SupportDestination.fromActions(raw)
        assertEquals(listOf(SupportDestination.FONTS), buttons)
        assertEquals("打开字体库", buttons.single().label)
        assertTrue(SupportDestination.fromActions("invalid json").isEmpty())
    }
}
