package scp.protocol

enum class Color { WHITE, BLACK }

enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

data class Piece(val type: PieceType, val color: Color)

enum class BoardCapabilityLevel { OCCUPANCY, COLOR, FULL }

sealed class SquareState {
    data object Empty : SquareState()
    data class Occupied(val occupied: Boolean) : SquareState()
    data class OccupiedByColor(val color: Color) : SquareState()
    data class OccupiedByPiece(val piece: Piece) : SquareState()
}

data class TimeControl(
    val type: TimeControlType,
    val initialMs: Long,
    val incrementMs: Long = 0,
)

enum class TimeControlType { FISCHER, BRONSTEIN, SIMPLE, NONE }

enum class GameStatus {
    NOT_STARTED, IN_PROGRESS, DRAW_OFFERED,
    CHECKMATE, STALEMATE, DRAW, RESIGNED, TIMEOUT, ABORTED,
}

enum class GameResult(val protocolString: String) {
    WHITE_WINS("1-0"),
    BLACK_WINS("0-1"),
    DRAW("1/2-1/2"),
    IN_PROGRESS("*");

    companion object {
        fun fromProtocolString(s: String): GameResult = entries.first { it.protocolString == s }
    }
}

enum class GameMode { LOCAL, ENGINE, REMOTE }

enum class Authority { LOCAL, EXTERNAL }

data class Clocks(val whiteMs: Long, val blackMs: Long)

enum class PromotionPiece(val uci: String) {
    QUEEN("q"), ROOK("r"), BISHOP("b"), KNIGHT("n");

    companion object {
        fun fromUci(s: String): PromotionPiece = entries.first { it.uci == s }
    }
}

enum class DrawClaimReason { THREEFOLD_REPETITION, FIFTY_MOVES, INSUFFICIENT_MATERIAL }

data class Score(val type: ScoreType, val value: Int)

enum class ScoreType { CP, MATE }
