package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Command/reply strings below are cited from the Meade "Telescope Serial Command Protocol"
 * reference (the LX200 command set widely mirrored by mount vendors, DSC/encoder boxes, and
 * OnStep controllers) -- not a recomputation using [Lx200Codec]'s own formulas, matching this
 * repo's existing astro/ testing convention.
 */
class Lx200CodecTest {

    @Test
    fun fixedCommandsMatchTheProtocol() {
        assertEquals(":GR#", Lx200Codec.getRightAscension())
        assertEquals(":GD#", Lx200Codec.getDeclination())
        assertEquals(":MS#", Lx200Codec.slewToTarget())
        assertEquals(":Q#", Lx200Codec.abortSlew())
    }

    @Test
    fun encodesTargetRightAscensionAsHighPrecisionWire() {
        assertEquals(":Sr 18:36:56#", Lx200Codec.setTargetRightAscension(Angle.ofHms(18, 36, 56.0)))
        assertEquals(":Sr 00:00:00#", Lx200Codec.setTargetRightAscension(Angle.ZERO))
    }

    @Test
    fun encodesTargetDeclinationSignedWithAsteriskDelimiter() {
        assertEquals(":Sd +38*47:01#", Lx200Codec.setTargetDeclination(Angle.ofDms(38, 47, 1.0)))
        assertEquals(":Sd -05*30:00#", Lx200Codec.setTargetDeclination(Angle.ofDms(-5, 30, 0.0)))
        assertEquals(":Sd +00*00:00#", Lx200Codec.setTargetDeclination(Angle.ZERO))
    }

    @Test
    fun parsesHighPrecisionRightAscensionReply() {
        val ra = Lx200Codec.parseRightAscension("18:36:56")
        assertEquals(Angle.ofHms(18, 36, 56.0).degrees, ra.degrees, 1e-9)
    }

    @Test
    fun parsesLowPrecisionRightAscensionReply() {
        // "06:30.5" -- HH:MM.T, T = tenths of a minute (30.5 minutes = 30m 30s)
        val ra = Lx200Codec.parseRightAscension("06:30.5")
        assertEquals(Angle.ofHms(6, 30, 30.0).degrees, ra.degrees, 1e-9)
    }

    @Test
    fun parsesDeclinationReplyWithApostropheDelimiter() {
        val dec = Lx200Codec.parseDeclination("+38*47'01")
        assertEquals(Angle.ofDms(38, 47, 1.0).degrees, dec.degrees, 1e-9)
    }

    @Test
    fun parsesDeclinationReplyWithColonDelimiter() {
        val dec = Lx200Codec.parseDeclination("+38*47:01")
        assertEquals(Angle.ofDms(38, 47, 1.0).degrees, dec.degrees, 1e-9)
    }

    @Test
    fun parsesLowPrecisionDeclinationReplyWithoutSeconds() {
        val dec = Lx200Codec.parseDeclination("-05*30")
        assertEquals(-5.5, dec.degrees, 1e-9)
    }

    @Test
    fun targetSetAckIsInvertedRelativeToSlewAck() {
        // :Sr#/:Sd# -- "1" means accepted, "0" means invalid (opposite of :MS#'s "0" = success).
        assertTrue(Lx200Codec.parseTargetSetAck("1"))
        assertFalse(Lx200Codec.parseTargetSetAck("0"))
    }
}
