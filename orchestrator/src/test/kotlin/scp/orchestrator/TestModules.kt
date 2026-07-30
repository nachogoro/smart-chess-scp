package scp.orchestrator

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

// ---------------------------------------------------------------------------
// Fake Board
// ---------------------------------------------------------------------------

class FakeBoardModule(
    private val level: BoardCapabilityLevel = BoardCapabilityLevel.FULL,
) : BoardModule {
    private val _errors = MutableSharedFlow<BoardErrorEvent>()
    override val errors: SharedFlow<BoardErrorEvent> = _errors

    var boardState: BoardState = BoardState(emptyMap())
    var failWith: BoardError? = null

    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        failWith?.let { return Result.Failure(it) }
        return Result.Success(BoardCapabilities(level))
    }

    override suspend fun getState(): Result<BoardError, BoardState> {
        failWith?.let { return Result.Failure(it) }
        return Result.Success(boardState)
    }

    suspend fun emitError(event: BoardErrorEvent) = _errors.emit(event)
}

// ---------------------------------------------------------------------------
// Fake Game Controller
// ---------------------------------------------------------------------------

open class FakeGameControllerModule : GameControllerModule {
    private val _gameEnded = MutableSharedFlow<GameEndedEvent>()
    private val _flagFall = MutableSharedFlow<FlagFallEvent>()
    override val gameEnded: SharedFlow<GameEndedEvent> = _gameEnded
    override val flagFall: SharedFlow<FlagFallEvent> = _flagFall

    var nextGameId = "game-1"
    private val games = mutableMapOf<String, GameState>()
    var createFail: GameError? = null
    var submitMoveFail: GameError? = null
    var lastSubmittedMove: SubmitMoveParams? = null
    var lastResign: Pair<String, Color>? = null
    var lastDrawOffer: Pair<String, Color>? = null
    var lastDrawResponse: Pair<String, Boolean>? = null
    var lastDrawClaim: String? = null
    var lastAbort: String? = null
    var lastSetResult: SetResultParams? = null

    // Called by tests to seed a game state
    fun setGameState(state: GameState) {
        games[state.gameId] = state
    }

    override suspend fun create(params: CreateGameParams): Result<GameError, CreateGameResult> {
        createFail?.let { return Result.Failure(it) }
        val gameId = nextGameId
        val fen = params.fen ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        games[gameId] = GameState(
            gameId = gameId,
            fen = fen,
            moves = emptyList(),
            status = GameStatus.IN_PROGRESS,
            turn = Color.WHITE,
            clocks = params.timeControl?.let { Clocks(it.initialMs, it.initialMs) },
            result = GameResult.IN_PROGRESS,
        )
        return Result.Success(CreateGameResult(gameId))
    }

    override open suspend fun getState(gameId: String): Result<GameError, GameState> {
        val state = games[gameId] ?: return Result.Failure(GameError.GameNotFound())
        return Result.Success(state)
    }

    override suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult> {
        submitMoveFail?.let { return Result.Failure(it) }
        lastSubmittedMove = params
        val state = games[params.gameId] ?: return Result.Failure(GameError.GameNotFound())
        val newTurn = if (state.turn == Color.WHITE) Color.BLACK else Color.WHITE
        // Use chess library to get the resulting FEN
        val game = com.nachogoro.simplechess.Game.fromFen(state.fen).getOrThrow()
        val move = game.availableMoves.first { moveToUci(it) == params.move }
        val afterGame = game.makeMove(move).getOrThrow()
        val newFen = afterGame.currentPosition.fen
        val san = afterGame.history.last().second.algebraicNotation
        val newState = state.copy(fen = newFen, turn = newTurn, moves = state.moves + params.move)
        games[params.gameId] = newState
        return Result.Success(SubmitMoveResult(fen = newFen, san = san, status = GameStatus.IN_PROGRESS, clocks = state.clocks))
    }

    override suspend fun resign(gameId: String, color: Color): Result<GameError, Unit> {
        lastResign = gameId to color
        return Result.Success(Unit)
    }

    override suspend fun offerDraw(gameId: String, color: Color): Result<GameError, Unit> {
        lastDrawOffer = gameId to color
        return Result.Success(Unit)
    }

    override suspend fun respondDraw(gameId: String, accept: Boolean): Result<GameError, Unit> {
        lastDrawResponse = gameId to accept
        return Result.Success(Unit)
    }

    override open suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
        lastDrawClaim = gameId
        return Result.Success(Unit)
    }

    override suspend fun abort(gameId: String): Result<GameError, Unit> {
        lastAbort = gameId
        return Result.Success(Unit)
    }

    override suspend fun getHistory(gameId: String): Result<GameError, GameHistory> {
        return Result.Success(GameHistory(emptyList()))
    }

    override suspend fun setResult(params: SetResultParams): Result<GameError, Unit> {
        lastSetResult = params
        return Result.Success(Unit)
    }

    suspend fun emitGameEnded(event: GameEndedEvent) = _gameEnded.emit(event)
    suspend fun emitFlagFall(event: FlagFallEvent) = _flagFall.emit(event)
}

// ---------------------------------------------------------------------------
// Fake UI
// ---------------------------------------------------------------------------

class FakeUIModule : UIModule {
    private val _newGameRequested = MutableSharedFlow<Unit>()
    private val _moveReady = MutableSharedFlow<MoveReadyEvent>()
    private val _resignRequested = MutableSharedFlow<ResignRequestedEvent>()
    private val _drawRequested = MutableSharedFlow<DrawRequestedEvent>()
    private val _abortRequested = MutableSharedFlow<AbortRequestedEvent>()

    override val newGameRequested: SharedFlow<Unit> = _newGameRequested
    override val moveReady: SharedFlow<MoveReadyEvent> = _moveReady
    override val resignRequested: SharedFlow<ResignRequestedEvent> = _resignRequested
    override val drawRequested: SharedFlow<DrawRequestedEvent> = _drawRequested
    override val abortRequested: SharedFlow<AbortRequestedEvent> = _abortRequested

    // Configurable responses — use the list variants to return different values on retries.
    // When a list is set, responses are popped from the front; when empty, Cancelled is returned.
    var selectGameModeResponse: Result<UIError, GameMode> = Result.Success(GameMode.LOCAL)
    var configureLocalGameResponse: Result<UIError, LocalGameConfig> = Result.Success(
        LocalGameConfig(whiteName = "White", blackName = "Black", timeControl = null)
    )
    var configureLocalGameResponses: MutableList<Result<UIError, LocalGameConfig>>? = null
    var configureEngineGameResponse: Result<UIError, EngineGameConfig> = Result.Success(
        EngineGameConfig(playerColor = Color.WHITE, engineSettings = emptyMap(), timeControl = null)
    )
    var configureEngineGameResponses: MutableList<Result<UIError, EngineGameConfig>>? = null
    var configureRemoteGameResponse: Result<UIError, RemoteGameConfig> = Result.Success(
        RemoteGameConfig(platformSettings = emptyMap(), seekSettings = emptyMap())
    )
    var configureRemoteGameResponses: MutableList<Result<UIError, RemoteGameConfig>>? = null
    var promptPromotionResponse: Result<UIError, PromotionPiece> = Result.Success(PromotionPiece.QUEEN)
    var promptDisambiguationResponse: Result<UIError, String> = Result.Success("")
    var promptDrawResponseResult: Result<UIError, Boolean> = Result.Success(false)
    var selectEngineResponse: Result<UIError, String> = Result.Success("engine-1")
    var selectRemotePlatformResponse: Result<UIError, String> = Result.Success("lichess")

    // Tracking
    var lastGameStartedInfo: GameStartedInfo? = null
    var lastGameSummary: GameSummary? = null
    var lastOpponentMove: ShowOpponentMoveParams? = null
    var lastError: UIMessage? = null
    var lastDesync: Pair<String, UIMessage>? = null
    var displayErrorCount = 0

    var lastConfigureLocalError: UIMessage? = null
    var lastConfigureEngineParams: ConfigureEngineGameParams? = null
    var lastConfigureRemoteParams: ConfigureRemoteGameParams? = null

    override suspend fun selectGameMode(availableModes: List<GameMode>) = selectGameModeResponse
    override suspend fun configureLocalGame(errorMessage: UIMessage?): Result<UIError, LocalGameConfig> {
        lastConfigureLocalError = errorMessage
        return configureLocalGameResponses?.removeFirstOrNull() ?: configureLocalGameResponse
    }
    override suspend fun selectEngine(engines: List<EngineInfo>) = selectEngineResponse
    override suspend fun configureEngineGame(params: ConfigureEngineGameParams): Result<UIError, EngineGameConfig> {
        lastConfigureEngineParams = params
        return configureEngineGameResponses?.removeFirstOrNull() ?: configureEngineGameResponse
    }
    override suspend fun selectRemotePlatform(platforms: List<PlatformInfo>) = selectRemotePlatformResponse
    override suspend fun configureRemoteGame(params: ConfigureRemoteGameParams): Result<UIError, RemoteGameConfig> {
        lastConfigureRemoteParams = params
        return configureRemoteGameResponses?.removeFirstOrNull() ?: configureRemoteGameResponse
    }

    override suspend fun promptPromotion(move: String, color: Color) = promptPromotionResponse
    override suspend fun promptMoveDisambiguation(moves: List<String>, color: Color) = promptDisambiguationResponse

    var lastDrawResponsePrompt: Pair<String, DrawOffer>? = null

    override suspend fun promptDrawResponse(gameId: String, from: DrawOffer): Result<UIError, Boolean> {
        lastDrawResponsePrompt = gameId to from
        return promptDrawResponseResult
    }

    var seekingGameNotified = false

    override suspend fun onSeekingGame() {
        seekingGameNotified = true
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

    override suspend fun displayError(message: UIMessage): Result<UIError, Unit> {
        lastError = message
        displayErrorCount++
        return Result.Success(Unit)
    }

    override suspend fun boardDesync(expectedFen: String, message: UIMessage): Result<UIError, Unit> {
        lastDesync = expectedFen to message
        return Result.Success(Unit)
    }

    suspend fun emitNewGameRequested() = _newGameRequested.emit(Unit)
    suspend fun emitMoveReady(gameId: String) = _moveReady.emit(MoveReadyEvent(gameId))
    suspend fun emitResignRequested(gameId: String) = _resignRequested.emit(ResignRequestedEvent(gameId))
    suspend fun emitDrawRequested(gameId: String) = _drawRequested.emit(DrawRequestedEvent(gameId))
    suspend fun emitAbortRequested(gameId: String) = _abortRequested.emit(AbortRequestedEvent(gameId))
}

// ---------------------------------------------------------------------------
// Fake Engine
// ---------------------------------------------------------------------------

class FakeEngineModule : EngineAdapterModule {
    private val _errors = MutableSharedFlow<EngineErrorEvent>()
    override val errors: SharedFlow<EngineErrorEvent> = _errors

    var capabilities = EngineCapabilities(name = "FakeEngine", author = "Test", parameters = emptyList())
    var capabilitiesFail: EngineError? = null
    var configureFail: EngineError? = null
    var bestMoveResponse: Result<EngineError, BestMoveResult> =
        Result.Success(BestMoveResult(move = "e7e5", score = null, depth = null, pv = null))
    var lastConfigureSettings: Map<String, Any>? = null
    var newGameCalled = false
    var lastBestMoveParams: GetBestMoveParams? = null

    override suspend fun getCapabilities(): Result<EngineError, EngineCapabilities> {
        capabilitiesFail?.let { return Result.Failure(it) }
        return Result.Success(capabilities)
    }

    override suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit> {
        configureFail?.let { return Result.Failure(it) }
        lastConfigureSettings = settings
        return Result.Success(Unit)
    }

    override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> {
        lastBestMoveParams = params
        return bestMoveResponse
    }

    override suspend fun stop(): Result<EngineError, Unit> = Result.Success(Unit)

    override suspend fun newGame(): Result<EngineError, Unit> {
        newGameCalled = true
        return Result.Success(Unit)
    }

    suspend fun emitError(event: EngineErrorEvent) = _errors.emit(event)
}

// ---------------------------------------------------------------------------
// Fake Remote
// ---------------------------------------------------------------------------

class FakeRemoteModule : RemoteAdapterModule {
    private val _gameStarted = MutableSharedFlow<GameStartedEvent>()
    private val _opponentMove = MutableSharedFlow<OpponentMoveEvent>()
    private val _gameEnded = MutableSharedFlow<RemoteGameEndedEvent>()
    private val _drawOffered = MutableSharedFlow<Unit>()
    private val _errors = MutableSharedFlow<RemoteErrorEvent>()

    override val gameStarted: SharedFlow<GameStartedEvent> = _gameStarted
    override val opponentMove: SharedFlow<OpponentMoveEvent> = _opponentMove
    override val gameEnded: SharedFlow<RemoteGameEndedEvent> = _gameEnded
    override val drawOffered: SharedFlow<Unit> = _drawOffered
    override val errors: SharedFlow<RemoteErrorEvent> = _errors

    var capabilities = RemoteCapabilities(
        platform = "FakePlatform", features = emptyList(),
        parameters = emptyList(), seekParameters = emptyList(),
    )
    var capabilitiesFail: RemoteError? = null
    var configureFail: RemoteError? = null
    var configureResponse = ConfigureResult(user = RemoteUser("testuser", 1500))
    var seekFail: RemoteError? = null
    var lastConfigureSettings: Map<String, Any>? = null
    var lastSeekSettings: Map<String, Any>? = null
    var lastSentMove: String? = null
    var resignCalled = false
    var drawOfferCalled = false
    var lastDrawResponse: Boolean? = null
    var disconnectCalled = false

    override suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities> {
        capabilitiesFail?.let { return Result.Failure(it) }
        return Result.Success(capabilities)
    }

    override suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult> {
        configureFail?.let { return Result.Failure(it) }
        lastConfigureSettings = settings
        return Result.Success(configureResponse)
    }

    override suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit> {
        seekFail?.let { return Result.Failure(it) }
        lastSeekSettings = settings
        return Result.Success(Unit)
    }

    override suspend fun cancelSeek(): Result<RemoteError, Unit> = Result.Success(Unit)

    override suspend fun sendMove(move: String): Result<RemoteError, Unit> {
        lastSentMove = move
        return Result.Success(Unit)
    }

    override suspend fun resign(): Result<RemoteError, Unit> {
        resignCalled = true
        return Result.Success(Unit)
    }

    override suspend fun offerDraw(): Result<RemoteError, Unit> {
        drawOfferCalled = true
        return Result.Success(Unit)
    }

    override suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit> {
        lastDrawResponse = accept
        return Result.Success(Unit)
    }

    override suspend fun disconnect(): Result<RemoteError, Unit> {
        disconnectCalled = true
        return Result.Success(Unit)
    }

    suspend fun emitGameStarted(event: GameStartedEvent) = _gameStarted.emit(event)
    suspend fun emitOpponentMove(event: OpponentMoveEvent) = _opponentMove.emit(event)
    suspend fun emitGameEnded(event: RemoteGameEndedEvent) = _gameEnded.emit(event)
    suspend fun emitDrawOffered() = _drawOffered.emit(Unit)
    suspend fun emitError(event: RemoteErrorEvent) = _errors.emit(event)
}
