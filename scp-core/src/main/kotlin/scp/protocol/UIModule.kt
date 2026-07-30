package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface UIModule {
    // Game setup
    suspend fun selectGameMode(availableModes: List<GameMode>): Result<UIError, GameMode>
    suspend fun configureLocalGame(errorMessage: UIMessage? = null): Result<UIError, LocalGameConfig>
    suspend fun selectEngine(engines: List<EngineInfo>): Result<UIError, String>
    suspend fun configureEngineGame(params: ConfigureEngineGameParams): Result<UIError, EngineGameConfig>
    suspend fun selectRemotePlatform(platforms: List<PlatformInfo>): Result<UIError, String>
    suspend fun configureRemoteGame(params: ConfigureRemoteGameParams): Result<UIError, RemoteGameConfig>

    // In-game prompts
    suspend fun promptPromotion(move: String, color: Color): Result<UIError, PromotionPiece>
    suspend fun promptMoveDisambiguation(moves: List<String>, color: Color): Result<UIError, String>
    suspend fun promptDrawResponse(gameId: String, from: DrawOffer): Result<UIError, Boolean>

    // Notifications (one-way, no response expected)
    suspend fun onSeekingGame()
    suspend fun onGameStarted(info: GameStartedInfo)
    suspend fun onGameEnded(summary: GameSummary)
    suspend fun showOpponentMove(params: ShowOpponentMoveParams): Result<UIError, Unit>
    suspend fun displayError(message: UIMessage): Result<UIError, Unit>
    suspend fun boardDesync(expectedFen: String, message: UIMessage): Result<UIError, Unit>

    // Events
    val newGameRequested: SharedFlow<Unit>
    val moveReady: SharedFlow<MoveReadyEvent>
    val resignRequested: SharedFlow<ResignRequestedEvent>
    val drawRequested: SharedFlow<DrawRequestedEvent>
    val abortRequested: SharedFlow<AbortRequestedEvent>
}

/**
 * Who offered a draw.
 *
 * [name] is a name a player supplied and is shown as it stands - it is not ours to translate.
 * Where there is none the UI words the offer from [color], and where that is unknown too, as the
 * opponent. The Orchestrator used to fill those gaps itself with "White" and "opponent", which are
 * wordings only a UI can choose.
 */
data class DrawOffer(val name: String? = null, val color: Color? = null)

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
    /** Why the previous attempt was refused, when this form is being shown again. */
    val errorMessage: UIMessage? = null,
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
    /** Why the previous attempt was refused, when this form is being shown again. */
    val errorMessage: UIMessage? = null,
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

sealed class UIError(override val code: String) : ModuleError {
    class Cancelled : UIError("CANCELLED")
    class Timeout : UIError("TIMEOUT")
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
