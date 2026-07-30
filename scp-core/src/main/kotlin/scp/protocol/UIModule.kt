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

/**
 * An engine offered for selection.
 *
 * [id] is what [UIModule.selectEngine] answers with. A name cannot do that job: two adapters may
 * report the same one, and neither the UI nor the orchestrator would be able to say which was
 * meant.
 */
data class EngineInfo(val id: String, val name: String, val author: String)

/** A platform offered for selection. [id] identifies it; see [EngineInfo.id]. */
data class PlatformInfo(val id: String, val platform: String, val features: List<String>)

data class LocalGameConfig(
    val whiteName: String?,
    val blackName: String?,
    val timeControl: TimeControl?,
)

data class ConfigureEngineGameParams(
    val engineName: String,
    val engineAuthor: String,
    val parameters: List<ParameterDescriptor>,
    /** Why the previous attempt was refused, when this form is being shown again. */
    val errorMessage: UIMessage? = null,
)

data class EngineGameConfig(
    val playerColor: Color,
    /** What to call the human player. Null where the UI did not ask for one. */
    val playerName: String?,
    val engineSettings: Map<String, Any>,
    val timeControl: TimeControl?,
)

data class ConfigureRemoteGameParams(
    val platform: String,
    /** What the platform says it supports, e.g. `rated`, `casual`. */
    val features: List<String>,
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
    /** The colour the opponent was playing, so a UI can say whose move it is showing. */
    val color: Color,
    val clocks: Clocks?,
    /** See [SubmitMoveResult.isEnPassant]: the player must clear a square the move does not name. */
    val isEnPassant: Boolean = false,
)

sealed class UIError(override val code: String) : ModuleError {
    class Cancelled : UIError("CANCELLED")
    class Timeout : UIError("TIMEOUT")
}

/**
 * The user has finished their physical move.
 *
 * [color] is the player who signalled. The orchestrator works out what was actually played by
 * reading the board rather than from this, because the board is the authority on what moved - but
 * a UI knows which player pressed, and the protocol has it report that.
 */
data class MoveReadyEvent(val gameId: String, val color: Color)

/**
 * A player wants to resign.
 *
 * [color] is whose resignation it is, and it is the UI's to decide because only the UI knows how
 * the request was made. A local game is two people at one board: whether the Resign button belongs
 * to the player to move, or there is one for each of them, is a question about the screen, and
 * inferring the answer here got it wrong for whichever of them was not to move.
 */
data class ResignRequestedEvent(val gameId: String, val color: Color)

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
