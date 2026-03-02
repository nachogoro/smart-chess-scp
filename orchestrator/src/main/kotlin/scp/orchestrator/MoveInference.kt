package scp.orchestrator

import com.nachogoro.simplechess.Game
import com.nachogoro.simplechess.Move
import com.nachogoro.simplechess.Square
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardState
import scp.protocol.SquareState
import com.nachogoro.simplechess.Color as ChessColor
import com.nachogoro.simplechess.Piece as ChessPiece
import com.nachogoro.simplechess.PieceType as ChessPieceType
import scp.protocol.Color as ScpColor
import scp.protocol.PieceType as ScpPieceType

sealed class InferenceOutcome {
    data class SingleMove(val uci: String) : InferenceOutcome()
    data class NeedsPromotion(val fromTo: String) : InferenceOutcome()
    data class Ambiguous(val moves: List<String>) : InferenceOutcome()
    data object NoMatch : InferenceOutcome()
}

fun inferMove(fen: String, boardState: BoardState, boardLevel: BoardCapabilityLevel): InferenceOutcome {
    val game = Game.fromFen(fen).getOrNull() ?: return InferenceOutcome.NoMatch

    val matching = mutableListOf<Move>()
    for (move in game.availableMoves) {
        val afterGame = game.makeMove(move).getOrNull() ?: continue
        val resultBoard = afterGame.currentPosition.board
        if (boardMatches(resultBoard, boardState, boardLevel)) {
            matching.add(move)
        }
    }

    if (matching.isEmpty()) return InferenceOutcome.NoMatch

    // Check if all matches are promotion variants of the same from→to
    val distinctFromTo = matching.map { moveToFromTo(it) }.distinct()
    if (distinctFromTo.size == 1 && matching.all { it.isPromotion }) {
        // At FULL level, different promotion pieces produce different boards,
        // so if we still have multiple matches they must be at OCCUPANCY/COLOR level
        return if (matching.size == 1) {
            InferenceOutcome.SingleMove(moveToUci(matching.single()))
        } else {
            InferenceOutcome.NeedsPromotion(distinctFromTo.single())
        }
    }

    if (matching.size == 1) {
        return InferenceOutcome.SingleMove(moveToUci(matching.single()))
    }

    return InferenceOutcome.Ambiguous(matching.map { moveToUci(it) })
}

private fun boardMatches(
    resultBoard: Map<Square, ChessPiece>,
    physicalBoard: BoardState,
    level: BoardCapabilityLevel,
): Boolean {
    for (square in Square.ALL_SQUARES) {
        val squareName = square.toString()
        val chessPiece = resultBoard[square]
        val physicalState = physicalBoard.squares[squareName] ?: SquareState.Empty

        val matches = when (level) {
            BoardCapabilityLevel.FULL -> matchFull(chessPiece, physicalState)
            BoardCapabilityLevel.COLOR -> matchColor(chessPiece, physicalState)
            BoardCapabilityLevel.OCCUPANCY -> matchOccupancy(chessPiece, physicalState)
        }
        if (!matches) return false
    }
    return true
}

private fun matchFull(chessPiece: ChessPiece?, physicalState: SquareState): Boolean {
    return when (physicalState) {
        is SquareState.Empty -> chessPiece == null
        is SquareState.OccupiedByPiece -> {
            chessPiece != null
                && convertPieceType(chessPiece.type) == physicalState.piece.type
                && convertColor(chessPiece.color) == physicalState.piece.color
        }
        // Full-level board should always send OccupiedByPiece, but handle gracefully
        is SquareState.OccupiedByColor -> {
            chessPiece != null && convertColor(chessPiece.color) == physicalState.color
        }
        is SquareState.Occupied -> {
            (chessPiece != null) == physicalState.occupied
        }
    }
}

private fun matchColor(chessPiece: ChessPiece?, physicalState: SquareState): Boolean {
    return when (physicalState) {
        is SquareState.Empty -> chessPiece == null
        is SquareState.OccupiedByColor -> {
            chessPiece != null && convertColor(chessPiece.color) == physicalState.color
        }
        is SquareState.OccupiedByPiece -> {
            chessPiece != null
                && convertColor(chessPiece.color) == physicalState.piece.color
                && convertPieceType(chessPiece.type) == physicalState.piece.type
        }
        is SquareState.Occupied -> {
            (chessPiece != null) == physicalState.occupied
        }
    }
}

private fun matchOccupancy(chessPiece: ChessPiece?, physicalState: SquareState): Boolean {
    return when (physicalState) {
        is SquareState.Empty -> chessPiece == null
        is SquareState.Occupied -> (chessPiece != null) == physicalState.occupied
        is SquareState.OccupiedByColor -> chessPiece != null
        is SquareState.OccupiedByPiece -> chessPiece != null
    }
}

internal fun moveToUci(move: Move): String {
    val base = "${move.from}${move.to}"
    return if (move.isPromotion) {
        base + when (move.promotion) {
            ChessPieceType.QUEEN -> "q"
            ChessPieceType.ROOK -> "r"
            ChessPieceType.BISHOP -> "b"
            ChessPieceType.KNIGHT -> "n"
            else -> ""
        }
    } else base
}

private fun moveToFromTo(move: Move): String = "${move.from}${move.to}"

internal fun convertColor(chessColor: ChessColor): ScpColor = when (chessColor) {
    ChessColor.WHITE -> ScpColor.WHITE
    ChessColor.BLACK -> ScpColor.BLACK
}

internal fun convertPieceType(chessPieceType: ChessPieceType): ScpPieceType = when (chessPieceType) {
    ChessPieceType.PAWN -> ScpPieceType.PAWN
    ChessPieceType.KNIGHT -> ScpPieceType.KNIGHT
    ChessPieceType.BISHOP -> ScpPieceType.BISHOP
    ChessPieceType.ROOK -> ScpPieceType.ROOK
    ChessPieceType.QUEEN -> ScpPieceType.QUEEN
    ChessPieceType.KING -> ScpPieceType.KING
}

