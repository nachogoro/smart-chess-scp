package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface GameControllerModule {
    suspend fun create(params: CreateGameParams): Result<GameError, CreateGameResult>
    suspend fun getState(gameId: String): Result<GameError, GameState>
    suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult>
    suspend fun resign(gameId: String, color: Color): Result<GameError, Unit>
    suspend fun offerDraw(gameId: String, color: Color): Result<GameError, Unit>
    suspend fun respondDraw(gameId: String, accept: Boolean): Result<GameError, Unit>
    suspend fun claimDraw(gameId: String): Result<GameError, Unit>
    suspend fun abort(gameId: String): Result<GameError, Unit>
    suspend fun getHistory(gameId: String): Result<GameError, GameHistory>
    suspend fun setResult(params: SetResultParams): Result<GameError, Unit>

    val gameEnded: SharedFlow<GameEndedEvent>
    val flagFall: SharedFlow<FlagFallEvent>
}

data class CreateGameParams(
    val fen: String? = null,
    val timeControl: TimeControl? = null,
    val authority: Authority = Authority.LOCAL,
    val whiteName: String? = null,
    val blackName: String? = null,
)

data class CreateGameResult(val gameId: String)

data class GameState(
    val gameId: String,
    val fen: String,
    val moves: List<String>,
    val status: GameStatus,
    val turn: Color,
    val clocks: Clocks?,
    val result: GameResult,
)

data class SubmitMoveParams(
    val gameId: String,
    val move: String,
    val whiteMs: Long? = null,
    val blackMs: Long? = null,
)

data class SubmitMoveResult(
    val fen: String,
    val san: String,
    val status: GameStatus,
    val clocks: Clocks?,
    /**
     * Whether the move was an en passant capture.
     *
     * Not derivable from [san] and [fen] alone - `exd6` looks the same either way - and a UI
     * driving a physical board needs it, because the captured pawn stands on a square the move
     * never names.
     */
    val isEnPassant: Boolean = false,
)

data class SetResultParams(
    val gameId: String,
    val result: GameResult,
    val termination: Termination,
)

data class GameHistory(val moves: List<GameHistoryMove>)

data class GameHistoryMove(
    val move: String,
    val san: String,
    val fen: String,
    val whiteMs: Long?,
    val blackMs: Long?,
    val timestamp: String,
)

sealed class GameError(
    override val code: String,
    override val diagnostic: String? = null,
) : ModuleError {
    /** [move] is the UCI that was refused, where the caller supplied one. */
    class IllegalMove(val move: String? = null) : GameError("ILLEGAL_MOVE")

    /**
     * A position could not be read.
     *
     * Its own code rather than [IllegalMove]: setting a game up from a malformed FEN is not a move
     * at all, and reporting it as one left a UI unable to tell the two apart.
     */
    class InvalidFen(val fen: String? = null) : GameError("INVALID_FEN")

    class GameNotFound : GameError("GAME_NOT_FOUND")
    class GameOver : GameError("GAME_OVER")
    class NotYourTurn : GameError("NOT_YOUR_TURN")
    class InvalidClaim : GameError("INVALID_CLAIM")
    class NoDrawOffer : GameError("NO_DRAW_OFFER")
    class ExternalAuthority : GameError("EXTERNAL_AUTHORITY")
    class InvalidAuthority : GameError("INVALID_AUTHORITY")
}

data class GameEndedEvent(
    val gameId: String,
    val result: GameResult,
    val termination: Termination,
    val fen: String,
)

data class FlagFallEvent(
    val gameId: String,
    val color: Color,
)
