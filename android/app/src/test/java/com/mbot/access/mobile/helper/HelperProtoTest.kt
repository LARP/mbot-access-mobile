package com.mbot.access.mobile.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperProtoTest {
    @Test fun onlyTheAppUidMayCallTheHelper() {
        assertTrue(HelperProto.clientAllowed(10166, 10166))
        assertFalse(HelperProto.clientAllowed(2000, 10166))      // la shell NO
        assertFalse(HelperProto.clientAllowed(0, 10166))         // root tampoco
        assertFalse(HelperProto.clientAllowed(10167, 10166))     // otra app
        assertFalse(HelperProto.clientAllowed(0, 0))             // uid de la app desconocido: nadie
        assertFalse(HelperProto.clientAllowed(-1, -1))
    }

    @Test fun onlyShellOrRootMayDeliverTheBinder() {
        assertTrue(HelperProto.serverTrusted(2000))
        assertTrue(HelperProto.serverTrusted(0))
        assertFalse(HelperProto.serverTrusted(10166))
        assertFalse(HelperProto.serverTrusted(1000))
    }

    @Test fun keyNames() {
        assertEquals(66, HelperProto.keyCode("ENTER"))
        assertEquals(66, HelperProto.keyCode("keycode_enter"))
        assertEquals(224, HelperProto.keyCode("WAKE"))
        assertEquals(26, HelperProto.keyCode("power"))
        assertEquals(4, HelperProto.keyCode("4"))
        assertEquals(187, HelperProto.keyCode("APP_SWITCH"))
        assertEquals(-1, HelperProto.keyCode("NOPE"))
    }
}
