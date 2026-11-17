package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Test
import org.junit.Assert.*

class BookSyncWarningTextTest {
    @Test fun warningNamesEachBookReasonAndRecoveryAction() {
        val issues=listOf(BookSyncIssue("0123abcd","book_manifest_invalid","样本书","manifest_identity_mismatch"),
            BookSyncIssue("12345678","book_package_invalid","另一本","package_invalid"))
        val warning=PhoneBluetoothSyncResult("watch",bookSyncIssues=issues).bookSyncWarning!!
        for(text in listOf("样本书","0123abcd","manifest_identity_mismatch","另一本","12345678","package_invalid",BookSyncWarningText.UPDATE,BookSyncWarningText.REIMPORT))assertTrue(text,warning.contains(text))
        assertFalse(warning.contains("可直接点同步重试"))
        assertNull(BookSyncWarningText.format(emptyList()))
    }
    @Test fun fieldAndCompatibilityFailuresExplainTheProblemInChinese() {
        for((reason,text) in listOf("manifest_updated_at_type" to BookSyncWarningText.TIME,
            "manifest_title_type" to BookSyncWarningText.TITLE,
            "manifest_instance_identity_unsupported" to BookSyncWarningText.INSTANCE)) {
            val warning=BookSyncWarningText.format(listOf(BookSyncIssue("0123abcd","book_manifest_invalid","样本书",reason)))!!
            assertTrue(warning.contains(text));assertTrue(warning.contains(reason))
            if(reason=="manifest_instance_identity_unsupported")assertTrue(warning.contains(BookSyncWarningText.INSTANCE_ACTION))
        }
    }
    @Test fun titleIsBoundedSingleLineAndMissingTitleUsesAnExplicitFallback() {
        val raw="\u202E标题\n\t"+"书".repeat(200)
        val title=BookSyncIssue.displayTitle(raw)!!
        assertTrue(title.length<=120);assertFalse(title.contains('\n'));assertFalse(title.contains('\u202E'))
        assertNull(BookSyncIssue.displayTitle(123))
        val warning=BookSyncWarningText.format(listOf(BookSyncIssue("0123abcd","book_manifest_invalid",null,"manifest_requested_body_unavailable")))!!
        assertTrue(warning.contains(BookSyncWarningText.UNKNOWN_TITLE));assertTrue(warning.contains(BookSyncWarningText.UNAVAILABLE))
    }
}
