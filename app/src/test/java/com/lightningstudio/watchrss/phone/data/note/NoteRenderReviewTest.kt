package com.lightningstudio.watchrss.phone.data.note
import org.junit.Assert.*
import org.junit.Test
class NoteRenderReviewTest {
 @Test fun RNR06_supplementaryLetterIsAWord() {
  assertEquals("RNR-06: a supplementary letter is the first word", "𐐀 alpha beta", deriveNoteTitle("𐐀 alpha beta gamma"))
 }
 @Test fun RNR07_codePointLimitPolicyDoesNotPromiseWholeGraphemes() {
  assertEquals(".".repeat(78)+"👨‍",deriveNoteTitle(".".repeat(78)+"👨‍👩‍👧‍👦"))
  assertEquals("a".repeat(79)+"e",deriveNoteTitle("a".repeat(79)+"e\u0301"))
 }
 @Test fun NA15_scalarBoundaryRetainsBothSurrogates() {
  assertEquals(".".repeat(79)+"😀",deriveNoteTitle(".".repeat(79)+"😀"))
 }
}
