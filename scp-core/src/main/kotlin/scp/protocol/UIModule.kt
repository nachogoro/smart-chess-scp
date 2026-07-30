package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface UIModule {
    // Game setup
    suspend fun selectGameMode(availableModes: List<GameMode>): Result<UIError, GameMode>
    suspend fun configureLocalGame(errorMessage: String? = null): Result<UIError, LocalGameConfig>
    suspend fun selectEngine(engines: List<EngineInfo>): Result<UIError, String>
    suspend fun configureEngineGame(params: ConfigureEngineGameParams): Result<UIError, EngineGameConfig>
    suspend fun selectRemotePlatform(platforms: List<PlatformInfo>): Result<UIError, String>
    suspend fun configureRemoteGame(params: ConfigureRemoteGameParams): Result<UIError, RemoteGameConfig>

    // In-game prompts
    suspend fun promptPromotion(move: String, color: Color): Result<UIError, PromotionPiece>
    suspend fun promptMoveDisambiguation(moves: List<String>, color: Color): Result<UIError, String>
    suspend fun promptDrawResponse(gameId: String, from: String): Result<UIError, Boolean>

    // Notifications (one-way, no response expected)
    suspend fun onSeekingGame()
    suspend fun onGameStarted(info: GameStartedInfo)
    suspend fun onGameEnded(summary: GameSummary)
    suspend fun showOpponentMove(params: ShowOpponentMoveParams): Result<UIError, Unit>
    suspend fun displayError(message: String): Result<UIError, Unit>
    suspend fun boardDesync(expectedFen: String, message: String): Result<UIError, Unit>

    // Events
    val newGameRequested: SharedFlow<Unit>
    val moveReady: SharedFlow<MoveReadyEvent>
    val resignRequested: SharedFlow<ResignRequestedEvent>
    val drawRequested: SharedFlow<DrawRequestedEvent>
    val abortRequested: SharedFlow<AbortRequestedEvent>
}

data class EngineInfo(val name: String, val author: String)

data class PlatformInfo(val platform: String, val features: List<String>)

data class LocalGameConfig(
    val whiteName: String?,
    val blackName: String?,
    val timeControl: TimeControl?,
)

data class ConfigureEngineGameParams(
    val engineName: String,
    val parameters: List<ParameterDescriptor>,
    val errorMessage: String? = null,
)

data class EngineGameConfig(
    val playerColor: Color,
    val engineSettings: Map<String, Any>,
    val timeControl: TimeControl?,
)

data class ConfigureRemoteGameParams(
    val platform: String,
    val parameters: List<ParameterDescriptor>,
    val seekParameters: List<ParameterDescriptor>,
    val errorMessage: String? = null,
)

data class RemoteGameConfig(
    val platformSettings: Map<String, Any>,
    val seekSettings: Map<String, Any>,
)

data class ShowOpponentMoveParams(
    val move: String,
    val san: String,
    val fen: String,
    val clocks: Clocks?,
    /** See [SubmitMoveResult.isEnPassant]: the player must clear a square the move does not name. */
    val isEnPassant: Boolean = false,
)

sealed class UIError(override val code: String, override val message: String) : ModuleError {
    class Cancelled(message: String = "User cancelled") : UIError("CANCELLED", message)
    class Timeout(message: String = "UI timeout") : UIError("TIMEOUT", message)
}

data class MoveReadyEvent(val gameId: String)

data class ResignRequestedEvent(val gameId: String)

data class DrawRequestedEvent(val gameId: String)

data class AbortRequestedEvent(val gameId: String)

data class GameSummary(
    val gameId: String,
    val mode: GameMode,
    val whiteName: String?,
    val blackName: String?,
    val playerColor: Color?,
    val timeControl: TimeControl?,
    val result: GameResult,
    val termination: Termination,
    val finalFen: String,
    val moves: List<GameHistoryMove>,
)
