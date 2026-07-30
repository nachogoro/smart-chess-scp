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

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = GameResult.WHITE_WINS, termination = Termination.Checkmate, fen = ""))

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

        gc.emitGameEnded(GameEndedEvent(gameId = gameId1, result = GameResult.DRAW, termination = Termination.Drawn(DrawKind.Other()), fen = ""))
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        // Second game
        gc.nextGameId = "game-2"
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals("game-2", orchestrator.session!!.gameId)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Game summary notification
    // -----------------------------------------------------------------------

    @Test
    fun `game ended notifies UI with summary containing result and reason`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = GameResult.WHITE_WINS, termination = Termination.Checkmate, fen = "final-fen"))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(gameId, summary.gameId)
        assertEquals(GameResult.WHITE_WINS, summary.result)
        assertEquals(Termination.Checkmate, summary.termination)
        assertEquals(GameMode.LOCAL, summary.mode)

        orchestrator.stop()
    }

    @Test
    fun `game summary includes player names from local game`() = runTest(UnconfinedTestDispatcher()) {
        ui.configureLocalGameResponse = Result.Success(
            LocalGameConfig(whiteName = "Alice", blackName = "Bob", timeControl = null)
        )
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = GameResult.BLACK_WINS, termination = Termination.Resignation, fen = ""))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals("Alice", summary.whiteName)
        assertEquals("Bob", summary.blackName)

        orchestrator.stop()
    }

    @Test
    fun `game summary includes move history`() = runTest(UnconfinedTestDispatcher()) {
        // Use a game controller that returns history
        val historyGc = object : FakeGameControllerModule() {
            override suspend fun getHistory(gameId: String): Result<GameError, GameHistory> {
                return Result.Success(GameHistory(listOf(
                    GameHistoryMove(
                        move = "e2e4", san = "e4",
                        fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                        whiteMs = null, blackMs = null, timestamp = "2026-01-01T00:00:00Z",
                    ),
                )))
            }
        }
        val orchestrator = Orchestrator(
            board = board, gameController = historyGc, ui = ui,
        )
        launchOrchestrator(orchestrator)
        ui.emitNewGameRequested()
        val gameId = orchestrator.session!!.gameId

        historyGc.emitGameEnded(GameEndedEvent(gameId = gameId, result = GameResult.DRAW, termination = Termination.Stalemate, fen = ""))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(1, summary.moves.size)
        assertEquals("e2e4", summary.moves[0].move)
        assertEquals("e4", summary.moves[0].san)

        orchestrator.stop()
    }

    @Test
    fun `abort notifies UI with summary`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        ui.emitAbortRequested(gameId)

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(gameId, summary.gameId)
        assertEquals(GameResult.IN_PROGRESS, summary.result)
        assertEquals(Termination.Aborted, summary.termination)

        orchestrator.stop()
    }

    @Test
    fun `remote game ended notifies UI with summary`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        remote.emitGameEnded(RemoteGameEndedEvent(result = "0-1", reason = "resign"))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(gameId, summary.gameId)
        assertEquals(GameResult.BLACK_WINS, summary.result)
        assertEquals(Termination.Other(text = "resign"), summary.termination)
        assertEquals(GameMode.REMOTE, summary.mode)
        assertEquals(Color.WHITE, summary.playerColor)

        orchestrator.stop()
    }

    @Test
    fun `engine game ended notifies UI with summary`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)
        val gameId = orchestrator.session!!.gameId

        gc.emitGameEnded(GameEndedEvent(gameId = gameId, result = GameResult.WHITE_WINS, termination = Termination.Checkmate, fen = ""))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(GameMode.ENGINE, summary.mode)
        assertEquals(Color.WHITE, summary.playerColor)

        orchestrator.stop()
    }

    @Test
    fun `flag fall notifies UI with summary`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(gameId, summary.gameId)
        assertEquals(Termination.Timeout, summary.termination)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Game started notification
    // -----------------------------------------------------------------------

    @Test
    fun `local game notifies UI with game started info`() = runTest(UnconfinedTestDispatcher()) {
        ui.configureLocalGameResponse = Result.Success(
            LocalGameConfig(whiteName = "Alice", blackName = "Bob", timeControl = null)
        )
        val orchestrator = startLocalGame()

        val info = ui.lastGameStartedInfo
        assertNotNull(info)
        assertEquals(orchestrator.session?.gameId ?: info.gameId, info.gameId)
        assertEquals(GameMode.LOCAL, info.mode)
        assertNull(info.playerColor)
        assertEquals("Alice", info.whiteName)
        assertEquals("Bob", info.blackName)

        orchestrator.stop()
    }

    @Test
    fun `engine game notifies UI with game started info`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)

        val info = ui.lastGameStartedInfo
        assertNotNull(info)
        assertEquals(GameMode.ENGINE, info.mode)
        assertEquals(Color.WHITE, info.playerColor)

        orchestrator.stop()
    }

    @Test
    fun `remote game notifies UI with game started info`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(playerColor = Color.WHITE)

        val info = ui.lastGameStartedInfo
        assertNotNull(info)
        assertEquals(GameMode.REMOTE, info.mode)
        assertEquals(Color.WHITE, info.playerColor)

        orchestrator.stop()
    }
}
