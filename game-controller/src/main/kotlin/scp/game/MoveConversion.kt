package scp.game

import com.nachogoro.simplechess.Game
import com.nachogoro.simplechess.Move
import com.nachogoro.simplechess.PieceType as ChessPieceType
import com.nachogoro.simplechess.Square
import com.nachogoro.simplechess.Color as ChessColor
import scp.protocol.Color as ScpColor

fun parseUciMove(uci: String, game: Game): Move? {
    if (uci.length !in 4..5) return null

    val fromSquare = try {
        Square.fromString(uci.take(2))
    } catch (_: IllegalArgumentException) {
        return null
    }

    val toSquare = try {
        Square.fromString(uci.substring(2, 4))
    } catch (_: IllegalArgumentException) {
        return null
    }

    val promotionType = if (uci.length == 5) {
        when (uci.last().lowercaseChar()) {
            'q' -> ChessPieceType.QUEEN
            'r' -> ChessPieceType.ROOK
            'b' -> ChessPieceType.BISHOP
            'n' -> ChessPieceType.KNIGHT
            else -> return null
        }
    } else null

    return game.availableMoves.find { move ->
        move.from == fromSquare
            && move.to == toSquare
            && move.promotion == promotionType
    }
}

/**
 * Whether [uci] moves a piece belonging to the side that is not to move.
 *
 * False for a move whose origin square is empty or unreadable: that is not a turn-order problem,
 * and [parseUciMove] refuses it as illegal along with everything else it cannot match.
 */
fun movesWrongSide(game: Game, uci: String): Boolean {
    if (uci.length < 2) return false
    val from = try {
        Square.fromString(uci.take(2))
    } catch (_: IllegalArgumentException) {
        return false
    }
    val piece = game.currentPosition.board[from] ?: return false
    return piece.color != game.activeColor
}

fun chessColorToScp(color: ChessColor): ScpColor = when (color) {
    ChessColor.WHITE -> ScpColor.WHITE
    ChessColor.BLACK -> ScpColor.BLACK
}