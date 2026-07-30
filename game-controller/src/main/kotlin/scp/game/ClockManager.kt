package scp.game

import scp.protocol.Clocks
import scp.protocol.Color
import scp.protocol.TimeControl
import scp.protocol.TimeControlType

fun computeClockAfterMove(
    clocks: Clocks,
    timeControl: TimeControl,
    elapsedMs: Long,
    movingColor: Color,
): Clocks {
    val remaining = when (movingColor) {
        Color.WHITE -> clocks.whiteMs
        Color.BLACK -> clocks.blackMs
    }

    // A clock never goes below zero: past zero the flag has fallen, and how far past is not a
    // quantity the game has any use for.
    val afterElapsed = (remaining - elapsedMs).coerceAtLeast(0)

    // An exhausted clock is not topped up. The increment pays for a move made in time, so adding
    // it to a clock that already ran out handed the player their overrun back and then some - on
    // a 1s + 5s control, thinking for 3s used to *end* the move 3s better off than it started.
    val afterIncrement = if (afterElapsed == 0L) {
        0L
    } else when (timeControl.type) {
        TimeControlType.FISCHER -> afterElapsed + timeControl.incrementMs
        TimeControlType.BRONSTEIN -> {
            // Add back up to incrementMs of thinking time, but never exceed pre-move value
            val bonus = minOf(elapsedMs, timeControl.incrementMs)
            afterElapsed + bonus
        }
        TimeControlType.SIMPLE -> afterElapsed
        TimeControlType.NONE -> afterElapsed
    }

    return when (movingColor) {
        Color.WHITE -> Clocks(whiteMs = afterIncrement, blackMs = clocks.blackMs)
        Color.BLACK -> Clocks(whiteMs = clocks.whiteMs, blackMs = afterIncrement)
    }
}
