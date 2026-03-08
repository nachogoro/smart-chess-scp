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
    ): Orchestrator {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)
        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = playerColor, engineSettings = emptyMap(), timeControl = null)
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
    fun `board desync triggers ui notification`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        // Board unchanged = no legal move matches = desync
        board.boardState = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)
        ui.emitMoveReady(gameId)

        assertNotNull(ui.lastDesync)

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
        gc.submitMoveFail = GameError.IllegalMove("Not your turn")
        ui.emitMoveReady(gameId)

        assertEquals("Move rejected: Not your turn", ui.lastError)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `board state read failure during move shows error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        board.failWith = BoardError.SensorFailure()
        ui.emitMoveReady(gameId)

        assertEquals("Failed to read board state", ui.lastError)
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
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.WHITE, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)

        orchestrator.stop()
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

        assertEquals("Engine error: Process died", ui.lastError)

        orchestrator.stop()
    }

    @Test
    fun `engine move rejected by game controller shows error`() = runTest(UnconfinedTestDispatcher()) {
        // Engine plays white (moves first), so engine move is the first submitMove call
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.BLACK, engineSettings = emptyMap(), timeControl = null)
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e2e4"))
        gc.submitMoveFail = GameError.IllegalMove("Invalid engine move")
        ui.emitNewGameRequested()

        assertEquals("Engine move rejected: Invalid engine move", ui.lastError)

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
    fun `resign in remote game also notifies remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        ui.emitResignRequested(gameId)

        assertTrue(remote.resignCalled)
        assertNotNull(gc.lastResign)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Draw
    // -----------------------------------------------------------------------

    @Test
    fun `draw claim in local game calls game controller`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawClaimRequested(gameId)

        assertNotNull(gc.lastDrawClaim)
        assertEquals(gameId, gc.lastDrawClaim)

        orchestrator.stop()
    }

    @Test
    fun `draw offer in local game calls game controller`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawOfferRequested(gameId)

        assertNotNull(gc.lastDrawOffer)
        assertEquals(gameId, gc.lastDrawOffer!!.first)
        assertEquals(Color.WHITE, gc.lastDrawOffer!!.second)

        orchestrator.stop()
    }

    @Test
    fun `draw offer in engine game shows unsupported error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawOfferRequested(gameId)

        assertEquals("Draw offers are not supported against the engine", ui.lastError)

        orchestrator.stop()
    }

    @Test
    fun `draw offer in remote game sends to remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        ui.emitDrawOfferRequested(gameId)

        assertTrue(remote.drawOfferCalled)
        assertNotNull(gc.lastDrawOffer)

        orchestrator.stop()
    }

    @Test
    fun `remote draw offered prompts user and responds accept`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        ui.promptDrawResponseResult = Result.Success(true)
        remote.emitDrawOffered()

        assertEquals(true, remote.lastDrawResponse)
        assertNotNull(gc.lastDrawResponse)
        assertEquals(true, gc.lastDrawResponse!!.second)

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
}
