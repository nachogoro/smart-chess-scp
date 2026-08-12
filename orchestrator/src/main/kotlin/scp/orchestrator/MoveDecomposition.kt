package scp.orchestrator

import com.nachogoro.simplechess.Game
import com.nachogoro.simplechess.Move
import com.nachogoro.simplechess.Position
import com.nachogoro.simplechess.Square
import scp.protocol.Piece
import scp.protocol.PieceTransfer
import com.nachogoro.simplechess.PieceType as ChessPieceType

/**
 * What the board is asked to do about a move, and what is left over for the player.
 */
data class MoveDecomposition(
    val transfers: List<PieceTransfer>,
    /**
     * A piece the move introduces that the board cannot: only ever a promotion on a board with
     * nothing to place. The pawn still comes off, so the board is a piece short rather than wrong
     * about one, and the player supplies it.
     */
    val leavesPieceToPlace: Boolean = false,
)

/**
 * Turns an accepted move into the ordered transfers that play it on a physical board.
 *
 * Derived from the move and the position it is played from rather than from a diff of the
 * positions either side of it, because the order is what makes the list safe to carry out and only
 * the move says which square is being vacated for which other one. A diff can see that a piece left
 * d5 and one arrived on e6; it cannot see that the pawn on e5 has to come off first, or that it is
 * the pawn on e5 at all. Reading intent out of a diff is [inferMove]'s job, and this is the side of
 * the exchange that already has the answer.
 *
 * [canPlacePieces] is the board's own declaration. A board that cannot fetch a piece is asked only
 * for the transfers it can make, and the rest is reported through [MoveDecomposition].
 *
 * Null where the position cannot be read or the move is not legal in it, which leaves the caller to
 * ask the player rather than to move pieces on a guess.
 */
fun decomposeMove(fenBefore: String, uci: String, canPlacePieces: Boolean): MoveDecomposition? {
    val game = Game.fromFen(fenBefore).getOrNull() ?: return null
    val move = game.availableMoves.firstOrNull { moveToUci(it) == uci } ?: return null
    val position = game.currentPosition

    val transfers = mutableListOf<PieceTransfer>()
    var leavesPieceToPlace = false

    // First, always: every later transfer lands a piece somewhere, and the one square that might
    // not be empty is the one the captured piece is standing on.
    capturedSquare(position, move)?.let { transfers.add(PieceTransfer.Remove(it.toString())) }

    if (move.isPromotion) {
        // The piece that arrives is not the piece that left, so the pawn comes off whether or not
        // anything can be put down in its place. Leaving it standing would put the board in a
        // position that looks legal, is not this game's, and would be inferred against next move.
        transfers.add(PieceTransfer.Remove(move.from.toString()))
        val promoted = move.promotion
        if (canPlacePieces && promoted != null) {
            val piece = Piece(convertPieceType(promoted), convertColor(move.piece.color))
            transfers.add(PieceTransfer.Place(piece, move.to.toString()))
        } else {
            leavesPieceToPlace = true
        }
    } else {
        transfers.add(PieceTransfer.Move(move.from.toString(), move.to.toString()))
    }

    castlingRookTransfer(move)?.let { transfers.add(it) }

    return MoveDecomposition(transfers, leavesPieceToPlace)
}

/**
 * The square the piece this move captures is standing on, or null if it captures nothing.
 */
private fun capturedSquare(position: Position, move: Move): Square? {
    // An ordinary capture takes the piece standing on the square being moved to.
    if (position.board[move.to] != null) return move.to

    // An en passant capture does not, and the square it does take from is named nowhere in the
    // move: the victim is beside the capturing pawn, on the file the pawn is crossing to. Tested
    // only once the destination is known to be empty, which is what an en passant destination is.
    if (move.piece.type == ChessPieceType.PAWN && move.to == position.enPassantTarget) {
        return Square.fromRankAndFile(move.from.rank, move.to.file)
    }

    return null
}

/**
 * The rook's transfer when this move is a castle, or null when it is not.
 *
 * It goes after the king's. Both orders satisfy the rule that a destination is empty when the
 * transfer runs — the rook's landing square is vacant either way — so the order is fixed here to
 * keep the decomposition deterministic, and it leaves the rook set down on the far side of a king
 * that has already arrived.
 */
private fun castlingRookTransfer(move: Move): PieceTransfer? {
    if (move.piece.type != ChessPieceType.KING) return null
    val rank = move.from.rank
    return when (move.to.file - move.from.file) {
        2 -> PieceTransfer.Move("h$rank", "f$rank")
        -2 -> PieceTransfer.Move("a$rank", "d$rank")
        else -> null
    }
}
