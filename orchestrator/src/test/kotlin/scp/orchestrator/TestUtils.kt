package scp.orchestrator

import com.nachogoro.simplechess.Game
import com.nachogoro.simplechess.Square
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardState
import scp.protocol.Piece
import scp.protocol.PieceTransfer
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

/**
 * Applies [transfers] to [state] the way a board that can move pieces would.
 *
 * Written at the fidelity [level] can hold, so an occupancy-level board that actuates ends up in
 * the state an occupancy-level read of the same position would produce.
 */
fun applyTransfers(
    state: BoardState,
    transfers: List<PieceTransfer>,
    level: BoardCapabilityLevel,
): BoardState {
    val squares = state.squares.toMutableMap()
    for (transfer in transfers) {
        when (transfer) {
            is PieceTransfer.Move -> {
                squares[transfer.to] = squares[transfer.from] ?: SquareState.Empty
                squares[transfer.from] = SquareState.Empty
            }
            is PieceTransfer.Remove -> squares[transfer.from] = SquareState.Empty
            is PieceTransfer.Place -> squares[transfer.to] = occupiedBy(transfer.piece, level)
        }
    }
    return BoardState(squares)
}

private fun occupiedBy(piece: Piece, level: BoardCapabilityLevel): SquareState = when (level) {
    BoardCapabilityLevel.FULL -> SquareState.OccupiedByPiece(piece)
    BoardCapabilityLevel.COLOR -> SquareState.OccupiedByColor(piece.color)
    BoardCapabilityLevel.OCCUPANCY -> SquareState.Occupied(true)
}
