package com.astrocompass.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionComparatorTest {

    @Test
    fun higherPatchIsNewer() {
        assertTrue(isNewerVersion("v2.3.0-beta", "v2.2.0-beta"))
        assertFalse(isNewerVersion("v2.2.0-beta", "v2.3.0-beta"))
    }

    @Test
    fun higherMinorOutranksSuffix() {
        assertTrue(isNewerVersion("v2.1.0-beta", "v2.0.0-alpha"))
    }

    @Test
    fun identicalVersionIsNotNewer() {
        assertFalse(isNewerVersion("v2.2.0-beta", "v2.2.0-beta"))
    }

    @Test
    fun stableOutranksItsOwnPreRelease() {
        assertTrue(isNewerVersion("1.2.0", "1.2.0-beta"))
        assertFalse(isNewerVersion("1.2.0-beta", "1.2.0"))
    }

    @Test
    fun leadingVIsOptionalOnEitherSide() {
        assertFalse(isNewerVersion("2.2.0-beta", "v2.2.0-beta"))
        assertTrue(isNewerVersion("v2.3.0-beta", "2.2.0-beta"))
    }

    @Test
    fun shorterVersionTreatsMissingComponentsAsZero() {
        assertTrue(isNewerVersion("v1.1", "v1.0.5"))
    }
}
