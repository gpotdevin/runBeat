// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits short audible beeps to signal which rhythm mode a track will run in.
 *
 * Two beeps denote Binary mode (rhythm factor 1.0 or 2.0), three beeps denote
 * Ternary mode (factor 1.5 or 3.0). The beeps are produced with the built-in
 * [ToneGenerator] on [AudioManager.STREAM_MUSIC], so no audio assets are required.
 *
 * The pure mapping from a rhythm factor to a beep count lives in
 * [beepCountForFactor] so it can be unit-tested without Android audio hardware.
 */
@Singleton
class ModeBeepPlayer @Inject constructor() {

    companion object {
        private const val TAG = "ModeBeepPlayer"

        // Tone produced for each beep and its duration / spacing.
        private const val TONE_TYPE = ToneGenerator.TONE_PROP_BEEP
        private const val TONE_DURATION_MS = 135
        private const val BEEP_SPACING_MS = 250L

        // Binary rhythm factors (run on every beat or half-beat).
        private val BINARY_FACTORS = setOf(1.0f, 2.0f)
        // Ternary rhythm factors (dotted quarter or sixth note).
        private val TERNARY_FACTORS = setOf(1.5f, 3.0f)
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var toneGenerator: ToneGenerator? = null

    /**
     * Map a cadence-matcher rhythm factor to the number of beeps that signal the
     * corresponding rhythm group.
     *
     * - Binary factors (1.0, 2.0) -> 2 beeps
     * - Ternary factors (1.5, 3.0) -> 3 beeps
     * - Unknown / invalid factor -> 0 beeps (no signal)
     */
    fun beepCountForFactor(factor: Float): Int = when (factor) {
        in BINARY_FACTORS -> 2
        in TERNARY_FACTORS -> 3
        else -> 0
    }

    /**
     * Emit [count] short beeps. Pending beeps from a previous call are cancelled
     * first so overlapping signals never occur. Safe to call from any thread;
     * tone generation is dispatched on the main looper.
     *
     * [onStart] is invoked immediately before the first beep and [onFinish] after
     * the last beep completes (both on the main looper), so callers can duck and
     * restore external audio (e.g. music volume) around the signal.
     */
    fun beep(
        count: Int,
        onStart: (() -> Unit)? = null,
        onFinish: (() -> Unit)? = null
    ) {
        if (count <= 0) {
            onStart?.invoke()
            onFinish?.invoke()
            return
        }
        handler.removeCallbacksAndMessages(null)
        // Duck audio before the first tone.
        handler.post {
            onStart?.invoke()
            for (i in 0 until count) {
                val delayMs = i * BEEP_SPACING_MS
                handler.postDelayed({ playSingleTone() }, delayMs)
            }
            // Restore once the last tone has finished playing.
            val totalDuration = (count - 1) * BEEP_SPACING_MS + TONE_DURATION_MS
            handler.postDelayed({ onFinish?.invoke() }, totalDuration)
        }
    }

    private fun playSingleTone() {
        try {
            val generator = toneGenerator ?: ToneGenerator(
                AudioManager.STREAM_MUSIC,
                80
            ).also { toneGenerator = it }
            generator.startTone(TONE_TYPE, TONE_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play mode beep tone", e)
        }
    }

    /**
     * Release native resources. Safe to call multiple times.
     */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ToneGenerator", e)
        }
        toneGenerator = null
    }
}