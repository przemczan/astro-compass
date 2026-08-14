package com.astrocompass.guiding

import com.astrocompass.astro.Angle
import com.astrocompass.astro.ephemeris.Twilight

/** [startEpochMillis]: when the Night Wizard should default to starting. [twilightKnown]: false if
 *  [Twilight] couldn't find a crossing at this latitude/date (continuous day or night), in which
 *  case [startEpochMillis] just falls back to "now" and the UI should say so rather than implying
 *  a real twilight time was computed. */
data class NightWizardDefaultStart(val startEpochMillis: Long, val twilightKnown: Boolean)

/**
 * The Night Wizard's default start-time rule: if we're currently in the dark window between an
 * evening's nautical twilight and the following morning's nautical dawn, start now: otherwise,
 * start at the next upcoming nautical twilight. Determined by comparing the next dusk and next
 * dawn crossings *forward* from now -- whichever comes first tells us which side we're on, with
 * no separate backward search needed.
 */
object NightWizardDefaultStartTime {

    fun compute(nowEpochMillis: Long, latitude: Angle, longitude: Angle): NightWizardDefaultStart {
        val nextDusk = Twilight.nextNauticalDusk(nowEpochMillis, latitude, longitude)
        val nextDawn = Twilight.nextNauticalDawn(nowEpochMillis, latitude, longitude)
        return when {
            nextDawn != null && (nextDusk == null || nextDawn < nextDusk) ->
                NightWizardDefaultStart(nowEpochMillis, twilightKnown = true)
            nextDusk != null ->
                NightWizardDefaultStart(nextDusk, twilightKnown = true)
            else ->
                NightWizardDefaultStart(nowEpochMillis, twilightKnown = false)
        }
    }
}
