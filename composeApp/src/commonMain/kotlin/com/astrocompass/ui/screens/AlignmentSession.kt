package com.astrocompass.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.astrocompass.alignment.AlignmentPoint
import com.astrocompass.catalog.StarObject
import com.astrocompass.guiding.GuidingMode

/** How many stars a fresh run uses -- the smallest count either mode accepts, so the quickest
 *  path through the screen is also the default one. */
private const val DEFAULT_STAR_COUNT = 2

/**
 * The alignment wizard's steps. [CHOOSE_TYPE] forks into one of two chains, which never meet again:
 * [STAR_SYNC] alone for a sensors-only setup, or the camera-calibration chain
 * [CHOOSE_MIRROR]-[MOUNT_PHONE]-[POINT_TELESCOPE]-[CENTER_CROSSHAIR]-[DONE] for a plate-solving one.
 *
 * [previous] is the whole of the wizard's back navigation -- the toolbar's Back button and the
 * system back gesture both walk it, so the two can't disagree.
 */
enum class AlignmentStep {
    CHOOSE_TYPE,
    STAR_SYNC,
    CHOOSE_MIRROR,
    MOUNT_PHONE,
    POINT_TELESCOPE,
    CENTER_CROSSHAIR,
    DONE,
    ;

    /** The step Back leads to, or null at the wizard's start, where Back leaves the screen. */
    val previous: AlignmentStep?
        get() = when (this) {
            CHOOSE_TYPE -> null
            STAR_SYNC, CHOOSE_MIRROR -> CHOOSE_TYPE
            MOUNT_PHONE -> CHOOSE_MIRROR
            POINT_TELESCOPE -> MOUNT_PHONE
            CENTER_CROSSHAIR -> POINT_TELESCOPE
            DONE -> CENTER_CROSSHAIR
        }
}

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
 * and [switchTo] discards neither, for the same reason: a future caller that flips [mode] mid-run
 * (a dropped Bluetooth link, say) must not silently wipe the memory of an armed mount along with it.
 *
 * Nothing calls [switchTo] today -- see [mode]'s own doc.
 */
class AlignmentSession {
    // Nothing calls switchTo(TELESCOPE) today: the mount's own native alignment sequence below
    // (markMountAlignmentArmed/addMountAlignedStar and StarAlignmentStep's alignsMount branch) is
    // fully built but currently unreachable -- a dedicated telescope alignment wizard is planned
    // separately to pick it, rather than reviving this path through the phone wizard.
    var mode by mutableStateOf(GuidingMode.PHONE)
        private set
    private val alignsMount get() = mode == GuidingMode.TELESCOPE

    /** Which wizard step is showing. Held here, not in the screen, for the same reason as the run
     *  itself -- a trip through Settings would otherwise drop the user back at [CHOOSE_TYPE] with a
     *  half-finished run behind it. */
    var step by mutableStateOf(AlignmentStep.CHOOSE_TYPE)
        private set

    fun goTo(newStep: AlignmentStep) {
        step = newStep
    }

    /** One step back, or false at the start of the wizard -- where the caller leaves the screen. */
    fun stepBack(): Boolean {
        step = step.previous ?: return false
        return true
    }

    /** The camera branch's draft, kept alongside the star branch's [points] rather than
     *  `remember`ed in the screen, so both survive a teardown identically. Nothing here is
     *  persisted until the wizard's last step confirms. */
    var calibrationCameraId by mutableStateOf<String?>(null)
        private set
    var calibrationPhysicalCameraId by mutableStateOf<String?>(null)
        private set
    var calibrationPan by mutableStateOf(Offset.Zero)
        private set

    fun selectCalibrationCamera(cameraId: String?, physicalCameraId: String?) {
        calibrationCameraId = cameraId
        calibrationPhysicalCameraId = physicalCameraId
    }

    fun updateCalibrationPan(pan: Offset) {
        calibrationPan = pan
    }

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

    /** Ends the *current* mode's run and returns the wizard to its first step, leaving the other
     *  mode's progress alone. */
    fun clear() {
        if (alignsMount) {
            mountAlignmentActive = false
            mountAlignedStars = emptyList()
        } else {
            points = emptyList()
        }
        step = AlignmentStep.CHOOSE_TYPE
    }
}
