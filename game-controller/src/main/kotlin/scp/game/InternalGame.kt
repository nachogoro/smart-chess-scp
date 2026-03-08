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
    var clocks: Clocks? = timeControl?.let { Clocks(it.initialMs, it.initialMs) }
    var drawOffer: Color? = null
    val moveHistory: MutableList<GameHistoryMove> = mutableListOf()
    var turnStartedAt: Long = 0L
    var timerJob: Job? = null

    val isOver: Boolean
        get() = status in TERMINAL_STATUSES

    val turn: Color
        get() = chessColorToScp(chessGame.activeColor)

    val fen: String
        get() = chessGame.currentPosition.fen

    val uciMoves: List<String>
        get() = moveHistory.map { it.move }

    companion object {
        private val TERMINAL_STATUSES = setOf(
            GameStatus.CHECKMATE,
            GameStatus.STALEMATE,
            GameStatus.DRAW,
            GameStatus.RESIGNED,
            GameStatus.TIMEOUT,
            GameStatus.ABORTED,
        )
    }
}
