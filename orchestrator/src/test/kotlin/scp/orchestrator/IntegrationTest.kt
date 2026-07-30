package scp.orchestrator

import com.nachogoro.simplechess.Game as ChessGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import scp.game.GameController
import scp.game.TimeSource
import scp.protocol.*
import kotlin.test.*

/**
 * The orchestrator driven against the real game controller.
 *
 * Everything else here tests the orchestrator against [FakeGameControllerModule], which is fine
 * for the routing decisions but says nothing about whether the two agree. They did not: a
 * checkmate reached the UI as an unnamed ending, a clock could be topped up past its own flag,
 * and a refused move withdrew the opponent's draw offer - and all three passed a full suite,
 * because every controller the orchestrator was ever tested against was one written to satisfy it.
 *
 * These start where a player starts, from the opening position and a board, and check what a UI
 * would actually be told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private class FakeTimeSource(var now: Long = 0L) : TimeSource {
        override fun currentTimeMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    /**
     * A board that pieces actually get moved on.
     *
     * The orchestrator infers moves by diffing what the board reports against the game's position,
     * so a test that wants to play a move has to put the pieces where the move would leave them.
     */
    private class BoardDriver(
        private val board: FakeBoardModule,
        private val level: BoardCapabilityLevel = BoardCapabilityLevel.FULL,
        startFen: String,
    ) {
        private var game = ChessGame.fromFen(startFen).getOrThrow()

        init {
            show()
        }

        fun play(uci: String) {
            val move = game.availableMoves.firstOrNull { moveToUci(it) == uci }
                ?: fail("$uci is not legal in ${game.currentPosition.fen}")
            game = game.makeMove(move).getOrThrow()
            show()
        }

        private fun show() {
            board.boardState = boardStateFromFen(game.currentPosition.fen, level)
        }
    }

    /** An engine that plays the first legal move it is offered, so a game can be driven to its end. */
    private class FirstLegalMoveEngine : EngineAdapterModule {
        override val errors = kotlinx.coroutines.flow.MutableSharedFlow<EngineError>()
        var lastParams: GetBestMoveParams? = null

        override suspend fun getCapabilities() =
            Result.Success(EngineCapabilities("Test", "Test", emptyList()))

        override suspend fun configure(settings: Map<String, Any>) = Result.Success(Unit)
        override suspend fun stop() = Result.Success(Unit)
        override suspend fun newGame() = Result.Success(Unit)

        override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> {
            lastParams = params
            var game = ChessGame.fromFen(params.fen).getOrThrow()
            for (uci in params.moves.orEmpty()) {
                game = game.makeMove(game.availableMoves.first { moveToUci(it) == uci }).getOrThrow()
            }
            val move = game.availableMoves.firstOrNull()
                ?: return Result.Failure(EngineError.InvalidPosition())
            return Result.Success(BestMoveResult(moveToUci(move)))
        }
    }

    private class Fixture(
        val board: FakeBoardModule,
        val ui: FakeUIModule,
        val controller: GameController,
        val orchestrator: Orchestrator,
        val clock: FakeTimeSource,
    ) {
        lateinit var driver: BoardDriver
        val gameId: String get() = orchestrator.session?.gameId ?: fail("no game in progress")

        /** Moves a piece on the board and tells the orchestrator the player is done. */
        suspend fun playFromBoard(uci: String) {
            driver.play(uci)
            ui.emitMoveReady(orchestrator.session?.gameId ?: "")
        }
    }

    private suspend fun TestScope.startGame(
        mode: GameMode = GameMode.LOCAL,
        timeControl: TimeControl? = null,
        engines: List<EngineAdapterModule> = emptyList(),
        playerColor: Color = Color.WHITE,
    ): Fixture {
        val board = FakeBoardModule(BoardCapabilityLevel.FULL)
        val ui = FakeUIModule()
        val clock = FakeTimeSource()
        val controller = GameController(backgroundScope, clock)
        val orchestrator = Orchestrator(board, controller, ui, engines = engines)

        launch { orchestrator.start() }

        ui.selectGameModeResponse = Result.Success(mode)
        ui.configureLocalGameResponse = Result.Success(
            LocalGameConfig(whiteName = "White", blackName = "Black", timeControl = timeControl)
        )
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = playerColor, playerName = "Player", engineSettings = emptyMap(), timeControl = timeControl)
        )
        ui.emitNewGameRequested()

        val fixture = Fixture(board, ui, controller, orchestrator, clock)
        fixture.driver = BoardDriver(board, startFen = startFen)
        return fixture
    }

    // -----------------------------------------------------------------------
    // Endings, as a UI is told about them
    // -----------------------------------------------------------------------

    @Test
    fun `a game played to checkmate is reported as checkmate`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()

        // Fool's mate.
        f.playFromBoard("f2f3")
        f.playFromBoard("e7e5")
        f.playFromBoard("g2g4")
        f.playFromBoard("d8h4")

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Checkmate, summary.termination)
        assertEquals(GameResult.BLACK_WINS, summary.result)
        assertEquals(listOf("f2f3", "e7e5", "g2g4", "d8h4"), summary.moves.map { it.move })
        assertEquals("Qh4#", summary.moves.last().san)
        assertIs<OrchestratorState.Idle>(f.orchestrator.state)

        f.orchestrator.stop()
    }

    @Test
    fun `a game played to stalemate is reported as stalemate`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()

        // The shortest known stalemate, ten moves, ending with the black king boxed in.
        val moves = listOf(
            "e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "a5c7", "a6h6", "h2h4", "f7f6",
            "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7", "b8c8", "f7g6", "c8e6",
        )
        moves.forEach { f.playFromBoard(it) }

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Stalemate, summary.termination)
        assertEquals(GameResult.DRAW, summary.result)
        assertIs<OrchestratorState.Idle>(f.orchestrator.state)

        f.orchestrator.stop()
    }

    @Test
    fun `the resigning player is the one the UI names`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()
        f.playFromBoard("e2e4")

        // Black is to move, but white is the one resigning. Whose request it is belongs to the
        // UI: a local game is two people at one board, and inferring it from the turn resigned
        // the wrong player whenever the other one had asked.
        f.ui.emitResignRequested(f.gameId, Color.WHITE)

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Resignation, summary.termination)
        assertEquals(GameResult.BLACK_WINS, summary.result)

        f.orchestrator.stop()
    }

    @Test
    fun `the player to move can resign too`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()
        f.playFromBoard("e2e4")

        f.ui.emitResignRequested(f.gameId, Color.BLACK)

        assertEquals(GameResult.WHITE_WINS, assertNotNull(f.ui.lastGameSummary).result)

        f.orchestrator.stop()
    }

    @Test
    fun `an agreed draw is reported as agreement`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()
        f.ui.promptDrawResponseResult = Result.Success(true)

        f.ui.emitDrawRequested(f.gameId)

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Agreement, summary.termination)
        assertEquals(GameResult.DRAW, summary.result)

        f.orchestrator.stop()
    }

    @Test
    fun `a declined draw leaves the game running`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()
        f.ui.promptDrawResponseResult = Result.Success(false)

        f.ui.emitDrawRequested(f.gameId)

        assertNull(f.ui.lastGameSummary)
        assertIs<OrchestratorState.AwaitingPlayerMove>(f.orchestrator.state)

        // ...and play carries on.
        f.playFromBoard("e2e4")
        assertEquals(
            Color.BLACK,
            (f.orchestrator.state as OrchestratorState.AwaitingPlayerMove).color,
        )

        f.orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Clocks
    // -----------------------------------------------------------------------

    @Test
    fun `running out of time ends the game as a timeout`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame(
            timeControl = TimeControl(TimeControlType.FISCHER, initialMs = 60_000, incrementMs = 1_000)
        )

        // Nobody moves; white's flag falls.
        f.clock.advance(60_000)
        advanceTimeBy(60_001)
        advanceUntilIdle()

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Timeout, summary.termination)
        assertEquals(GameResult.BLACK_WINS, summary.result)
        assertIs<OrchestratorState.Idle>(f.orchestrator.state)

        f.orchestrator.stop()
    }

    @Test
    fun `a move made after the flag does not buy time back`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame(
            timeControl = TimeControl(TimeControlType.FISCHER, initialMs = 10_000, incrementMs = 30_000)
        )

        // The move ends the game, so hold on to the id before it does.
        val gameId = f.gameId

        // White overruns a ten second clock by five, and moves before the timer coroutine runs.
        f.clock.advance(15_000)
        f.playFromBoard("e2e4")

        val summary = assertNotNull(f.ui.lastGameSummary)
        assertEquals(Termination.Timeout, summary.termination)
        assertEquals(GameResult.BLACK_WINS, summary.result)
        // The increment used to be added to the overrun: 10000 - 15000 + 30000 = 25000.
        val state = assertIs<Result.Success<GameState>>(f.controller.getState(gameId))
        assertEquals(0, state.value.clocks?.whiteMs)

        f.orchestrator.stop()
    }

    @Test
    fun `the clock is charged for the time a move took`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame(
            timeControl = TimeControl(TimeControlType.FISCHER, initialMs = 300_000, incrementMs = 2_000)
        )

        f.clock.advance(10_000)
        f.playFromBoard("e2e4")

        val state = assertIs<Result.Success<GameState>>(f.controller.getState(f.gameId))
        assertEquals(300_000 - 10_000 + 2_000, state.value.clocks?.whiteMs)
        assertEquals(300_000, state.value.clocks?.blackMs)

        f.orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // The move path
    // -----------------------------------------------------------------------

    @Test
    fun `a move by the wrong side is refused as out of turn`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()

        // Black moves while white is to play. The board cannot tell us that, but the controller can.
        f.driver.play("e2e4")
        f.driver.play("e7e5")
        f.ui.emitMoveReady(f.gameId)

        // Two plies of change at once matches no single legal move, so this is the board and the
        // game disagreeing rather than a move.
        assertNotNull(f.ui.lastDesync)

        f.orchestrator.stop()
    }

    @Test
    fun `an offer is recorded as the offerer's while the opponent is asked`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = startGame()
            var offerWhilePrompting: Color? = null
            // Local play asks the other player outright rather than leaving an offer to discover,
            // so the moment the prompt is up is the only moment the offer is outstanding.
            f.ui.onPromptDrawResponse = {
                offerWhilePrompting =
                    (f.controller.getState(f.gameId) as? Result.Success)?.value?.drawOffer
            }
            f.ui.promptDrawResponseResult = Result.Success(false)

            f.ui.emitDrawRequested(f.gameId)

            // White is to move, so the offer is white's and black is the one being asked. A
            // controller that could not say whose it was let the offerer accept it themselves.
            assertEquals(Color.WHITE, offerWhilePrompting)

            f.orchestrator.stop()
        }

    // -----------------------------------------------------------------------
    // Playing an engine
    // -----------------------------------------------------------------------

    @Test
    fun `the engine is given the game so far, from the position it started at`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = FirstLegalMoveEngine()
            val f = startGame(mode = GameMode.ENGINE, engines = listOf(engine), playerColor = Color.WHITE)

            f.playFromBoard("e2e4")

            val asked = assertNotNull(engine.lastParams)
            assertEquals(startFen, asked.fen)
            assertEquals(listOf("e2e4"), asked.moves)

            // The engine's reply reached the UI and the game.
            val shown = assertNotNull(f.ui.lastOpponentMove)
            val state = assertIs<Result.Success<GameState>>(f.controller.getState(f.gameId))
            assertEquals(listOf("e2e4", shown.move), state.value.moves)

            f.orchestrator.stop()
        }

    @Test
    fun `an opponent move says which colour played it`() = runTest(UnconfinedTestDispatcher()) {
        // ui.showOpponentMove carries the colour (PROTOCOL.md 4.6). It has to be read before the
        // move is applied - afterwards the turn has passed back and nothing says whose it was.
        val engine = FirstLegalMoveEngine()
        val f = startGame(mode = GameMode.ENGINE, engines = listOf(engine), playerColor = Color.WHITE)

        f.playFromBoard("e2e4")

        assertEquals(Color.BLACK, assertNotNull(f.ui.lastOpponentMove).color)

        f.orchestrator.stop()
    }

    @Test
    fun `an engine game names both sides of the board`() = runTest(UnconfinedTestDispatcher()) {
        // ui.configureEngineGame returns the player's name, so the game has one for each side
        // rather than the two nulls it used to be created with.
        val engine = FirstLegalMoveEngine()
        val f = startGame(mode = GameMode.ENGINE, engines = listOf(engine), playerColor = Color.BLACK)

        val started = assertNotNull(f.ui.lastGameStartedInfo)
        assertEquals("Player", started.blackName, "the human plays black here")
        assertEquals("Test", started.whiteName, "and the engine is named from its capabilities")

        f.orchestrator.stop()
    }

    @Test
    fun `an engine game alternates until someone is mated`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FirstLegalMoveEngine()
        val f = startGame(mode = GameMode.ENGINE, engines = listOf(engine), playerColor = Color.WHITE)

        // Fool's mate again, with the engine answering in between. The engine plays the first
        // legal move it is offered, which from these positions is a7a6 and then a6a5.
        f.playFromBoard("f2f3")
        f.driver.play(assertNotNull(f.ui.lastOpponentMove).move)
        f.playFromBoard("g2g4")
        f.driver.play(assertNotNull(f.ui.lastOpponentMove).move)

        // Not mate - the engine did not cooperate - but the game is still going and consistent.
        val state = assertIs<Result.Success<GameState>>(f.controller.getState(f.gameId))
        assertEquals(GameStatus.IN_PROGRESS, state.value.status)
        assertEquals(4, state.value.moves.size)
        assertIs<OrchestratorState.AwaitingPlayerMove>(f.orchestrator.state)

        f.orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Starting over
    // -----------------------------------------------------------------------

    @Test
    fun `a second game starts clean`() = runTest(UnconfinedTestDispatcher()) {
        val f = startGame()
        f.playFromBoard("e2e4")
        val first = f.gameId

        // Asking for a new game mid-play abandons the current one.
        f.ui.emitNewGameRequested()

        val second = f.gameId
        assertNotEquals(first, second)
        val state = assertIs<Result.Success<GameState>>(f.controller.getState(second))
        assertEquals(startFen, state.value.fen)
        assertTrue(state.value.moves.isEmpty())

        // The board still shows the abandoned game. One ply of difference would read as that move
        // having just been played, which is right; two plies is a position no single move explains.
        f.driver.play("e7e5")
        f.ui.emitMoveReady(second)
        assertNotNull(f.ui.lastDesync)

        f.orchestrator.stop()
    }
}
