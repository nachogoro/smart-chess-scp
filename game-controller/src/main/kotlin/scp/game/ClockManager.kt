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

    val afterElapsed = remaining - elapsedMs

    val afterIncrement = when (timeControl.type) {
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
