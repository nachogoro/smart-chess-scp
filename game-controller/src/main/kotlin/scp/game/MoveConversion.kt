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

fun chessColorToScp(color: ChessColor): ScpColor = when (color) {
    ChessColor.WHITE -> ScpColor.WHITE
    ChessColor.BLACK -> ScpColor.BLACK
}