package scp.game

import com.nachogoro.simplechess.Game as ChessGame
import kotlinx.coroutines.Job
import scp.protocol.*

class InternalGame(
    val id: String,
    var chessGame: ChessGame,
    val authority: Authority,
    val timeControl: TimeControl?,
) {
    var status: GameStatus = GameStatus.NOT_STARTED
    var result: GameResult = GameResult.IN_PROGRESS
    // A `none` time control is the absence of one, so there are no clocks to track. Building a
    // pair of them anyway would leave every untimed game reporting a frozen 00:00.
    var clocks: Clocks? = timeControl
        ?.takeIf { it.type != TimeControlType.NONE }
        ?.let { Clocks(it.initialMs, it.initialMs) }
    var drawOffer: Color? = null
    val moveHistory: MutableList<GameHistoryMove> = mutableListOf()
    var turnStartedAt: Long = 0L
    var timerJob: Job? = null

    val isOver: Boolean
        get() = status.isFinished

    val turn: Color
        get() = chessColorToScp(chessGame.activeColor)

    val fen: String
        get() = chessGame.currentPosition.fen

    val uciMoves: List<String>
        get() = moveHistory.map { it.move }
}
