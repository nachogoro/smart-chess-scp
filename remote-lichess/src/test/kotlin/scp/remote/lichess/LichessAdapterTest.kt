package scp.remote.lichess

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LichessAdapterTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- Helpers --

    private fun gameFull(
        id: String = "game1",
        whiteName: String = "TestUser",
        whiteId: String = "testuser",
        blackName: String = "Opponent",
        blackId: String = "opponent",
        initialFen: String = "startpos",
        moves: String = "",
        status: String = "started",
        wtime: Long = 900000,
        btime: Long = 900000,
        clockInitial: Long = 900000,
        clockIncrement: Long = 10000,
    ): String = json.encodeToString(
        LichessGameFull(
            id = id,
            white = LichessPlayer(id = whiteId, name = whiteName, rating = 1500),
            black = LichessPlayer(id = blackId, name = blackName, rating = 1400),
            initialFen = initialFen,
            state = LichessGameState(
                moves = moves,
                wtime = wtime,
                btime = btime,
                status = status,
            ),
            clock = LichessClock(initial = clockInitial, increment = clockIncrement),
        )
    )

    private fun gameState(
        moves: String = "",
        wtime: Long = 900000,
        btime: Long = 900000,
        status: String = "started",
        winner: String? = null,
        wdraw: Boolean = false,
        bdraw: Boolean = false,
    ): String = json.encodeToString(
        LichessGameState(
            moves = moves,
            wtime = wtime,
            btime = btime,
            status = status,
            winner = winner,
            wdraw = wdraw,
            bdraw = bdraw,
        )
    )

    private fun gameStartEvent(gameId: String = "game1"): String = json.encodeToString(
        LichessStreamEvent(
            type = "gameStart",
            game = LichessGameEventInfo(
                fullId = "${gameId}full",
                gameId = gameId,
            ),
        )
    )

    private suspend fun configureAdapter(
        api: FakeLichessApi,
        adapter: LichessAdapter,
    ) {
        val result = adapter.configure(mapOf("api_token" to "test-token"))
        assertIs<Result.Success<ConfigureResult>>(result)
    }

    /** Configures and starts seeking, so the adapter will process gameStart events. */
    private suspend fun configureAndSeek(
        api: FakeLichessApi,
        adapter: LichessAdapter,
    ) {
        configureAdapter(api, adapter)
        adapter.seekGame(mapOf("opponent_type" to "Engine", "ai_level" to 1, "time" to 10, "increment" to 0))
    }

    // -- GetCapabilities --

    @Test
    fun `getCapabilities returns lichess platform info`() = runTest {
        val adapter = LichessAdapter(backgroundScope)
        val result = adapter.getCapabilities()
        assertIs<Result.Success<RemoteCapabilities>>(result)
        assertEquals("Lichess", result.value.platform)
        assertTrue(result.value.parameters.any { it.name == "api_token" })
        assertTrue(result.value.seekParameters.any { it.name == "opponent_type" })
        assertTrue(result.value.seekParameters.any { it.name == "time" })
        assertTrue(result.value.seekParameters.any { it.name == "increment" })
        assertTrue(result.value.seekParameters.any { it.name == "rated" })
        assertTrue(result.value.seekParameters.any { it.name == "color" })
    }

    @Test
    fun `getCapabilities opponent_name is visible only for friend`() = runTest {
        val adapter = LichessAdapter(backgroundScope)
        val caps = (adapter.getCapabilities() as Result.Success).value
        val opponentName = caps.seekParameters.first { it.name == "opponent_name" }
        assertEquals("opponent_type", opponentName.visibleWhen?.field)
        assertEquals("Friend", opponentName.visibleWhen?.equals)
        assertTrue(opponentName.required)
    }

    @Test
    fun `getCapabilities ai_level is visible only for ai`() = runTest {
        val adapter = LichessAdapter(backgroundScope)
        val caps = (adapter.getCapabilities() as Result.Success).value
        val aiLevel = caps.seekParameters.first { it.name == "ai_level" }
        assertEquals("opponent_type", aiLevel.visibleWhen?.field)
        assertEquals("Engine", aiLevel.visibleWhen?.equals)
    }

    @Test
    fun `getCapabilities rated is visible for random and friend`() = runTest {
        val adapter = LichessAdapter(backgroundScope)
        val caps = (adapter.getCapabilities() as Result.Success).value
        val rated = caps.seekParameters.first { it.name == "rated" }
        assertEquals("opponent_type", rated.visibleWhen?.field)
        assertEquals(listOf("Random opponent", "Friend"), rated.visibleWhen?.oneOf)
    }

    @Test
    fun `seek time minimum is 10 minutes`() = runTest {
        val adapter = LichessAdapter(backgroundScope)
        val caps = adapter.getCapabilities()
        assertIs<Result.Success<RemoteCapabilities>>(caps)
        val timeParam = caps.value.seekParameters.first { it.name == "time" }
        assertEquals(10, timeParam.min)
    }

    // -- Configure --

    @Test
    fun `configure with valid token succeeds`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)

        val result = adapter.configure(mapOf("api_token" to "valid-token"))
        assertIs<Result.Success<ConfigureResult>>(result)
        assertEquals("TestUser", result.value.user?.username)
        assertEquals(1500, result.value.user?.rating)

        adapter.disconnect()
    }

    @Test
    fun `configure with invalid token fails`() = runTest {
        val api = FakeLichessApi()
        api.authShouldFail = true
        val adapter = LichessAdapter(backgroundScope, api)

        val result = adapter.configure(mapOf("api_token" to "bad-token"))
        assertIs<Result.Failure<RemoteError>>(result)
        assertIs<RemoteError.AuthFailed>(result.error)
    }

    @Test
    fun `configure without token fails`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(backgroundScope, api)

        val result = adapter.configure(emptyMap())
        assertIs<Result.Failure<RemoteError>>(result)
        assertIs<RemoteError.InvalidParams>(result.error)
    }

    // -- SeekGame --

    @Test
    fun `seekGame rejects blitz time control`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)

        val result = adapter.seekGame(mapOf("time" to 5))
        assertIs<Result.Failure<RemoteError>>(result)
        val error = assertIs<RemoteError.InvalidParams>(result.error)
        // The field, not a sentence about it: the UI points at what the player filled in, and
        // words the constraint from the descriptor's own minimum (PROTOCOL.md 2.6, 7.2).
        assertEquals("time", error.parameter)

        adapter.disconnect()
    }

    @Test
    fun `seekGame with random opponent creates open seek`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)
        advanceUntilIdle()

        adapter.seekGame(mapOf("opponent_type" to "Random opponent", "time" to 15, "increment" to 10, "rated" to true, "color" to "white"))
        advanceUntilIdle()

        val params = api.lastSeekParams
        assertNotNull(params)
        assertEquals(15, params.time)
        assertEquals(10, params.increment)
        assertTrue(params.rated)
        assertEquals("white", params.color)

        adapter.disconnect()
    }

    @Test
    fun `seekGame defaults to random when opponent_type omitted`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)
        advanceUntilIdle()

        adapter.seekGame(mapOf("time" to 15, "increment" to 10))
        advanceUntilIdle()

        assertNotNull(api.lastSeekParams)
        assertNull(api.lastChallengeParams)
        assertNull(api.lastAiChallengeParams)

        adapter.disconnect()
    }

    @Test
    fun `seekGame with friend creates challenge`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)
        advanceUntilIdle()

        adapter.seekGame(mapOf("opponent_type" to "Friend", "opponent_name" to "friend123", "time" to 15, "increment" to 10, "rated" to false))
        advanceUntilIdle()

        assertNull(api.lastSeekParams, "Should not create a seek when opponent_type is friend")
        val challenge = api.lastChallengeParams
        assertNotNull(challenge)
        assertEquals("friend123", challenge.username)
        assertEquals(15, challenge.time)
        assertEquals(10, challenge.increment)
        assertFalse(challenge.rated)

        adapter.disconnect()
    }

    @Test
    fun `seekGame with ai challenges stockfish`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)
        advanceUntilIdle()

        val result = adapter.seekGame(mapOf("opponent_type" to "Engine", "ai_level" to 6, "time" to 15, "increment" to 10, "color" to "black"))
        assertIs<Result.Success<Unit>>(result)

        assertNull(api.lastSeekParams, "Should not create a seek for AI")
        assertNull(api.lastChallengeParams, "Should not create a user challenge for AI")
        val aiParams = api.lastAiChallengeParams
        assertNotNull(aiParams)
        assertEquals(6, aiParams.level)
        assertEquals(15, aiParams.time)
        assertEquals(10, aiParams.increment)
        assertEquals("black", aiParams.color)

        adapter.disconnect()
    }

    @Test
    fun `challenge also rejects blitz time control`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)

        val result = adapter.seekGame(mapOf("opponent_type" to "Friend", "opponent_name" to "friend123", "time" to 3))
        assertIs<Result.Failure<RemoteError>>(result)
        assertIs<RemoteError.InvalidParams>(result.error)

        adapter.disconnect()
    }

    @Test
    fun `ai challenge rejects blitz time control`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)

        val result = adapter.seekGame(mapOf("opponent_type" to "Engine", "ai_level" to 4, "time" to 3))
        assertIs<Result.Failure<RemoteError>>(result)
        assertIs<RemoteError.InvalidParams>(result.error)

        adapter.disconnect()
    }

    @Test
    fun `seekGame without configure fails`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(backgroundScope, api)

        val result = adapter.seekGame(mapOf("time" to 15))
        assertIs<Result.Failure<RemoteError>>(result)
        assertIs<RemoteError.NotConnected>(result.error)
    }

    // -- Game stream: gameStarted --

    @Test
    fun `game start emits gameStarted event`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val events = mutableListOf<GameStartedEvent>()
        val collectJob = launch { adapter.gameStarted.collect { events.add(it) } }

        api.eventStreamChannel.send(gameStartEvent("game1"))
        advanceUntilIdle()

        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        assertTrue(events.isNotEmpty())
        assertEquals("game1", events.first().gameId)
        assertEquals(Color.WHITE, events.first().color)
        assertEquals("Opponent", events.first().opponent)
        assertNotNull(events.first().timeControl)
        assertEquals(900000, events.first().timeControl!!.initialMs)
        assertEquals(10000, events.first().timeControl!!.incrementMs)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `game start as black detects color correctly`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val events = mutableListOf<GameStartedEvent>()
        val collectJob = launch { adapter.gameStarted.collect { events.add(it) } }

        api.eventStreamChannel.send(gameStartEvent("game1"))
        advanceUntilIdle()

        api.gameStreamChannel.send(
            gameFull(whiteName = "Opponent", whiteId = "opponent", blackName = "TestUser", blackId = "testuser")
        )
        advanceUntilIdle()

        assertEquals(Color.BLACK, events.first().color)
        assertEquals("Opponent", events.first().opponent)

        collectJob.cancel()
        adapter.disconnect()
    }

    // -- Opponent moves --

    @Test
    fun `opponent move emits opponentMove event`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val moves = mutableListOf<OpponentMoveEvent>()
        val collectJob = launch { adapter.opponentMove.collect { moves.add(it) } }

        // Start game as white
        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // We (white) send a move
        adapter.sendMove("e2e4")
        advanceUntilIdle()

        // Opponent (black) plays e7e5
        api.gameStreamChannel.send(gameState(moves = "e2e4 e7e5", btime = 895000))
        advanceUntilIdle()

        assertTrue(moves.isNotEmpty())
        assertEquals("e7e5", moves.last().move)
        assertEquals(895000, moves.last().clocks?.blackMs)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `several moves arriving at once are all delivered, in order`() = runTest {
        // What a reconnection looks like: one gameState carrying everything played while away.
        // Emitting only the last of them left the game controller a move list with holes in it.
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val moves = mutableListOf<OpponentMoveEvent>()
        val collectJob = launch { adapter.opponentMove.collect { moves.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // We are white; four moves land in one update, two of them the opponent's.
        api.gameStreamChannel.send(gameState(moves = "e2e4 e7e5 g1f3 b8c6", btime = 880000))
        advanceUntilIdle()

        assertEquals(listOf("e7e5", "b8c6"), moves.map { it.move })
        // Only the latest move carries the clocks the update reported.
        assertNull(moves.first().clocks)
        assertEquals(880000, moves.last().clocks?.blackMs)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `move attribution follows the starting position, not the standard one`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val moves = mutableListOf<OpponentMoveEvent>()
        val collectJob = launch { adapter.opponentMove.collect { moves.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        // A position with BLACK to move, so the first move in the list is black's - and we are
        // white, which makes it the opponent's.
        api.gameStreamChannel.send(
            gameFull(initialFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        )
        advanceUntilIdle()

        api.gameStreamChannel.send(gameState(moves = "e7e5 g1f3"))
        advanceUntilIdle()

        assertEquals(listOf("e7e5"), moves.map { it.move }, "black moved first from this position")

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `own move does not emit opponentMove`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val moves = mutableListOf<OpponentMoveEvent>()
        val collectJob = launch { adapter.opponentMove.collect { moves.add(it) } }

        // Start game as white
        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // We (white) send a move — gameState arrives with our move
        adapter.sendMove("e2e4")
        api.gameStreamChannel.send(gameState(moves = "e2e4"))
        advanceUntilIdle()

        // Should not emit opponent move for our own move
        assertTrue(moves.isEmpty())

        collectJob.cancel()
        adapter.disconnect()
    }

    // -- Draw offers --

    @Test
    fun `opponent draw offer emits drawOffered`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val drawOffers = mutableListOf<Unit>()
        val collectJob = launch { adapter.drawOffered.collect { drawOffers.add(it) } }

        // Start game as white
        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // Black offers draw
        api.gameStreamChannel.send(gameState(moves = "e2e4 e7e5", bdraw = true))
        advanceUntilIdle()

        assertTrue(drawOffers.isNotEmpty())

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `own draw offer does not emit drawOffered`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val drawOffers = mutableListOf<Unit>()
        val collectJob = launch { adapter.drawOffered.collect { drawOffers.add(it) } }

        // Start game as white
        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // We (white) offered draw
        api.gameStreamChannel.send(gameState(moves = "e2e4", wdraw = true))
        advanceUntilIdle()

        assertTrue(drawOffers.isEmpty())

        collectJob.cancel()
        adapter.disconnect()
    }

    // -- Game end --

    @Test
    fun `checkmate emits gameEnded`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val endings = mutableListOf<RemoteGameEndedEvent>()
        val collectJob = launch { adapter.gameEnded.collect { endings.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        api.gameStreamChannel.send(gameState(status = "mate", winner = "white"))
        advanceUntilIdle()

        assertTrue(endings.isNotEmpty())
        assertEquals(GameResult.WHITE_WINS, endings.first().result)
        assertEquals(Termination.Checkmate, endings.first().termination)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `resignation emits gameEnded`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val endings = mutableListOf<RemoteGameEndedEvent>()
        val collectJob = launch { adapter.gameEnded.collect { endings.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        api.gameStreamChannel.send(gameState(status = "resign", winner = "black"))
        advanceUntilIdle()

        assertEquals(GameResult.BLACK_WINS, endings.first().result)
        assertEquals(Termination.Resignation, endings.first().termination)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `every terminal Lichess status maps to a termination`() {
        // The point of the Other cases: a status this protocol does not name must not be pushed
        // into the nearest code, and one Lichess adds tomorrow must still arrive intact rather
        // than being guessed at.
        val expected = mapOf(
            "mate" to Termination.Checkmate,
            "resign" to Termination.Resignation,
            "stalemate" to Termination.Stalemate,
            "timeout" to Termination.Timeout,
            "outoftime" to Termination.Timeout,
            "aborted" to Termination.Aborted,
            "noStart" to Termination.Aborted,
            "draw" to Termination.Drawn(DrawKind.Other(authorityCode = "draw")),
            "cheat" to Termination.Other(authorityCode = "cheat", text = "fair play violation"),
            "variantEnd" to Termination.Other(authorityCode = "variantEnd", text = "variant end"),
            "somethingLichessAddsLater" to Termination.Other(authorityCode = "somethingLichessAddsLater"),
        )

        for ((status, termination) in expected) {
            assertEquals(termination, mapLichessTermination(status), "for status '$status'")
        }
    }

    @Test
    fun `draw emits gameEnded with draw result`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val endings = mutableListOf<RemoteGameEndedEvent>()
        val collectJob = launch { adapter.gameEnded.collect { endings.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        api.gameStreamChannel.send(gameState(status = "draw"))
        advanceUntilIdle()

        assertEquals(GameResult.DRAW, endings.first().result)

        collectJob.cancel()
        adapter.disconnect()
    }

    @Test
    fun `timeout with no winner is draw`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        val endings = mutableListOf<RemoteGameEndedEvent>()
        val collectJob = launch { adapter.gameEnded.collect { endings.add(it) } }

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        // outoftime with no winner = insufficient material draw
        api.gameStreamChannel.send(gameState(status = "outoftime", winner = null))
        advanceUntilIdle()

        assertEquals(GameResult.DRAW, endings.first().result)

        collectJob.cancel()
        adapter.disconnect()
    }

    // -- Event flows do not replay --

    @Test
    fun `a subscriber arriving after a game ends hears nothing of it`() = runTest {
        // The adapter outlives a game; the orchestrator subscribes per game. A replay buffer
        // handed the next game's collectors the previous game's ending the moment they
        // subscribed, which finished the new game before it had begun.
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameState(moves = "e2e4", status = "resign", winner = "black"))
        advanceUntilIdle()

        val endings = mutableListOf<RemoteGameEndedEvent>()
        val starts = mutableListOf<GameStartedEvent>()
        val collectJob = launch { adapter.gameEnded.collect { endings.add(it) } }
        val startJob = launch { adapter.gameStarted.collect { starts.add(it) } }
        advanceUntilIdle()

        assertTrue(endings.isEmpty(), "the finished game's ending must not be replayed")
        assertTrue(starts.isEmpty(), "the finished game's start must not be replayed")

        collectJob.cancel()
        startJob.cancel()
        adapter.disconnect()
    }

    // -- SendMove / Resign / Draw --

    @Test
    fun `sendMove calls API`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        val result = adapter.sendMove("e2e4")
        assertIs<Result.Success<Unit>>(result)
        assertEquals("game1" to "e2e4", api.lastMoveSent)

        adapter.disconnect()
    }

    @Test
    fun `resign calls API`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAndSeek(api, adapter)
        advanceUntilIdle()

        api.eventStreamChannel.send(gameStartEvent())
        advanceUntilIdle()
        api.gameStreamChannel.send(gameFull())
        advanceUntilIdle()

        val result = adapter.resign()
        assertIs<Result.Success<Unit>>(result)
        assertEquals("game1", api.lastResignedGame)

        adapter.disconnect()
    }

    @Test
    fun `sendMove without game fails`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)

        val result = adapter.sendMove("e2e4")
        assertIs<Result.Failure<RemoteError>>(result)

        adapter.disconnect()
    }

    // -- Disconnect --

    @Test
    fun `disconnect cleans up state`() = runTest {
        val api = FakeLichessApi()
        val adapter = LichessAdapter(this, api)
        configureAdapter(api, adapter)

        val result = adapter.disconnect()
        assertIs<Result.Success<Unit>>(result)

        // After disconnect, operations should fail
        val seekResult = adapter.seekGame(mapOf("time" to 15))
        assertIs<Result.Failure<RemoteError>>(seekResult)
        assertIs<RemoteError.NotConnected>(seekResult.error)
    }
}
