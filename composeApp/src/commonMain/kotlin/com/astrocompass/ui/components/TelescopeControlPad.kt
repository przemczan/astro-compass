package com.astrocompass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.astrocompass.telescope.MoveRatePreset
import com.astrocompass.telescope.TelescopeDirection

private val ARROW_SIZE = 64.dp

/**
 * A hand controller for a connected mount: hold an arrow to move, release to stop, with the move
 * rate picked from the middle of the pad.
 *
 * Hold-to-move means the *release* is what stops the mount, so anything that swallows a release
 * leaves it slewing. [onStopAllMotion] is the backstop for the one case the gesture itself cannot
 * cover -- the pad being disposed mid-press, where the press handler is cancelled before its own
 * stop can run -- so it must be a fire-and-forget that does not depend on the caller's composition
 * scope still being alive. Every ordinary release still sends the precise per-axis stop, which is
 * why that path is not just this blanket one.
 */
@Composable
fun TelescopeControlPad(
    moveRate: MoveRatePreset,
    onMoveRateChange: (MoveRatePreset) -> Unit,
    onPressDirection: (TelescopeDirection) -> Unit,
    onReleaseDirection: (TelescopeDirection) -> Unit,
    onStopAllMotion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stopAllMotion by rememberUpdatedState(onStopAllMotion)
    DisposableEffect(Unit) { onDispose { stopAllMotion() } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DirectionButton(TelescopeDirection.NORTH, Icons.Default.KeyboardArrowUp, "Move north", onPressDirection, onReleaseDirection)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirectionButton(TelescopeDirection.WEST, Icons.Default.KeyboardArrowLeft, "Move west", onPressDirection, onReleaseDirection)
            MoveRateSelector(moveRate, onMoveRateChange, Modifier.padding(horizontal = 8.dp))
            DirectionButton(TelescopeDirection.EAST, Icons.Default.KeyboardArrowRight, "Move east", onPressDirection, onReleaseDirection)
        }
        DirectionButton(TelescopeDirection.SOUTH, Icons.Default.KeyboardArrowDown, "Move south", onPressDirection, onReleaseDirection)
    }
}

/** Press and release rather than click: a click fires only once the gesture has already finished,
 *  which for hold-to-move would mean the mount never moves at all. `tryAwaitRelease` also reports
 *  a cancelled gesture (a finger dragged off the button) as a release, which is exactly the
 *  behavior wanted -- sliding off an arrow stops that axis.
 *
 *  Drawn as a bordered [Box] rather than an `OutlinedIconButton` for the same reason: a Material
 *  button installs its own click/indication pointer input on the node, which competes with this
 *  one for the down event. One gesture detector per node, and it is this one. */
@Composable
private fun DirectionButton(
    direction: TelescopeDirection,
    icon: ImageVector,
    contentDescription: String,
    onPress: (TelescopeDirection) -> Unit,
    onRelease: (TelescopeDirection) -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(ARROW_SIZE)
            .clip(CircleShape)
            .background(if (held) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(direction) {
                detectTapGestures(
                    onPress = {
                        held = true
                        press(direction)
                        tryAwaitRelease()
                        release(direction)
                        held = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (held) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
        )
    }
}

/** Sits in the middle of the pad, where the unusable center cell of a direction cross already is
 *  -- the rate belongs to the arrows around it, and putting it anywhere else would cost the
 *  overlay another row of height over the map. */
@Composable
private fun MoveRateSelector(
    moveRate: MoveRatePreset,
    onMoveRateChange: (MoveRatePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(moveRate.label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in MoveRatePreset.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onMoveRateChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
