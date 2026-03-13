package scp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

class ComposeUIModule : UIModule {

    // -- Observable state for Compose UI --

    var pendingGameModeSelection by mutableStateOf<PendingRequest<List<GameMode>, GameMode>?>(null)
        private set
    var pendingLocalGameConfig by mutableStateOf<PendingRequest<Unit, LocalGameConfig>?>(null)
        private set
    var pendingEngineSelection by mutableStateOf<PendingRequest<List<EngineInfo>, String>?>(null)
        private set
    var pendingEngineGameConfig by mutableStateOf<PendingRequest<ConfigureEngineGameParams, EngineGameConfig>?>(null)
        private set
    var pendingRemotePlatformSelection by mutableStateOf<PendingRequest<List<PlatformInfo>, String>?>(null)
        private set
    var pendingRemoteGameConfig by mutableStateOf<PendingRequest<ConfigureRemoteGameParams, RemoteGameConfig>?>(null)
        private set
    var pendingPromotion by mutableStateOf<PendingRequest<PromotionContext, PromotionPiece>?>(null)
        private set
    var pendingDisambiguation by mutableStateOf<PendingRequest<DisambiguationContext, String>?>(null)
        private set
    var pendingDrawResponse by mutableStateOf<PendingRequest<String, Boolean>?>(null)
        private set

    var lastOpponentMove by mutableStateOf<ShowOpponentMoveParams?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var desyncInfo by mutableStateOf<DesyncInfo?>(null)
        private set
    var lastGameStartedInfo by mutableStateOf<GameStartedInfo?>(null)
        private set
    var lastGameSummary by mutableStateOf<GameSummary?>(null)
        private set

    data class PromotionContext(val move: String, val color: Color)
    data class DisambiguationContext(val moves: List<String>, val color: Color)
    data class DesyncInfo(val expectedFen: String, val message: String)

    // -- UIModule implementation --

    private val _newGameRequested = MutableSharedFlow<Unit>(replay = 1)
    private val _moveReady = MutableSharedFlow<MoveReadyEvent>()
    private val _resignRequested = MutableSharedFlow<ResignRequestedEvent>()
    private val _drawRequested = MutableSharedFlow<DrawRequestedEvent>()
    private val _abortRequested = MutableSharedFlow<AbortRequestedEvent>()

    override val newGameRequested: SharedFlow<Unit> = _newGameRequested
    override val moveReady: SharedFlow<MoveReadyEvent> = _moveReady
    override val resignRequested: SharedFlow<ResignRequestedEvent> = _resignRequested
    override val drawRequested: SharedFlow<DrawRequestedEvent> = _drawRequested
    override val abortRequested: SharedFlow<AbortRequestedEvent> = _abortRequested

    override suspend fun selectGameMode(availableModes: List<GameMode>): Result<UIError, GameMode> {
        val deferred = CompletableDeferred<GameMode>()
        pendingGameModeSelection = PendingRequest(availableModes, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingGameModeSelection = null
        }
    }

    override suspend fun configureLocalGame(): Result<UIError, LocalGameConfig> {
        val deferred = CompletableDeferred<LocalGameConfig>()
        pendingLocalGameConfig = PendingRequest(Unit, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingLocalGameConfig = null
        }
    }

    override suspend fun selectEngine(engines: List<EngineInfo>): Result<UIError, String> {
        val deferred = CompletableDeferred<String>()
        pendingEngineSelection = PendingRequest(engines, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingEngineSelection = null
        }
    }

    override suspend fun configureEngineGame(params: ConfigureEngineGameParams): Result<UIError, EngineGameConfig> {
        val deferred = CompletableDeferred<EngineGameConfig>()
        pendingEngineGameConfig = PendingRequest(params, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingEngineGameConfig = null
        }
    }

    override suspend fun selectRemotePlatform(platforms: List<PlatformInfo>): Result<UIError, String> {
        val deferred = CompletableDeferred<String>()
        pendingRemotePlatformSelection = PendingRequest(platforms, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingRemotePlatformSelection = null
        }
    }

    override suspend fun configureRemoteGame(params: ConfigureRemoteGameParams): Result<UIError, RemoteGameConfig> {
        val deferred = CompletableDeferred<RemoteGameConfig>()
        pendingRemoteGameConfig = PendingRequest(params, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingRemoteGameConfig = null
        }
    }

    override suspend fun promptPromotion(move: String, color: Color): Result<UIError, PromotionPiece> {
        val deferred = CompletableDeferred<PromotionPiece>()
        pendingPromotion = PendingRequest(PromotionContext(move, color), deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingPromotion = null
        }
    }

    override suspend fun promptMoveDisambiguation(moves: List<String>, color: Color): Result<UIError, String> {
        val deferred = CompletableDeferred<String>()
        pendingDisambiguation = PendingRequest(DisambiguationContext(moves, color), deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingDisambiguation = null
        }
    }

    override suspend fun promptDrawResponse(gameId: String, from: String): Result<UIError, Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingDrawResponse = PendingRequest(from, deferred)
        return try {
            Result.Success(deferred.await())
        } catch (_: Exception) {
            Result.Failure(UIError.Cancelled())
        } finally {
            pendingDrawResponse = null
        }
    }

    override suspend fun onGameStarted(info: GameStartedInfo) {
        lastGameStartedInfo = info
    }

    override suspend fun onGameEnded(summary: GameSummary) {
        lastGameSummary = summary
    }

    override suspend fun showOpponentMove(params: ShowOpponentMoveParams): Result<UIError, Unit> {
        lastOpponentMove = params
        return Result.Success(Unit)
    }

    override suspend fun displayError(message: String): Result<UIError, Unit> {
        lastError = message
        return Result.Success(Unit)
    }

    override suspend fun boardDesync(expectedFen: String, message: String): Result<UIError, Unit> {
        desyncInfo = DesyncInfo(expectedFen, message)
        return Result.Success(Unit)
    }

    // -- Methods called by Compose UI to emit events --

    suspend fun requestNewGame() = _newGameRequested.emit(Unit)
    suspend fun signalMoveReady(gameId: String) = _moveReady.emit(MoveReadyEvent(gameId))
    suspend fun requestResign(gameId: String) = _resignRequested.emit(ResignRequestedEvent(gameId))
    suspend fun requestDraw(gameId: String) = _drawRequested.emit(DrawRequestedEvent(gameId))
    suspend fun requestAbort(gameId: String) = _abortRequested.emit(AbortRequestedEvent(gameId))

    fun clearError() { lastError = null }
    fun clearDesync() { desyncInfo = null }
    fun clearOpponentMove() { lastOpponentMove = null }
    fun clearGameStartedInfo() { lastGameStartedInfo = null }
    fun clearGameSummary() { lastGameSummary = null }
}

data class PendingRequest<I, O>(
    val input: I,
    val deferred: CompletableDeferred<O>,
) {
    fun complete(value: O) = deferred.complete(value)
    fun cancel() = deferred.cancel()
}
