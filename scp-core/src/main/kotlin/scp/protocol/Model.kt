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

/**
 * Where a game is in its life, and nothing else.
 *
 * Why a finished game finished is [Termination]'s to say, and who won is [GameResult]'s.
 */
enum class GameStatus {
    NOT_STARTED,
    IN_PROGRESS,

    /** A draw has been offered and is waiting for an answer. */
    DRAW_OFFERED,

    FINISHED;

    /** Whether the game is over. */
    val isFinished: Boolean get() = this == FINISHED
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

data class Score(val type: ScoreType, val value: Int)

enum class ScoreType { CP, MATE }

data class GameStartedInfo(
    val gameId: String,
    val mode: GameMode,
    val playerColor: Color?,
    val timeControl: TimeControl?,
    val whiteName: String?,
    val blackName: String?,
    val initialFen: String,
)

/**
 * A move the game controller accepted, whoever played it.
 *
 * Sent for every move, so that together with [GameStartedInfo] it is all a UI needs to follow the
 * game: the side to move is in [fen], and whether that side is the local player follows from the
 * mode and colour the game started with.
 */
data class MovePlayedInfo(
    val gameId: String,
    val move: String,
    val san: String,
    val fen: String,
    /** Who played it. */
    val color: Color,
    /** Both clocks after the move, or null in an untimed game. */
    val clocks: Clocks?,
)
