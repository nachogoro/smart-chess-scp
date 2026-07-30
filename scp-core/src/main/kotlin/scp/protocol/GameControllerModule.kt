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
    val result: String,
    val reason: String,
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

sealed class GameError(val code: String, val message: String) {
    class IllegalMove(message: String = "Illegal move") : GameError("ILLEGAL_MOVE", message)
    class GameNotFound(message: String = "Game not found") : GameError("GAME_NOT_FOUND", message)
    class GameOver(message: String = "Game is over") : GameError("GAME_OVER", message)
    class NotYourTurn(message: String = "Not your turn") : GameError("NOT_YOUR_TURN", message)
    class InvalidClaim(message: String = "Invalid draw claim") : GameError("INVALID_CLAIM", message)
    class NoDrawOffer(message: String = "No draw offer to respond to") : GameError("NO_DRAW_OFFER", message)
    class ExternalAuthority(message: String = "Draw claims not allowed in external authority mode") : GameError("EXTERNAL_AUTHORITY", message)
    class InvalidAuthority(message: String = "Game was not created with external authority") : GameError("INVALID_AUTHORITY", message)
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
