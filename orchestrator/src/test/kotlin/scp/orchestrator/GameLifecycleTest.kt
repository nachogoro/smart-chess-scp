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
            EngineGameConfig(playerColor = playerColor, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
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

    /**
     * A controller that ends a game the way the real one does: `submitMove` records a terminal
     * status *and* announces the ending on `game.ended`, both before it returns to the caller.
     */
    private class MatingGameController : FakeGameControllerModule() {
        private var ended = false

        override suspend fun getState(gameId: String): Result<GameError, GameState> {
            val base = super.getState(gameId)
            if (base is Result.Success && ended) {
                return Result.Success(
                    base.value.copy(status = GameStatus.FINISHED, result = GameResult.WHITE_WINS)
                )
            }
            return base
        }

        override suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult> {
            val result = super.submitMove(params)
            ended = true
            emitGameEnded(
                GameEndedEvent(params.gameId, GameResult.WHITE_WINS, Termination.Checkmate, "final-fen")
            )
            return result
        }
    }

    @Test
    fun `a mating move is reported to the UI as checkmate`() = runTest(UnconfinedTestDispatcher()) {
        // The controller's game.ended is queued while the orchestrator is still handling the move
        // that caused it. Ending the game from transitionToAwaitingMove as well would win that
        // race and report Termination.Other, losing how the game actually finished.
        gc = MatingGameController()
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        val mateInOne = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2"
        gc.setGameState(
            GameState(gameId, mateInOne, emptyList(), GameStatus.IN_PROGRESS, Color.BLACK, null, GameResult.IN_PROGRESS)
        )
        board.boardState = boardStateFromFen(fenAfter(mateInOne, "d8h4"), BoardCapabilityLevel.FULL)

        ui.emitMoveReady(gameId)

        assertEquals(Termination.Checkmate, ui.lastGameSummary?.termination)
        assertEquals(GameResult.WHITE_WINS, ui.lastGameSummary?.result)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    private fun fenAfter(fen: String, uci: String): String {
        val game = com.nachogoro.simplechess.Game.fromFen(fen).getOrThrow()
        return game.makeMove(game.availableMoves.first { moveToUci(it) == uci }).getOrThrow()
            .currentPosition.fen
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
    fun `an action aimed at a finished game does not act on the current one`() =
        runTest(UnconfinedTestDispatcher()) {
            // A UI can queue a tap that outlives the game it was made in: resign pressed as one
            // game ends, delivered after the next has started. It must not end the new game.
            val orchestrator = startLocalGame()
            val first = orchestrator.session!!.gameId

            gc.nextGameId = "game-2"
            ui.emitNewGameRequested()
            val second = orchestrator.session!!.gameId
            assertNotEquals(first, second)

            ui.emitResignRequested(first)
            ui.emitAbortRequested(first)
            ui.emitDrawRequested(first)
            ui.emitMoveReady(first)

            assertNull(gc.lastResign)
            assertNull(gc.lastAbort)
            assertNull(gc.lastDrawClaim)
            assertNull(gc.lastSubmittedMove)
            assertNotNull(orchestrator.session)
            assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

            orchestrator.stop()
        }

    @Test
    fun `a flag fall says who won on time`() = runTest(UnconfinedTestDispatcher()) {
        // Whose clock ran out settles who won, so the result carries it. Left at its default of
        // "*", a UI could say the game ended on time and never who by.
        val orchestrator = startLocalGame()
        val gameId = orchestrator.session!!.gameId

        gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

        val summary = assertNotNull(ui.lastGameSummary)
        assertEquals(Termination.Timeout, summary.termination)
        assertEquals(GameResult.BLACK_WINS, summary.result)

        orchestrator.stop()
    }

    @Test
    fun `a controller that ends the game itself is left to report it`() =
        runTest(UnconfinedTestDispatcher()) {
            // The protocol allows either. Where the controller ends the game, game.ended is
            // already on its way with the result and the termination, and ending it here as well
            // would win that race and report whatever this end of it happened to assume.
            val endedItself = object : FakeGameControllerModule() {
                override suspend fun getState(gameId: String): Result<GameError, GameState> =
                    when (val state = super.getState(gameId)) {
                        is Result.Success -> Result.Success(state.value.copy(status = GameStatus.FINISHED))
                        is Result.Failure -> state
                    }
            }
            gc = endedItself
            val orchestrator = startLocalGame()
            val gameId = orchestrator.session!!.gameId

            gc.emitFlagFall(FlagFallEvent(gameId = gameId, color = Color.WHITE))

            assertNull(ui.lastGameSummary, "the controller's game.ended reports this one")
            assertNotNull(orchestrator.session)

            // ...and when it arrives, it is what the UI is told.
            gc.emitGameEnded(
                GameEndedEvent(gameId, GameResult.DRAW, Termination.InsufficientMaterial, "")
            )
            assertEquals(
                Termination.InsufficientMaterial,
                ui.lastGameSummary?.termination,
            )

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

        remote.emitGameEnded(RemoteGameEndedEvent(result = GameResult.BLACK_WINS, termination = Termination.Resignation))

        assertNotNull(gc.lastSetResult)
        assertEquals(GameResult.BLACK_WINS, gc.lastSetResult!!.result)
        assertEquals(Termination.Resignation, gc.lastSetResult!!.termination)
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
    fun `abort in remote game is asked of the server, not decided here`() =
        runTest(UnconfinedTestDispatcher()) {
            val orchestrator = startRemoteGame(Color.WHITE)
            val gameId = orchestrator.session!!.gameId

            ui.emitAbortRequested(gameId)

            assertTrue(remote.abortCalled)
            // The server is the authority on how a remote game ends, so the
            // game stays live until it says otherwise. Ending it here would commit to an outcome
            // the server might refuse, with no way back.
            assertNull(gc.lastAbort)
            assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
            assertNotNull(orchestrator.session)

            // ...and it ends when the server says so.
            remote.emitGameEnded(RemoteGameEndedEvent(GameResult.IN_PROGRESS, Termination.Aborted))

            assertIs<OrchestratorState.Idle>(orchestrator.state)
            assertEquals(Termination.Aborted, ui.lastGameSummary?.termination)

            orchestrator.stop()
        }

    // -----------------------------------------------------------------------
    // Error handling during game
    // -----------------------------------------------------------------------

    @Test
    fun `board error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startLocalGame()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        board.emitError(BoardError.SensorFailure(unreadableSquares = listOf("e4", "d5")))

        val failed = assertIs<UIMessage.BoardFailed>(ui.lastError)
        assertEquals("SENSOR_FAILURE", failed.error.code)
        // The squares survive the trip to the UI, which is what lets it name the pieces to
        // re-seat. A bare code would leave "something went wrong" as the only honest thing to
        // say, since the protocol forbids displaying the diagnostic.
        assertEquals(
            listOf("e4", "d5"),
            assertIs<BoardError.SensorFailure>(failed.error).unreadableSquares,
        )
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `a platform failure keeps its status code and the platform's own words`() =
        runTest(UnconfinedTestDispatcher()) {
            val orchestrator = startRemoteGame(Color.WHITE)

            remote.emitError(RemoteError.PlatformError(statusCode = 429, serverText = "Too many requests"))

            val failed = assertIs<UIMessage.RemoteFailed>(ui.lastError)
            val error = assertIs<RemoteError.PlatformError>(failed.error)
            assertEquals(429, error.statusCode)
            assertEquals("Too many requests", error.serverText)

            orchestrator.stop()
        }

    @Test
    fun `engine error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startEngineGame(playerColor = Color.WHITE)

        engine.emitError(EngineError.EngineCrashed(diagnostic = "engine process died"))

        assertEquals("ENGINE_CRASHED", assertIs<UIMessage.EngineFailed>(ui.lastError).error.code)

        orchestrator.stop()
    }

    @Test
    fun `remote error during game shows error and stays in state`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(Color.WHITE)

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)

        remote.emitError(RemoteError.ConnectionLost(diagnostic = "connection lost"))

        assertEquals("CONNECTION_LOST", assertIs<UIMessage.RemoteFailed>(ui.lastError).error.code)
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

        gc.emitGameEnded(GameEndedEvent(gameId = gameId1, result = GameResult.DRAW, termination = Termination.Other(), fen = ""))
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

        remote.emitGameEnded(RemoteGameEndedEvent(result = GameResult.BLACK_WINS, termination = Termination.Resignation))

        val summary = ui.lastGameSummary
        assertNotNull(summary)
        assertEquals(gameId, summary.gameId)
        assertEquals(GameResult.BLACK_WINS, summary.result)
        assertEquals(Termination.Resignation, summary.termination)
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

    @Test
    fun `remote game names the signed-in user and the opponent by colour`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = startRemoteGame(playerColor = Color.BLACK)

        val info = assertNotNull(ui.lastGameStartedInfo)
        assertEquals("opp", info.whiteName)
        assertEquals("testuser", info.blackName)

        remote.emitGameEnded(RemoteGameEndedEvent(result = GameResult.BLACK_WINS, termination = Termination.Resignation))

        val summary = assertNotNull(ui.lastGameSummary)
        assertEquals("opp", summary.whiteName)
        assertEquals("testuser", summary.blackName)

        orchestrator.stop()
    }
}
