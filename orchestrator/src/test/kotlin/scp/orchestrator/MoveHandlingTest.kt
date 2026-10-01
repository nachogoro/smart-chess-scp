package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MoveHandlingTest {

    private lateinit var board: FakeBoardModule
    private lateinit var gc: FakeGameControllerModule
    private lateinit var ui: FakeUIModule
    private lateinit var engine: FakeEngineModule
    private lateinit var remote: FakeRemoteModule

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @BeforeTest
    fun setUp() {
        board = FakeBoardModule()
        gc = FakeGameControllerModule()
        ui = FakeUIModule()
        engine = FakeEngineModule()
        remote = FakeRemoteModule()
    }

    private fun createOrchestrator(
        withEngine: Boolean = false,
        withRemote: Boolean = false,
    ) = Orchestrator(
        board = board,
        gameController = gc,
        ui = ui,
        engines = if (withEngine) listOf(engine) else emptyList(),
        remotes = if (withRemote) listOf(remote) else emptyList(),
    )

    private fun CoroutineScope.launchOrchestrator(orchestrator: Orchestrator) {
        launch { orchestrator.start() }
    }

    /** Start a local game and return the orchestrator. */
    private suspend fun CoroutineScope.startLocalGame(): Orchestrator {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        return orchestrator
    }

    /** Start an engine game and return the orchestrator. */
    private suspend fun CoroutineScope.startEngineGame(
        playerColor: Color = Color.WHITE,
        timeControl: TimeControl? = null,
    ): Orchestrator {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = playerColor, playerName = "Player", engineSettings = emptyMap(), timeControl = timeControl)
        )
        ui.emitNewGameRequested()
        return orchestrator
    }

    /** Start a remote game and return the orchestrator. */
    private suspend fun CoroutineScope.startRemoteGame(
        playerColor: Color = Color.WHITE,
    ): Orchestrator {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()
        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = playerColor,
            opponent = "opp", fen = startFen, timeControl = null,
        ))
        return orchestrator
    }

    // -----------------------------------------------------------------------
    // Player move handling
    // -----------------------------------------------------------------------

    @Test
    fun `player move inferred and submitted in local game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        ui.emitMoveReady(gameId)

        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e2e4", gc.lastSubmittedMove!!.move)
        assertEquals(gameId, gc.lastSubmittedMove!!.gameId)

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.BLACK, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)

        orchestrator.stop()
    }

    @Test
    fun `the UI is told of the player's own move`() = runTest(UnconfinedTestDispatcher()) {
        // No ui.showOpponentMove is sent for it, so this is the only way a UI drawing the position
        // hears of it.
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        ui.emitMoveReady(gameId)

        // No en passant target in the result: the rules library records one only where a capture
        // could actually use it, and after 1.e4 no black pawn can.
        assertEquals(
            listOf(MovePlayedInfo(
                gameId = gameId,
                move = "e2e4",
                san = "e4",
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                color = Color.WHITE,
                clocks = null,
            )),
            ui.movesPlayed,
        )
        assertNull(ui.lastOpponentMove)

        orchestrator.stop()
    }

    @Test
    fun `board desync triggers ui notification`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        // Two plies ahead of the game: no single legal move explains it, so the board and the game
        // genuinely disagree.
        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            BoardCapabilityLevel.FULL,
        )
        ui.emitMoveReady(gameId)

        assertNotNull(ui.lastDesync)

        orchestrator.stop()
    }

    @Test
    fun `board still at the current position reports an error rather than a desync`() =
        runTest(UnconfinedTestDispatcher()) {
            val orchestrator = startLocalGame()
            val gameId = orchestrator.session!!.gameId

            // Signalling before touching a piece is an ordinary slip, so it must not be dressed up
            // as the board having drifted out of sync.
            board.boardState = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)
            ui.emitMoveReady(gameId)

            assertNull(ui.lastDesync)
            assertEquals(UIMessage.BoardUnchanged, ui.lastError)

            orchestrator.stop()
        }

    @Test
    fun `move rejected by game controller shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        gc.submitMoveFail = GameError.NotYourTurn()
        ui.emitMoveReady(gameId)

        assertEquals("NOT_YOUR_TURN", assertIs<UIMessage.MoveRejected>(ui.lastError).error.code)
        assertTrue(ui.movesPlayed.isEmpty())
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `board state read failure during move shows error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        board.failWith = BoardError.SensorFailure()
        ui.emitMoveReady(gameId)

        assertEquals("SENSOR_FAILURE", assertIs<UIMessage.BoardReadFailed>(ui.lastError).error.code)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `player move in remote game is sent to remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        ui.emitMoveReady(gameId)

        assertEquals("e2e4", remote.lastSentMove)
        assertEquals(listOf("e2e4"), ui.movesPlayed.map { it.move })
        assertIs<OrchestratorState.AwaitingOpponentMove>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Engine move handling
    // -----------------------------------------------------------------------

    @Test
    fun `engine move submitted and shown to UI after player moves`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        // Player moves e2e4
        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e7e5"))
        ui.emitMoveReady(gameId)

        // After player moves, engine should move automatically
        assertNotNull(ui.lastOpponentMove)
        assertEquals("e7e5", ui.lastOpponentMove!!.move)

        // Both moves are announced, each as soon as it is accepted, and the engine's before the
        // player is asked to make it on the board.
        assertEquals(listOf("e2e4", "e7e5"), ui.movesPlayed.map { it.move })
        assertEquals(listOf(Color.WHITE, Color.BLACK), ui.movesPlayed.map { it.color })
        assertEquals(
            listOf("ui.onGameStarted", "ui.onMovePlayed", "ui.onMovePlayed", "ui.showOpponentMove"),
            ui.notifications,
        )
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.WHITE, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)

        orchestrator.stop()
    }

    @Test
    fun `engine is given the game history and a time budget`() = runTest(UnconfinedTestDispatcher()) {
        // Sending only the current position would leave the engine unable to see repetitions, and
        // sending no budget would leave it searching to whatever fixed depth its adapter defaults
        // to, which bears no relation to the clock and is how an engine flags its opponent.
        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 300_000, incrementMs = 2_000)
        val orchestrator = startEngineGame(playerColor = Color.WHITE, timeControl = tc)
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e7e5"))
        ui.emitMoveReady(gameId)

        val asked = assertNotNull(engine.lastBestMoveParams)
        assertEquals(startFen, asked.fen, "the engine searches from the starting position")
        assertEquals(listOf("e2e4"), asked.moves, "...with the moves played from it")
        // 300000 / 30 + 2000 * 3/4 = 11500, held down to the 10s ceiling.
        assertEquals(10_000, asked.timeMs)
        assertNull(asked.maxDepth, "a clock decides the search, not a depth")

        orchestrator.stop()
    }

    @Test
    fun `an untimed engine game still bounds the search`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE, timeControl = null)
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e7e5"))
        ui.emitMoveReady(gameId)

        val asked = assertNotNull(engine.lastBestMoveParams)
        assertNotNull(asked.maxDepth, "with no clock to divide up, a depth bounds the search")
        assertNotNull(asked.timeMs, "and a ceiling stops it hanging the game")

        orchestrator.stop()
    }

    @Test
    fun `think time is a share of the clock, and never more than it holds`() {
        // A comfortable clock: a thirtieth of it, plus most of the increment.
        assertEquals(120_000 / 30 + 1_500, engineThinkTimeMs(120_000, 2_000))
        // A short one is bounded by what is actually left, less the margin to send the move.
        assertTrue(engineThinkTimeMs(1_000, 2_000) <= 800)
        // Never longer than the ceiling, however much time there is.
        assertEquals(10_000, engineThinkTimeMs(60_000_000, 60_000))
    }

    @Test
    fun `engine getBestMove failure shows error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Failure(EngineError.EngineCrashed("Process died"))
        ui.emitMoveReady(gameId)

        assertEquals("ENGINE_CRASHED", assertIs<UIMessage.EngineFailed>(ui.lastError).error.code)

        orchestrator.stop()
    }

    @Test
    fun `engine move rejected by game controller shows error`() = runTest(UnconfinedTestDispatcher()) {
        // Engine plays white (moves first), so engine move is the first submitMove call
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.BLACK, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e2e4"))
        gc.submitMoveFail = GameError.IllegalMove("e2e5")
        ui.emitNewGameRequested()

        assertEquals("ILLEGAL_MOVE", assertIs<UIMessage.EngineMoveRejected>(ui.lastError).error.code)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Remote opponent move handling
    // -----------------------------------------------------------------------

    @Test
    fun `remote opponent move is submitted and shown to UI`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.BLACK)

        assertIs<OrchestratorState.AwaitingOpponentMove>(orchestrator.state)

        remote.emitOpponentMove(OpponentMoveEvent(move = "e2e4", clocks = null))

        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e2e4", gc.lastSubmittedMove!!.move)
        assertNotNull(ui.lastOpponentMove)
        assertEquals("e2e4", ui.lastOpponentMove!!.move)
        assertEquals(Color.WHITE, ui.movesPlayed.single().color)
        assertEquals(
            listOf("ui.onMovePlayed", "ui.showOpponentMove"),
            ui.notifications.takeLast(2),
        )
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Promotion flow
    // -----------------------------------------------------------------------

    @Test
    fun `promotion flow — user selects piece, correct UCI submitted`() = runTest(UnconfinedTestDispatcher()) {
        // Use occupancy-level board so promotion is ambiguous (NeedsPromotion)
        board = FakeBoardModule(level = BoardCapabilityLevel.OCCUPANCY)
        gc = FakeGameControllerModule()
        ui = FakeUIModule()

        val orchestrator = Orchestrator(
            board = board,
            gameController = gc,
            ui = ui,
            engines = emptyList(),
            remotes = emptyList(),
        )
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        // Seed the game controller with a promotion position
        val promotionFen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"
        gc.setGameState(GameState(
            gameId = gameId,
            fen = promotionFen,
            moves = emptyList(),
            status = GameStatus.IN_PROGRESS,
            turn = Color.WHITE,
            clocks = null,
            result = GameResult.IN_PROGRESS,
        ))

        // Board shows the state after e7e8 promotion (at occupancy level, all promotions look the same)
        val afterFen = "4Q1k1/8/8/8/8/8/8/4K3 b - - 0 1"
        board.boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.OCCUPANCY)
        ui.promptPromotionResponse = Result.Success(PromotionPiece.ROOK)

        ui.emitMoveReady(gameId)

        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e7e8r", gc.lastSubmittedMove!!.move)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Resign
    // -----------------------------------------------------------------------

    @Test
    fun `resign in local game resigns for current turn color`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitResignRequested(gameId)

        assertNotNull(gc.lastResign)
        assertEquals(gameId, gc.lastResign!!.first)
        assertEquals(Color.WHITE, gc.lastResign!!.second)

        orchestrator.stop()
    }

    @Test
    fun `resign in engine game resigns for player color`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        ui.emitResignRequested(gameId)

        assertEquals(Color.WHITE, gc.lastResign!!.second)

        orchestrator.stop()
    }

    @Test
    fun `resign in remote game is asked of the server, not decided here`() =
        runTest(UnconfinedTestDispatcher()) {
            val orchestrator = startRemoteGame(Color.WHITE)
            val gameId = orchestrator.session!!.gameId

            ui.emitResignRequested(gameId)

            assertTrue(remote.resignCalled)
            // Resigning locally as well would commit to an outcome the server has not agreed to,
            // leaving the setResult that relays the real ending to find the game already over.
            assertNull(gc.lastResign)
            assertNotNull(orchestrator.session)

            remote.emitGameEnded(RemoteGameEndedEvent(GameResult.BLACK_WINS, Termination.Resignation))

            assertIs<OrchestratorState.Idle>(orchestrator.state)
            assertEquals(Termination.Resignation, ui.lastGameSummary?.termination)

            orchestrator.stop()
        }

    // -----------------------------------------------------------------------
    // Draw
    // -----------------------------------------------------------------------

    @Test
    fun `draw request tries claim first`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        // claimDraw is always attempted first
        assertNotNull(gc.lastDrawClaim)
        assertEquals(gameId, gc.lastDrawClaim)

        orchestrator.stop()
    }

    @Test
    fun `draw request offers draw in local game when claim fails`() = runTest(UnconfinedTestDispatcher()) {
        // Use a custom GC that rejects claims
        val claimRejectingGc = object : FakeGameControllerModule() {
            override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
                lastDrawClaim = gameId
                return Result.Failure(GameError.InvalidClaim())
            }
        }
        gc = claimRejectingGc
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertNotNull(claimRejectingGc.lastDrawOffer)
        assertEquals(gameId, claimRejectingGc.lastDrawOffer!!.first)
        assertEquals(Color.WHITE, claimRejectingGc.lastDrawOffer!!.second)

        orchestrator.stop()
    }

    /** A game controller that refuses draw claims, so the offer path is the one exercised. */
    private fun claimRejectingController() = object : FakeGameControllerModule() {
        override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
            lastDrawClaim = gameId
            return Result.Failure(GameError.InvalidClaim())
        }
    }

    @Test
    fun `local draw offer is put to the opponent and agreed when accepted`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = claimRejectingController()
            gc = controller
            ui.promptDrawResponseResult = Result.Success(true)
            val orchestrator = createOrchestrator()
            launchOrchestrator(orchestrator)
            ui.emitNewGameRequested()
            val gameId = orchestrator.session!!.gameId

            ui.emitDrawRequested(gameId)

            // White is to move at the start, so the offer is attributed to White and the person
            // on the other side of the board is asked. The name is the one this game was set up
            // with and is passed through untouched; the colour is there for a game that has none,
            // where wording the offer is the UI's job rather than this module's.
            assertEquals(
                gameId to DrawOffer(name = "White", color = Color.WHITE),
                ui.lastDrawResponsePrompt,
            )
            assertEquals(Triple(gameId, Color.BLACK, true), controller.lastDrawResponse)

            orchestrator.stop()
        }

    @Test
    fun `local draw offer declined leaves the game running`() = runTest(UnconfinedTestDispatcher()) {
        val controller = claimRejectingController()
        gc = controller
        ui.promptDrawResponseResult = Result.Success(false)
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertNotNull(ui.lastDrawResponsePrompt)
        // Answered rather than left pending: the offer must not survive to be accepted by a later
        // stray Draw tap.
        assertEquals(Triple(gameId, Color.BLACK, false), controller.lastDrawResponse)

        orchestrator.stop()
    }

    @Test
    /** A controller reporting a draw offer outstanding from [offeredBy], and no claim available. */
    private fun offerPendingFrom(offeredBy: Color) = object : FakeGameControllerModule() {
        override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
            lastDrawClaim = gameId
            return Result.Failure(GameError.InvalidClaim())
        }

        override suspend fun getState(gameId: String): Result<GameError, GameState> =
            when (val state = super.getState(gameId)) {
                is Result.Success -> Result.Success(
                    state.value.copy(status = GameStatus.DRAW_OFFERED, drawOffer = offeredBy)
                )
                is Result.Failure -> state
            }
    }

    @Test
    fun `draw request accepts a pending offer from the opponent`() = runTest(UnconfinedTestDispatcher()) {
        // White is to move, so White is asking; the offer waiting is Black's.
        val drawOfferedGc = offerPendingFrom(Color.BLACK)
        gc = drawOfferedGc
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertEquals(Triple(gameId, Color.WHITE, true), drawOfferedGc.lastDrawResponse)

        orchestrator.stop()
    }

    @Test
    fun `a player asking twice does not accept their own offer`() = runTest(UnconfinedTestDispatcher()) {
        // The remote flow leaves an offer standing while the server is asked, so without checking
        // whose it is, a second tap of Draw walks into the accept branch and ends the player's
        // own game.
        val drawOfferedGc = offerPendingFrom(Color.WHITE)
        gc = drawOfferedGc
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()
        remote.emitGameStarted(GameStartedEvent("r1", Color.WHITE, "opp", startFen, null))
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertNull(drawOfferedGc.lastDrawResponse, "a draw takes two players")
        assertNull(remote.lastDrawResponse)

        orchestrator.stop()
    }

    @Test
    fun `draw request in engine game shows unsupported error when claim fails`() = runTest(UnconfinedTestDispatcher()) {
        val claimRejectingGc = object : FakeGameControllerModule() {
            override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
                lastDrawClaim = gameId
                return Result.Failure(GameError.InvalidClaim())
            }
        }
        gc = claimRejectingGc
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
        )
        ui.emitNewGameRequested()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertEquals(UIMessage.DrawNotSupportedByEngine, ui.lastError)

        orchestrator.stop()
    }

    @Test
    fun `draw request in remote game sends to remote server when claim fails`() = runTest(UnconfinedTestDispatcher()) {
        val claimRejectingGc = object : FakeGameControllerModule() {
            override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
                lastDrawClaim = gameId
                return Result.Failure(GameError.InvalidClaim())
            }
        }
        gc = claimRejectingGc
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()
        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawRequested(gameId)

        assertTrue(remote.drawOfferCalled)
        assertNotNull(claimRejectingGc.lastDrawOffer)

        orchestrator.stop()
    }

    @Test
    fun `remote draw offered prompts user and responds accept`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        ui.promptDrawResponseResult = Result.Success(true)
        remote.emitDrawOffered()

        assertEquals(true, remote.lastDrawResponse)
        assertNotNull(gc.lastDrawResponse)
        assertEquals(true, gc.lastDrawResponse!!.third)

        orchestrator.stop()
    }

    @Test
    fun `remote draw offered — user declines — respondDraw false sent`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        ui.promptDrawResponseResult = Result.Success(false)
        remote.emitDrawOffered()

        assertEquals(false, remote.lastDrawResponse)
        // When declined, game controller respondDraw should NOT be called
        assertNull(gc.lastDrawResponse)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Board actuation
    // -----------------------------------------------------------------------

    /** Plays the player's e2e4 from the board and lets the engine answer with [engineMove]. */
    private suspend fun CoroutineScope.playerMoveThenEngine(engineMove: String): Orchestrator {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId
        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = engineMove))
        ui.emitMoveReady(gameId)
        return orchestrator
    }

    @Test
    fun `an engine move is played on a board that can move the pieces`() = runTest(UnconfinedTestDispatcher()) {
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = true))
        val orchestrator = playerMoveThenEngine("e7e5")

        assertEquals(listOf<PieceTransfer>(PieceTransfer.Move("e7", "e5")), board.actuated.single())
        assertEquals(BoardUpdate.COMPLETE, ui.lastOpponentMove!!.boardUpdate)
        // The point of actuating: the board is left in a state the next inference works from.
        assertTrue(boardShows(ui.lastOpponentMove!!.fen, board.boardState, BoardCapabilityLevel.FULL))

        orchestrator.stop()
    }

    @Test
    fun `a board that only senses is not asked to move anything`() = runTest(UnconfinedTestDispatcher()) {
        // board is left as the default FakeBoardModule, which declares no actuation.
        val orchestrator = playerMoveThenEngine("e7e5")

        assertTrue(board.actuated.isEmpty())
        assertEquals(BoardUpdate.MANUAL, ui.lastOpponentMove!!.boardUpdate)

        orchestrator.stop()
    }

    @Test
    fun `a successful actuation is not read back`() = runTest(UnconfinedTestDispatcher()) {
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = true))
        val orchestrator = playerMoveThenEngine("e7e5")

        // One read, for the player's own move. Reading again after every opponent move would only
        // report sooner a failure the board did not report at all; the player's next signal reads
        // it anyway.
        assertEquals(1, board.stateReads)

        orchestrator.stop()
    }

    @Test
    fun `a promotion on a board with nothing to place is left half done`() = runTest(UnconfinedTestDispatcher()) {
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = false))
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        // Black pawn on b2, white rook on a1: the engine's next move promotes with a capture.
        val beforeFen = "6k1/8/8/8/8/8/1p5P/R5K1 w - - 0 40"
        gc.setGameState(GameState(
            gameId = gameId, fen = beforeFen, moves = emptyList(),
            status = GameStatus.IN_PROGRESS, turn = Color.WHITE,
            clocks = null, result = GameResult.IN_PROGRESS,
        ))
        board.boardState = boardStateFromFen("6k1/8/8/8/8/7P/1p6/R5K1 b - - 0 40", BoardCapabilityLevel.FULL)
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "b2a1q"))
        ui.emitMoveReady(gameId)

        // The rook comes off and so does the pawn; the queen is the player's to put down. The pawn
        // must not be left standing — that is a position that looks legal and is not this game's.
        assertEquals(
            listOf(PieceTransfer.Remove("a1"), PieceTransfer.Remove("b2")),
            board.actuated.single(),
        )
        assertEquals(BoardUpdate.PARTIAL, ui.lastOpponentMove!!.boardUpdate)

        orchestrator.stop()
    }

    @Test
    fun `an actuation failure is reported as a desync`() = runTest(UnconfinedTestDispatcher()) {
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = true))
        board.movePiecesFail = BoardError.ActuationFailed(completed = 0, diagnostic = "jammed")
        val orchestrator = playerMoveThenEngine("e7e5")

        // The move stands — it was accepted before the board was ever asked to play it. What is
        // wrong is the physical board, which is the desynchronization the protocol already had.
        assertEquals("e7e5", gc.lastSubmittedMove!!.move)
        assertEquals(BoardUpdate.PARTIAL, ui.lastOpponentMove!!.boardUpdate)

        val desync = assertNotNull(ui.lastDesync)
        assertEquals(ui.lastOpponentMove!!.fen, desync.first)
        val message = assertIs<UIMessage.BoardFailed>(desync.second)
        assertEquals("ACTUATION_FAILED", message.error.code)

        orchestrator.stop()
    }

    @Test
    fun `a board left in the right position after a failure is not called desynchronized`() = runTest(UnconfinedTestDispatcher()) {
        // A failure to report is not a failure to move, which is why the board is read back rather
        // than assumed wrong.
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = true))
        board.movePiecesFail = BoardError.ActuationFailed(completed = 1, diagnostic = "lost count")
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId
        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e7e5"))
        ui.onShowOpponentMove = {
            // The pieces did end up where the move left them, the board just could not say so.
            board.boardState = boardStateFromFen(it.fen, BoardCapabilityLevel.FULL)
        }
        ui.emitMoveReady(gameId)

        assertEquals(BoardUpdate.PARTIAL, ui.lastOpponentMove!!.boardUpdate)
        assertNull(ui.lastDesync)

        orchestrator.stop()
    }

    @Test
    fun `a remote opponent's move is actuated too`() = runTest(UnconfinedTestDispatcher()) {
        board = FakeBoardModule(BoardCapabilityLevel.FULL, BoardActuation(canPlacePieces = true))
        val orchestrator = startRemoteGame(Color.BLACK)

        remote.emitOpponentMove(OpponentMoveEvent(move = "e2e4", clocks = null))

        assertEquals(listOf<PieceTransfer>(PieceTransfer.Move("e2", "e4")), board.actuated.single())
        assertEquals(BoardUpdate.COMPLETE, ui.lastOpponentMove!!.boardUpdate)

        orchestrator.stop()
    }
}
