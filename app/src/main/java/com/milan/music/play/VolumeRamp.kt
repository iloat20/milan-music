package com.milan.music.play

import kotlin.math.abs

/**
 * The gain values to step through when moving the player from one ReplayGain
 * correction to the next.
 *
 * ## Why a ramp at all
 *
 * `Player.volume` takes effect on the next buffer, so applying a correction once is a
 * step, not a slide. Most of the time that step is inaudible: the track change itself
 * is the bigger event. It becomes audible across a gapless boundary, where the next
 * track starts inside the previous one's correction and then jumps — a quiet track
 * following a loud one, or the reverse, produces a click-free but clearly discontinuous
 * level in the middle of continuous audio.
 *
 * ## Why a finite sequence instead of an animator
 *
 * The caller sets these values on the service scope and waits between them. Ten writes
 * over 150 ms is cheaper than an animator object, easier to reason about on a scope
 * that can be cancelled at any time, and — the reason it is a list — testable without a
 * player. The last value is the exact target rather than an interpolation of it, since
 * that is the level the track is supposed to play at.
 */
internal fun volumeRamp(from: Float, to: Float): List<Float> {
    // `coerceIn` passes NaN straight through, and `setVolume(NaN)` would leave the sink
    // with an undefined gain. Neither bound can be NaN today; full scale is the only
    // safe answer if one ever is.
    if (from.isNaN() || to.isNaN()) return listOf(1f)
    // `setVolume` clamps to [0, 1] anyway; doing it here keeps the sequence monotonic
    // instead of producing repeated clamped values that no longer move.
    val start = from.coerceIn(0f, 1f)
    val end = to.coerceIn(0f, 1f)
    // A change this small is inaudible and would cost eleven main-thread wakeups.
    if (abs(end - start) < VOLUME_RAMP_MIN_DELTA) return listOf(end)
    return (1..VOLUME_RAMP_STEPS).map { step ->
        if (step == VOLUME_RAMP_STEPS) {
            end
        } else {
            start + (end - start) * (step.toFloat() / VOLUME_RAMP_STEPS)
        }
    }
}

/** Number of gain writes in a ramp; the interval is [VOLUME_RAMP_MS] divided by this. */
internal const val VOLUME_RAMP_STEPS = 11

/** How long a ramp takes. Long enough to hear as a slide, short enough to be over. */
internal const val VOLUME_RAMP_MS = 150L

/** Below this difference the step is inaudible, so the ramp collapses to one write. */
internal const val VOLUME_RAMP_MIN_DELTA = 0.01f

/**
 * Delay between two ramp steps.
 *
 * Divided by `steps - 1`, not by `steps`: the caller waits *between* writes and not
 * after the last one, so there are one fewer intervals than there are values. Dividing
 * by the step count makes every ramp one interval shorter than [VOLUME_RAMP_MS] claims.
 * [VOLUME_RAMP_STEPS] is therefore one more than the number of intervals, which is what
 * keeps the exact product inside [VOLUME_RAMP_MS].
 */
internal const val VOLUME_RAMP_STEP_MS = VOLUME_RAMP_MS / (VOLUME_RAMP_STEPS - 1)
