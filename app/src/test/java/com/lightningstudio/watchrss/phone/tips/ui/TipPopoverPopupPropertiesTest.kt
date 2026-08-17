package com.lightningstudio.watchrss.phone.tips.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class TipPopoverPopupPropertiesTest {

    @Test
    fun popupRemainsNonFocusableAndUsesCustomOutsideDismissLayer() {
        assertFalse(TipPopoverPopupProperties.focusable)
        assertFalse(TipPopoverPopupProperties.dismissOnBackPress)
        assertFalse(TipPopoverPopupProperties.dismissOnClickOutside)
    }
}
