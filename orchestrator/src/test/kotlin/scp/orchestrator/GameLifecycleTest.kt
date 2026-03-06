package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GameLifecycleTest {

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

    private suspend fun CoroutineScope.startLocalGame(): Orchestrator {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        return orchestrator
    }

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
    // Game end transitions
    // -----------------------------------------------------------------------

    @Test
    fun `game ended event transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = "1-0", reason = "checkmate", fen = ""))

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `flag fall in local game ends game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `flag fall in engine game ends game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `flag fall in remote game is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        gc.emitFlagFall(FlagFallEvent(gameId = orchestrator.session!!.gameId, color = Color.WHITE))

        // Should still be in game, not Idle
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertNotNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `remote game ended sets result and transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        remote.emitGameEnded(RemoteGameEndedEvent(result = "0-1", reason = "resign"))

        assertNotNull(gc.lastSetResult)
        assertEquals("0-1", gc.lastSetResult!!.result)
        assertEquals("resign", gc.lastSetResult!!.reason)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Abort
    // -----------------------------------------------------------------------

    @Test
    fun `abort transitions to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitAbortRequested(gameId)

        assertEquals(gameId, gc.lastAbort)
        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `abort in remote game disconnects from remote`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        ui.emitAbortRequested(gameId)

        assertTrue(remote.disconnectCalled)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Error handling during game
    // -----------------------------------------------------------------------

    @Test
    fun `board error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        board.emitError(BoardErrorEvent(code = "SENSOR_FAILURE", message = "Sensor malfunction"))

        assertEquals("Board error: Sensor malfunction", ui.lastError)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `engine error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)

        engine.emitError(EngineErrorEvent(code = "ENGINE_CRASHED", message = "Engine process died"))

        assertEquals("Engine error: Engine process died", ui.lastError)

        orchestrator.stop()
    }

    @Test
    fun `remote error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        remote.emitError(RemoteErrorEvent(code = "CONNECTION_LOST", message = "Connection lost"))

        assertEquals("Remote error: Connection lost", ui.lastError)
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `events are ignored in Idle when no game is active`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.emitMoveReady("nonexistent")
        ui.emitResignRequested("nonexistent")

        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `can start new game after previous game ends`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

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
    }
}
