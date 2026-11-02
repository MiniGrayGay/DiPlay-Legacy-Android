package com.shilapi.xcertplay.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The availability table is a claim about the outside world, so it is pinned: if someone flips HiCar to
 * `available = true` without a licensed SDK behind it, this fails instead of the settings screen grow a
 * button that cannot work.
 */
class PhoneLinkProtocolTest {

    @Test
    fun carlifeAndCarplayAreOffered() {
        val offered = PhoneLinkProtocol.offered()
        assertTrue(offered.contains(PhoneLinkProtocol.CARPLAY))
        assertTrue(offered.contains(PhoneLinkProtocol.CARLIFE))
        assertTrue(offered.contains(PhoneLinkProtocol.ADB_MIRROR))
    }

    @Test
    fun huaweiAndTheAllianceAreBlockedWithAReason() {
        val blocked = PhoneLinkProtocol.blocked()
        assertEquals(
            setOf(PhoneLinkProtocol.HICAR, PhoneLinkProtocol.ICCOA_CARLINK),
            blocked.toSet(),
        )
        blocked.forEach {
            assertFalse(it.name, it.available)
            assertTrue(it.name, !it.blockedReason.isNullOrBlank())
        }
    }

    @Test
    fun everythingOfferedCarriesNoExcuse() {
        PhoneLinkProtocol.offered().forEach {
            assertNull(it.name, it.blockedReason)
        }
    }

    @Test
    fun theTwoSetsCoverEveryProtocolExactlyOnce() {
        val all = PhoneLinkProtocol.entries.toSet()
        assertEquals(all, (PhoneLinkProtocol.offered() + PhoneLinkProtocol.blocked()).toSet())
        assertTrue(PhoneLinkProtocol.offered().intersect(PhoneLinkProtocol.blocked().toSet()).isEmpty())
    }

    @Test
    fun lookupByStoredNameCircle() {
        assertEquals(PhoneLinkProtocol.CARLIFE, PhoneLinkProtocol.fromName("CARLIFE"))
        assertNull(PhoneLinkProtocol.fromName("CARLIFE_V2"))
        assertNull(PhoneLinkProtocol.fromName(null))
    }
}
