package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {

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

    private fun CoroutineScope.launchOrchestrator(orchestrator: Orchestrator): Job {
        return launch { orchestrator.start() }
    }

    // -----------------------------------------------------------------------
    // Setup flows
    // -----------------------------------------------------------------------

    @Test
    fun `local game setup transitions to AwaitingPlayerMove`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.WHITE, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)
        assertNotNull(orchestrator.session)
        assertEquals(GameMode.LOCAL, orchestrator.session!!.mode)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `user cancelling game mode selection stays Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `engine game setup configures engine and transitions correctly`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, engineSettings = mapOf("Depth" to 10), timeControl = null)
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(GameMode.ENGINE, orchestrator.session!!.mode)
        assertEquals(Color.WHITE, orchestrator.session!!.playerColor)
        assertTrue(engine.newGameCalled)
        assertEquals(mapOf("Depth" to 10), engine.lastConfigureSettings)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `engine game setup with engine playing white triggers engine move`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.BLACK, engineSettings = emptyMap(), timeControl = null)
        )
        engine.bestMoveResponse = Result.Success(BestMoveResult(move = "e2e4"))

        ui.emitNewGameRequested()

        // After engine moves, it should be player's turn (black)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.BLACK, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)
        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e2e4", gc.lastSubmittedMove!!.move)
        assertNotNull(ui.lastOpponentMove)
        assertEquals("e2e4", ui.lastOpponentMove!!.move)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote game setup transitions to SeekingGame`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponse = Result.Success(
            RemoteGameConfig(platformSettings = mapOf("token" to "abc"), seekSettings = mapOf("rated" to true))
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)
        assertEquals(mapOf("token" to "abc"), remote.lastConfigureSettings)
        assertEquals(mapOf("rated" to true), remote.lastSeekSettings)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote game started transitions to AwaitingPlayerMove when player is white`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)

        remote.emitGameStarted(GameStartedEvent(
            gameId = "remote-1",
            color = Color.WHITE,
            opponent = "opponent123",
            fen = startFen,
            timeControl = TimeControl(TimeControlType.FISCHER, 600000, 5000),
        ))

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.WHITE, orchestrator.session!!.playerColor)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote game started transitions to AwaitingOpponentMove when player is black`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "remote-1",
            color = Color.BLACK,
            opponent = "opponent123",
            fen = startFen,
            timeControl = null,
        ))

        assertIs<OrchestratorState.AwaitingOpponentMove>(orchestrator.state)
        assertEquals(Color.BLACK, orchestrator.session!!.playerColor)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Move handling
    // -----------------------------------------------------------------------

    @Test
    fun `player move inferred and submitted in local game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        // Set board to state after e2e4
        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )

        ui.emitMoveReady(gameId)

        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e2e4", gc.lastSubmittedMove!!.move)
        assertEquals(gameId, gc.lastSubmittedMove!!.gameId)

        // After white moves, it's black's turn in local mode
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.BLACK, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `board desync triggers ui notification`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        // Set board to starting position (no move was made — should be a desync)
        board.boardState = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)

        ui.emitMoveReady(gameId)

        assertNotNull(ui.lastDesync)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote opponent move is submitted and shown to UI`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.BLACK,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        assertIs<OrchestratorState.AwaitingOpponentMove>(orchestrator.state)

        // Opponent plays e2e4
        val afterFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        remote.emitOpponentMove(OpponentMoveEvent(move = "e2e4", fen = afterFen, clocks = null))

        assertNotNull(gc.lastSubmittedMove)
        assertEquals("e2e4", gc.lastSubmittedMove!!.move)
        assertNotNull(ui.lastOpponentMove)
        assertEquals("e2e4", ui.lastOpponentMove!!.move)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `player move in remote game is sent to remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        val gameId = orchestrator.session!!.gameId

        board.boardState = boardStateFromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            BoardCapabilityLevel.FULL,
        )
        ui.emitMoveReady(gameId)

        assertEquals("e2e4", remote.lastSentMove)
        assertIs<OrchestratorState.AwaitingOpponentMove>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Resign / Abort / Draw
    // -----------------------------------------------------------------------

    @Test
    fun `resign in local game resigns for current turn color`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        ui.emitResignRequested(gameId)

        assertNotNull(gc.lastResign)
        assertEquals(gameId, gc.lastResign!!.first)
        assertEquals(Color.WHITE, gc.lastResign!!.second)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `resign in engine game resigns for player color`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, engineSettings = emptyMap(), timeControl = null)
        )
        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId
        ui.emitResignRequested(gameId)

        assertEquals(Color.WHITE, gc.lastResign!!.second)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `resign in remote game also notifies remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        val gameId = orchestrator.session!!.gameId
        ui.emitResignRequested(gameId)

        assertTrue(remote.resignCalled)
        assertNotNull(gc.lastResign)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `abort transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        ui.emitAbortRequested(gameId)

        assertEquals(gameId, gc.lastAbort)
        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `abort in remote game disconnects from remote`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        val gameId = orchestrator.session!!.gameId
        ui.emitAbortRequested(gameId)

        assertTrue(remote.disconnectCalled)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `draw claim in local game calls game controller`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        ui.emitDrawClaimRequested(gameId, DrawClaimReason.THREEFOLD_REPETITION)

        assertNotNull(gc.lastDrawClaim)
        assertEquals(gameId, gc.lastDrawClaim!!.first)
        assertEquals(DrawClaimReason.THREEFOLD_REPETITION, gc.lastDrawClaim!!.second)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `draw offer in local game calls game controller`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        ui.emitDrawOfferRequested(gameId)

        assertNotNull(gc.lastDrawOffer)
        assertEquals(gameId, gc.lastDrawOffer!!.first)
        assertEquals(Color.WHITE, gc.lastDrawOffer!!.second)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `draw offer in remote game sends to remote server`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        val gameId = orchestrator.session!!.gameId
        ui.emitDrawOfferRequested(gameId)

        assertTrue(remote.drawOfferCalled)
        assertNotNull(gc.lastDrawOffer)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote draw offered prompts user and responds`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        ui.promptDrawResponseResult = Result.Success(true)
        remote.emitDrawOffered()

        assertEquals(true, remote.lastDrawResponse)
        assertNotNull(gc.lastDrawResponse)
        assertEquals(true, gc.lastDrawResponse!!.second)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Game end transitions
    // -----------------------------------------------------------------------

    @Test
    fun `game ended event transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = "1-0", reason = "checkmate", fen = ""))

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `flag fall in local game ends game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        val gameId = orchestrator.session!!.gameId

        gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `flag fall in remote game is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        gc.emitFlagFall(FlagFallEvent(gameId = orchestrator.session!!.gameId, color = Color.WHITE))

        // Should still be in game, not Idle
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertNotNull(orchestrator.session)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `remote game ended sets result and transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        remote.emitGameEnded(RemoteGameEndedEvent(result = "0-1", reason = "resign"))

        assertNotNull(gc.lastSetResult)
        assertEquals("0-1", gc.lastSetResult!!.result)
        assertEquals("resign", gc.lastSetResult!!.reason)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test
    fun `board error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        board.emitError(BoardErrorEvent(code = "SENSOR_FAILURE", message = "Sensor malfunction"))

        assertEquals("Board error: Sensor malfunction", ui.lastError)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `engine error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        val job = launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, engineSettings = emptyMap(), timeControl = null)
        )
        ui.emitNewGameRequested()

        engine.emitError(EngineErrorEvent(code = "ENGINE_CRASHED", message = "Engine process died"))

        assertEquals("Engine error: Engine process died", ui.lastError)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `events are ignored in Idle when no game is active`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        ui.emitMoveReady("nonexistent")
        ui.emitResignRequested("nonexistent")

        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `can start new game after previous game ends`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        val job = launchOrchestrator(orchestrator)

        // First game
        ui.emitNewGameRequested()
        val gameId1 = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId1, result = "1/2-1/2", reason = "draw", fen = ""))
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        // Second game
        gc.nextGameId = "game-2"
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals("game-2", orchestrator.session!!.gameId)

        orchestrator.stop()
        job.cancelAndJoin()
    }
}
