package com.astrocompass.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.catalog.StarObject
import com.astrocompass.guiding.GuidingMode

/** How many stars a fresh run uses -- the smallest count either mode accepts, so the quickest
 *  path through the screen is also the default one. */
private const val DEFAULT_STAR_COUNT = 2

/**
 * An alignment run in progress, held by `GuiderApp` rather than remembered inside
 * [AlignmentScreen] so it survives the screen being torn down -- the top bar's own Settings button
 * does exactly that, since `showSettings` is matched ahead of `showAlignment`.
 *
 * That is a convenience for a phone run, but a correctness requirement for a mount one: the
 * mount's alignment sequence is state on the *mount*, armed by a command that re-homes it and
 * cannot be cancelled (see [com.astrocompass.telescope.TelescopeConnection.beginAlignment]). If
 * the app forgot that a sequence was already armed, the screen would offer "Start" again and a tap
 * would re-home a mount that was two stars into a good run.
 *
 * The two modes therefore keep **entirely separate** progress -- separate star counts included --
 * and [switchTo] discards neither. Clearing on a mode change would look tidy but reopens exactly
 * the hazard above through a second door: [com.astrocompass.AppContainer.guidingMode] derives to
 * [GuidingMode.PHONE] the moment a link drops, so a Bluetooth blip mid-run would wipe the app's
 * memory of an armed mount with no way to know it had.
 */
class AlignmentSession {
    private var mode by mutableStateOf(GuidingMode.PHONE)
    private val alignsMount get() = mode == GuidingMode.TELESCOPE

    private var phoneStarCount by mutableStateOf(DEFAULT_STAR_COUNT)
    private var mountStarCount by mutableStateOf(DEFAULT_STAR_COUNT)

    /** Populated in [GuidingMode.PHONE] only: the sensor/sky pairs [AlignmentScreen] solves. */
    var points by mutableStateOf(listOf<AlignmentPoint>())
        private set

    /** True in [GuidingMode.TELESCOPE] between arming the mount's sequence and finishing the run. */
    var mountAlignmentActive by mutableStateOf(false)
        private set

    /** Populated in [GuidingMode.TELESCOPE] only: the stars the mount has accepted so far. Kept
     *  as objects rather than [AlignmentPoint]s because nothing on the phone side is fitted from
     *  them -- the model lives on the mount, and these are purely what the user has done. */
    var mountAlignedStars by mutableStateOf(listOf<StarObject>())
        private set

    /** How many stars the *current* mode's run is aiming for. */
    val starCount: Int get() = if (alignsMount) mountStarCount else phoneStarCount

    /** How many it has behind it, whichever kind of evidence it collects. */
    val syncedCount: Int get() = if (alignsMount) mountAlignedStars.size else points.size

    val isComplete: Boolean get() = syncedCount == starCount

    /** False only for a mount run already armed: the count is the `n` the mount was armed with, so
     *  changing it would either re-home the mount or leave the app expecting a different number of
     *  stars than the mount is waiting for. A phone run's count is always free to change. */
    val canChangeStarCount: Boolean get() = !(alignsMount && mountAlignmentActive)

    /** Idempotent, and non-destructive by design -- see the class doc. */
    fun switchTo(newMode: GuidingMode) {
        mode = newMode
    }

    fun changeStarCount(count: Int) {
        if (!canChangeStarCount) return
        if (alignsMount) mountStarCount = count else phoneStarCount = count
        points = points.take(phoneStarCount)
    }

    fun addPoint(point: AlignmentPoint) {
        points = points + point
    }

    fun removePointAt(index: Int) {
        points = points.toMutableList().also { it.removeAt(index) }
    }

    fun markMountAlignmentArmed() {
        mountAlignmentActive = true
        mountAlignedStars = emptyList()
    }

    fun addMountAlignedStar(star: StarObject) {
        mountAlignedStars = mountAlignedStars + star
    }

    /** Ends the *current* mode's run, leaving the other one's progress alone. */
    fun clear() {
        if (alignsMount) {
            mountAlignmentActive = false
            mountAlignedStars = emptyList()
        } else {
            points = emptyList()
        }
    }
}
