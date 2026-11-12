package com.example.tvremote

import com.example.tvremote.core.AppIds
import com.example.tvremote.core.Compatibility
import com.example.tvremote.core.Feature
import com.example.tvremote.core.KnownApp
import com.example.tvremote.core.TvPlatform
import com.example.tvremote.net.Pb
import com.example.tvremote.net.Pb.find
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Pure-JVM tests (no device needed). These do NOT replace testing against real TVs. */
class ProtocolTest {
    @Test fun varintRoundTrip() {
        val f = Pb.parse(Pb.int(1, 300) + Pb.int(2, 3))
        assertEquals(300L, f.find(1)!!.long); assertEquals(3L, f.find(2)!!.long)
    }

    @Test fun nestedMessageAndStrings() {
        val m = Pb.msg(10, Pb.str(1, "svc"), Pb.str(2, "client"))
        val outer = Pb.parse(m).find(10)!!
        val inner = Pb.parse(outer.data!!)
        assertEquals("svc", String(inner.find(1)!!.data!!)); assertEquals("client", String(inner.find(2)!!.data!!))
    }

    @Test fun frameRoundTrip() {
        val out = ByteArrayOutputStream(); val payload = ByteArray(200) { it.toByte() }
        Pb.writeFrame(out, payload)
        assertArrayEquals(payload, Pb.readFrame(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun everyPlatformResolvesAllKnownApps() {
        for (p in TvPlatform.entries) for (a in KnownApp.entries) assertNotNull("$p/$a", AppIds.resolve(p, a))
    }

    @Test fun compatibilityHasEntryForEveryPlatform() {
        for (p in TvPlatform.entries) assertTrue(Compatibility.base.getValue(p).contains(Feature.DPAD))
        // Android TV exposes no app list
        assertFalse(Feature.LIST_APPS in Compatibility.base.getValue(TvPlatform.ANDROID_TV))
    }
}
