package com.astrocompass.telescope

import com.astrocompass.astro.Angle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(":CM#", Lx200Codec.syncToTarget())
        assertEquals(":AW#", Lx200Codec.writeAlignmentModel())
        assertEquals(":hC#", Lx200Codec.moveToHome())
    }

    /** Move and stop share the direction suffix, so a mismatch between the two would leave the
     *  mount running on an axis nothing stops. */
    @Test
    fun pairsEachMoveCommandWithTheStopForTheSameAxis() {
        assertEquals(":Mn#", Lx200Codec.startMove(TelescopeDirection.NORTH))
        assertEquals(":Ms#", Lx200Codec.startMove(TelescopeDirection.SOUTH))
        assertEquals(":Me#", Lx200Codec.startMove(TelescopeDirection.EAST))
        assertEquals(":Mw#", Lx200Codec.startMove(TelescopeDirection.WEST))
        for (direction in TelescopeDirection.entries) {
            assertEquals(
                Lx200Codec.startMove(direction).replace(":M", ":Q"),
                Lx200Codec.stopMove(direction),
            )
        }
    }

    @Test
    fun encodesEveryMoveRatePreset() {
        assertEquals(":RG#", Lx200Codec.setMoveRatePreset(MoveRatePreset.GUIDE))
        assertEquals(":RC#", Lx200Codec.setMoveRatePreset(MoveRatePreset.CENTER))
        assertEquals(":RM#", Lx200Codec.setMoveRatePreset(MoveRatePreset.FIND))
        assertEquals(":RF#", Lx200Codec.setMoveRatePreset(MoveRatePreset.FAST))
        assertEquals(":RS#", Lx200Codec.setMoveRatePreset(MoveRatePreset.SLEW))
    }

    /** `H` is at-home; the lowercase `h` OnStep uses for *homing in progress* must not match, and
     *  neither may any other flag in the string. */
    @Test
    fun readsAtHomeFromTheStatusFlags() {
        assertTrue(Lx200Codec.parseAtHome("nNpHF"))
        assertFalse(Lx200Codec.parseAtHome("nNph"))
        assertFalse(Lx200Codec.parseAtHome("NpF"))
        assertFailsWith<IllegalArgumentException> { Lx200Codec.parseAtHome("  ") }
    }

    @Test
    fun encodesTheStarCountIntoTheAlignmentCommand() {
        assertEquals(":A2#", Lx200Codec.beginAlignment(2))
        assertEquals(":A3#", Lx200Codec.beginAlignment(3))
    }

    /** A count outside 1-9 has no single-digit encoding, so it would silently go out as something
     *  else entirely (`:A10#` parses as a 1-star align with a stray parameter). */
    @Test
    fun rejectsAnUnencodableStarCount() {
        assertFailsWith<IllegalArgumentException> { Lx200Codec.beginAlignment(0) }
        assertFailsWith<IllegalArgumentException> { Lx200Codec.beginAlignment(10) }
    }

    /** OnStepX answers `:CM#` with `N/A` on success and `E1`..`E9` on refusal -- nothing else
     *  counts as accepted, including the `1` every Set-command ack uses. */
    @Test
    fun readsSyncAcceptanceFromTheReplyBody() {
        assertTrue(Lx200Codec.parseSyncAccepted("N/A"))
        assertFalse(Lx200Codec.parseSyncAccepted("E1"))
        assertFalse(Lx200Codec.parseSyncAccepted("E9"))
        assertFalse(Lx200Codec.parseSyncAccepted("1"))
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
        assertTrue(Lx200Codec.parseAck("1"))
        assertFalse(Lx200Codec.parseAck("0"))
    }

    @Test
    fun encodesDateWithTwoDigitYear() {
        assertEquals(":SC 08/16/26#", Lx200Codec.setDate(year = 2026, month = 8, day = 16))
        // The wire format's two-digit year wraps at the century -- an inherent LX200 limitation,
        // not a bug: 2000 and 1900 both encode as "00".
        assertEquals(":SC 01/01/00#", Lx200Codec.setDate(year = 2000, month = 1, day = 1))
    }

    @Test
    fun encodesTime() {
        assertEquals(":SL 23:05:09#", Lx200Codec.setTime(hour = 23, minute = 5, second = 9))
    }

    @Test
    fun encodesUtcOffsetAlwaysCalledWithZero() {
        // syncMount only ever sends 0 (see Lx200TelescopeConnection.syncMount's doc) -- this just
        // confirms zero encodes with an explicit "+" sign, not a bare "0".
        assertEquals(":SG +00#", Lx200Codec.setUtcOffset(0))
    }

    @Test
    fun encodesSiteLatitudeNorthPositive() {
        // No sign flip vs. this app's own north-positive ObserverLocation.latitude convention.
        assertEquals(":St +38*47#", Lx200Codec.setSiteLatitude(Angle.ofDms(38, 47, 1.0)))
        assertEquals(":St -05*30#", Lx200Codec.setSiteLatitude(Angle.ofDms(-5, 30, 0.0)))
    }

    @Test
    fun encodesSiteLongitudeFlippedToWestPositive() {
        // App convention is east-positive (AstroTime.localSiderealTime's doc); OnStep/LX200's
        // :Sg is west-positive (source-verified, see Lx200Codec's mount-sync section doc) -- a
        // west-of-Greenwich app longitude (negative) must encode as a positive wire value, and an
        // east-of-Greenwich one (positive) must encode as negative.
        assertEquals(":Sg +122*30#", Lx200Codec.setSiteLongitude(Angle.ofDegrees(-122.5)))
        assertEquals(":Sg -002*30#", Lx200Codec.setSiteLongitude(Angle.ofDegrees(2.5)))
    }

    @Test
    fun encodesUnpark() {
        assertEquals(":hR#", Lx200Codec.unpark())
    }

    @Test
    fun encodesEverySlewRatePresetWithNoSpaceAfterTheMnemonic() {
        // OnStep reads the preset digit positionally out of "93,n" (parameter[3]) -- the space the
        // :Sr/:Sd/:SG commands above tolerate would shift it and silently select the base rate,
        // with no reply to reveal it. Fastest is '1' (half the microseconds per step, i.e. twice
        // the speed), slowest is '5' -- see SlewRatePreset.
        assertEquals(":SX93,1#", Lx200Codec.setSlewRatePreset(SlewRatePreset.FASTEST))
        assertEquals(":SX93,2#", Lx200Codec.setSlewRatePreset(SlewRatePreset.FASTER))
        assertEquals(":SX93,3#", Lx200Codec.setSlewRatePreset(SlewRatePreset.NORMAL))
        assertEquals(":SX93,4#", Lx200Codec.setSlewRatePreset(SlewRatePreset.SLOWER))
        assertEquals(":SX93,5#", Lx200Codec.setSlewRatePreset(SlewRatePreset.SLOWEST))
    }

    @Test
    fun encodesTrackingCommands() {
        assertEquals(":Te#", Lx200Codec.setTracking(enabled = true))
        assertEquals(":Td#", Lx200Codec.setTracking(enabled = false))
        assertEquals(":GU#", Lx200Codec.getStatus())
    }

    @Test
    fun readsTrackingStateFromTheLeadingStatusFlagOnly() {
        // "not tracking" is the first flag OnStep appends, so it can only ever appear at index 0.
        assertFalse(Lx200Codec.parseTrackingEnabled("nNpHEo000"))
        assertTrue(Lx200Codec.parseTrackingEnabled("NpHEo000"))
    }

    @Test
    fun doesNotMistakeTheNoRateCompensationFlagPairForStoppedTracking() {
        // Classic OnStep emits 'r','n' for "no rate compensation" mid-string. Searching the whole
        // reply for 'n' would report a happily tracking mount as stopped; only the first character
        // means that. Regression guard for exactly that mis-parse.
        assertTrue(Lx200Codec.parseTrackingEnabled("NpHrnEo000"))
    }

    @Test
    fun rejectsAnEmptyStatusReplyRatherThanReadingItAsTracking() {
        assertFailsWith<IllegalArgumentException> { Lx200Codec.parseTrackingEnabled("") }
    }
}
