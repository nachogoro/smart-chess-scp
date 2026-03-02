package scp.orchestrator

import com.nachogoro.simplechess.Game
import com.nachogoro.simplechess.Square
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardState
import scp.protocol.Piece
import scp.protocol.SquareState

fun boardStateFromFen(fen: String, level: BoardCapabilityLevel): BoardState {
    val game = Game.fromFen(fen).getOrThrow()
    val board = game.currentPosition.board
    val squares = mutableMapOf<String, SquareState>()
    for (square in Square.ALL_SQUARES) {
        val piece = board[square]
        squares[square.toString()] = when {
            piece == null -> SquareState.Empty
            else -> when (level) {
                BoardCapabilityLevel.FULL -> SquareState.OccupiedByPiece(
                    Piece(convertPieceType(piece.type), convertColor(piece.color))
                )
                BoardCapabilityLevel.COLOR -> SquareState.OccupiedByColor(convertColor(piece.color))
                BoardCapabilityLevel.OCCUPANCY -> SquareState.Occupied(true)
            }
        }
    }
    return BoardState(squares)
}
