// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the pure rhythm-factor -> beep-count mapping in [ModeBeepPlayer].
 *
 * Tone generation itself depends on Android audio hardware and is not covered here.
 */
@RunWith(RobolectricTestRunner::class)
class ModeBeepPlayerTest {

    private val player = ModeBeepPlayer()

    @Test
    fun binaryFactorsMapToTwoBeeps() {
        assertEquals(2, player.beepCountForFactor(1.0f))
        assertEquals(2, player.beepCountForFactor(2.0f))
    }

    @Test
    fun ternaryFactorsMapToThreeBeeps() {
        assertEquals(3, player.beepCountForFactor(1.5f))
        assertEquals(3, player.beepCountForFactor(3.0f))
    }

    @Test
    fun unknownFactorsMapToZeroBeeps() {
        assertEquals(0, player.beepCountForFactor(0.0f))
        assertEquals(0, player.beepCountForFactor(-1.0f))
        assertEquals(0, player.beepCountForFactor(0.5f))
        assertEquals(0, player.beepCountForFactor(4.0f))
    }
}