package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SetupFlowsTest {

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
        engines: List<FakeEngineModule>? = null,
        remotes: List<FakeRemoteModule>? = null,
    ) = Orchestrator(
        board = board,
        gameController = gc,
        ui = ui,
        engines = engines ?: if (withEngine) listOf(engine) else emptyList(),
        remotes = remotes ?: if (withRemote) listOf(remote) else emptyList(),
    )

    private fun CoroutineScope.launchOrchestrator(orchestrator: Orchestrator) {
        launch { orchestrator.start() }
    }

    // -----------------------------------------------------------------------
    // Local game setup
    // -----------------------------------------------------------------------

    @Test
    fun `local game setup transitions to AwaitingPlayerMove`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(Color.WHITE, (orchestrator.state as OrchestratorState.AwaitingPlayerMove).color)
        assertNotNull(orchestrator.session)
        assertEquals(GameMode.LOCAL, orchestrator.session!!.mode)

        orchestrator.stop()
    }

    @Test
    fun `user cancelling game mode selection stays Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `user cancelling configureLocalGame stays Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        ui.configureLocalGameResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `board capabilities fail during local setup re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        board.failWith = BoardError.Disconnected()
        // First call succeeds (triggers error), second call cancelled by user
        ui.configureLocalGameResponses = mutableListOf(
            Result.Success(LocalGameConfig("White", "Black", null)),
        )
        ui.configureLocalGameResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertEquals("DISCONNECTED", assertIs<UIMessage.BoardCapabilitiesUnavailable>(ui.lastConfigureLocalError).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `game creation fails during local setup re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator()
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.LOCAL)
        gc.createFail = GameError.GameNotFound()
        ui.configureLocalGameResponses = mutableListOf(
            Result.Success(LocalGameConfig("White", "Black", null)),
        )
        ui.configureLocalGameResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertEquals("GAME_NOT_FOUND", assertIs<UIMessage.GameCreationFailed>(ui.lastConfigureLocalError).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Engine game setup
    // -----------------------------------------------------------------------

    @Test
    fun `engine game setup configures engine and transitions correctly`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = mapOf("Depth" to 10), timeControl = null)
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(GameMode.ENGINE, orchestrator.session!!.mode)
        assertEquals(Color.WHITE, orchestrator.session!!.playerColor)
        assertTrue(engine.newGameCalled)
        assertEquals(mapOf("Depth" to 10), engine.lastConfigureSettings)

        orchestrator.stop()
    }

    @Test
    fun `engine game setup with engine playing white triggers engine move`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.BLACK, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
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
    }

    @Test
    fun `user cancelling configureEngineGame stays Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `board capabilities fail during engine setup re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponses = mutableListOf(
            Result.Success(EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)),
        )
        ui.configureEngineGameResponse = Result.Failure(UIError.Cancelled())
        board.failWith = BoardError.Disconnected()
        ui.emitNewGameRequested()

        assertEquals("DISCONNECTED", assertIs<UIMessage.BoardCapabilitiesUnavailable>(ui.lastConfigureEngineParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `game creation fails during engine setup re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponses = mutableListOf(
            Result.Success(EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)),
        )
        ui.configureEngineGameResponse = Result.Failure(UIError.Cancelled())
        gc.createFail = GameError.GameNotFound()
        ui.emitNewGameRequested()

        assertEquals("GAME_NOT_FOUND", assertIs<UIMessage.GameCreationFailed>(ui.lastConfigureEngineParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `engine capabilities fail shows error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        engine.capabilitiesFail = EngineError.EngineCrashed()
        ui.emitNewGameRequested()

        assertEquals(UIMessage.EngineCapabilitiesUnavailable, ui.lastError)
        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `engine configure fails re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponses = mutableListOf(
            Result.Success(EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)),
        )
        ui.configureEngineGameResponse = Result.Failure(UIError.Cancelled())
        engine.configureFail = EngineError.NotConfigured()
        ui.emitNewGameRequested()

        assertEquals("NOT_CONFIGURED", assertIs<UIMessage.EngineConfigurationFailed>(ui.lastConfigureEngineParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Remote game setup
    // -----------------------------------------------------------------------

    @Test
    fun `remote game setup transitions to SeekingGame`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponse = Result.Success(
            RemoteGameConfig(platformSettings = mapOf("token" to "abc"), seekSettings = mapOf("rated" to true))
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)
        assertEquals(mapOf("token" to "abc"), remote.lastConfigureSettings)
        assertEquals(mapOf("rated" to true), remote.lastSeekSettings)

        orchestrator.stop()
    }

    @Test
    fun `remote game started transitions to AwaitingPlayerMove when player is white`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

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
    }

    @Test
    fun `remote game started transitions to AwaitingOpponentMove when player is black`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

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
    }

    @Test
    fun `user cancelling configureRemoteGame stays Idle`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponse = Result.Failure(UIError.Cancelled())
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `board capabilities fail during remote setup re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponses = mutableListOf(
            Result.Success(RemoteGameConfig(platformSettings = emptyMap(), seekSettings = emptyMap())),
        )
        ui.configureRemoteGameResponse = Result.Failure(UIError.Cancelled())
        board.failWith = BoardError.Disconnected()
        ui.emitNewGameRequested()

        assertEquals("DISCONNECTED", assertIs<UIMessage.BoardCapabilitiesUnavailable>(ui.lastConfigureRemoteParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `remote capabilities fail shows error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        remote.capabilitiesFail = RemoteError.NotConnected()
        ui.emitNewGameRequested()

        assertEquals(UIMessage.RemoteCapabilitiesUnavailable, ui.lastError)
        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    @Test
    fun `remote configure fails re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponses = mutableListOf(
            Result.Success(RemoteGameConfig(platformSettings = emptyMap(), seekSettings = emptyMap())),
        )
        ui.configureRemoteGameResponse = Result.Failure(UIError.Cancelled())
        remote.configureFail = RemoteError.AuthFailed()
        ui.emitNewGameRequested()

        assertEquals("AUTH_FAILED", assertIs<UIMessage.RemoteConfigurationFailed>(ui.lastConfigureRemoteParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `seek game fails re-prompts with error`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.configureRemoteGameResponses = mutableListOf(
            Result.Success(RemoteGameConfig(platformSettings = emptyMap(), seekSettings = emptyMap())),
        )
        ui.configureRemoteGameResponse = Result.Failure(UIError.Cancelled())
        remote.seekFail = RemoteError.InvalidParams("time")
        ui.emitNewGameRequested()

        assertEquals("INVALID_PARAMS", assertIs<UIMessage.SeekFailed>(ui.lastConfigureRemoteParams?.errorMessage).error.code)
        assertIs<OrchestratorState.Idle>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `remote game creation fails disconnects and ends game`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)

        // Make game creation fail when remote game starts
        gc.createFail = GameError.GameNotFound()

        remote.emitGameStarted(GameStartedEvent(
            gameId = "r1", color = Color.WHITE,
            opponent = "opp", fen = startFen, timeControl = null,
        ))

        assertEquals("GAME_NOT_FOUND", assertIs<UIMessage.GameCreationFailed>(ui.lastError).error.code)
        assertTrue(remote.disconnectCalled)
        assertIs<OrchestratorState.Idle>(orchestrator.state)
        assertNull(orchestrator.session)

        orchestrator.stop()
    }

    // -----------------------------------------------------------------------
    // Multi-engine / multi-remote selection
    // -----------------------------------------------------------------------

    @Test
    fun `single engine auto-selected without UI prompt`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withEngine = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
        )
        // selectEngineResponse should not be called for single engine
        ui.selectEngineResponse = Result.Failure(UIError.Cancelled())

        ui.emitNewGameRequested()

        // Should succeed without prompting engine selection
        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(GameMode.ENGINE, orchestrator.session!!.mode)

        orchestrator.stop()
    }

    @Test
    fun `multiple engines prompt UI selection`() = runTest(UnconfinedTestDispatcher()) {
        val engine2 = FakeEngineModule()
        engine2.capabilities = EngineCapabilities(name = "Engine2", author = "Test2", parameters = emptyList())
        val orchestrator = createOrchestrator(engines = listOf(engine, engine2))
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.selectEngineResponse = Result.Success("0")
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertEquals(GameMode.ENGINE, orchestrator.session!!.mode)

        orchestrator.stop()
    }

    @Test
    fun `engines sharing a name are still told apart`() = runTest(UnconfinedTestDispatcher()) {
        // Two builds of the same engine, or the same engine reached twice. Selecting by name
        // always picked the first, whichever the player chose.
        val engine2 = FakeEngineModule()
        engine2.capabilities = engine.capabilities
        val orchestrator = createOrchestrator(engines = listOf(engine, engine2))
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
        ui.selectEngineResponse = Result.Success("1")
        ui.configureEngineGameResponse = Result.Success(
            EngineGameConfig(playerColor = Color.WHITE, playerName = "Player", engineSettings = emptyMap(), timeControl = null)
        )
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.AwaitingPlayerMove>(orchestrator.state)
        assertTrue(engine2.newGameCalled, "the second engine was the one chosen")
        assertFalse(engine.newGameCalled)

        orchestrator.stop()
    }

    @Test
    fun `a selection that matches no engine is reported rather than dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            // This used to resolve to index -1 and abandon the setup with nothing on screen.
            val engine2 = FakeEngineModule()
            engine2.capabilities = EngineCapabilities("Engine2", "Test2", emptyList())
            val orchestrator = createOrchestrator(engines = listOf(engine, engine2))
            launchOrchestrator(orchestrator)

            ui.selectGameModeResponse = Result.Success(GameMode.ENGINE)
            ui.selectEngineResponse = Result.Success("nonsense")
            ui.emitNewGameRequested()

            assertIs<OrchestratorState.Idle>(orchestrator.state)
            assertIs<UIMessage.UnexpectedFailure>(ui.lastError)

            orchestrator.stop()
        }

    @Test
    fun `single remote auto-selected without UI prompt`() = runTest(UnconfinedTestDispatcher()) {
        val orchestrator = createOrchestrator(withRemote = true)
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        // selectRemotePlatformResponse should not be called for single remote
        ui.selectRemotePlatformResponse = Result.Failure(UIError.Cancelled())

        ui.emitNewGameRequested()

        // Should succeed without prompting platform selection
        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)

        orchestrator.stop()
    }

    @Test
    fun `multiple remotes prompt UI selection`() = runTest(UnconfinedTestDispatcher()) {
        val remote2 = FakeRemoteModule()
        remote2.capabilities = RemoteCapabilities(
            platform = "Chess.com", features = emptyList(),
            parameters = emptyList(), seekParameters = emptyList(),
        )
        val orchestrator = createOrchestrator(remotes = listOf(remote, remote2))
        launchOrchestrator(orchestrator)

        ui.selectGameModeResponse = Result.Success(GameMode.REMOTE)
        ui.selectRemotePlatformResponse = Result.Success("0")
        ui.emitNewGameRequested()

        assertIs<OrchestratorState.SeekingGame>(orchestrator.state)

        orchestrator.stop()
    }
}
