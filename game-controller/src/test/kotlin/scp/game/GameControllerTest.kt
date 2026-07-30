package scp.game

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GameControllerTest {

    private class FakeTimeSource(var time: Long = 0L) : TimeSource {
        override fun currentTimeMs(): Long = time
        fun advance(ms: Long) { time += ms }
    }

    // -- Helpers --

    private fun scholarsMate() = listOf("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7")

    // -- Create --

    @Test
    fun `create returns a game id`() = runTest {
        val gc = GameController(backgroundScope)
        val result = gc.create(CreateGameParams())
        assertIs<Result.Success<CreateGameResult>>(result)
        assertTrue(result.value.gameId.isNotEmpty())
    }

    @Test
    fun `create with custom FEN`() = runTest {
        val gc = GameController(backgroundScope)
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val result = gc.create(CreateGameParams(fen = fen))
        assertIs<Result.Success<CreateGameResult>>(result)

        val state = gc.getState(result.value.gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(fen, state.value.fen)
        assertEquals(Color.BLACK, state.value.turn)
    }

    @Test
    fun `create with invalid FEN fails`() = runTest {
        val gc = GameController(backgroundScope)
        val result = gc.create(CreateGameParams(fen = "invalid"))
        assertIs<Result.Failure<GameError>>(result)
    }

    @Test
    fun `create with time control initializes clocks`() = runTest {
        val gc = GameController(backgroundScope)
        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 300000, incrementMs = 3000)
        val result = gc.create(CreateGameParams(timeControl = tc))
        assertIs<Result.Success<CreateGameResult>>(result)

        val state = gc.getState(result.value.gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(Clocks(300000, 300000), state.value.clocks)
    }

    // -- GetState --

    @Test
    fun `getState for nonexistent game returns GAME_NOT_FOUND`() = runTest {
        val gc = GameController(backgroundScope)
        val result = gc.getState("nonexistent")
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameNotFound>(result.error)
    }

    @Test
    fun `getState returns initial state`() = runTest {
        val gc = GameController(backgroundScope)
        val createResult = gc.create(CreateGameParams())
        assertIs<Result.Success<CreateGameResult>>(createResult)

        val state = gc.getState(createResult.value.gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.NOT_STARTED, state.value.status)
        assertEquals(Color.WHITE, state.value.turn)
        assertEquals(GameResult.IN_PROGRESS, state.value.result)
        assertTrue(state.value.moves.isEmpty())
    }

    // -- SubmitMove --

    @Test
    fun `submitMove with legal move succeeds`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals("e4", result.value.san)
        assertEquals(GameStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `submitMove with illegal move fails`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e5"))
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.IllegalMove>(result.error)
    }

    @Test
    fun `submitMove on nonexistent game fails`() = runTest {
        val gc = GameController(backgroundScope)
        val result = gc.submitMove(SubmitMoveParams(gameId = "nope", move = "e2e4"))
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameNotFound>(result.error)
    }

    @Test
    fun `submitMove flags en passant captures`() = runTest {
        val gc = GameController(backgroundScope)
        // Black has just played d7d5; White's pawn on e5 can take it in passing.
        val gameId = createGame(gc, fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e5d6"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals("exd6", result.value.san)
        assertTrue(result.value.isEnPassant)
    }

    @Test
    fun `submitMove does not flag ordinary pawn captures as en passant`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, fen = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2")

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e4d5"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals("exd5", result.value.san)
        assertFalse(result.value.isEnPassant)
    }

    @Test
    fun `submitMove detects checkmate`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        for (move in scholarsMate()) {
            val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = move))
            assertIs<Result.Success<SubmitMoveResult>>(result)
        }

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.WHITE_WINS, state.value.result)
    }

    @Test
    fun `submitMove on finished game fails`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        for (move in scholarsMate()) {
            gc.submitMove(SubmitMoveParams(gameId = gameId, move = move))
        }

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameOver>(result.error)
    }

    @Test
    fun `submitMove detects stalemate`() = runTest {
        val gc = GameController(backgroundScope)
        val fen = "k7/8/KQ6/8/8/8/8/8 w - - 0 1"
        val gameId = createGame(gc, fen = fen)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "b6c7"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals(GameStatus.FINISHED, result.value.status)
    }

    @Test
    fun `submitMove with promotion`() = runTest {
        val gc = GameController(backgroundScope)
        // White pawn on a7, black king on h8, white king on e1
        val fen = "7k/P7/8/8/8/8/8/4K3 w - - 0 1"
        val gameId = createGame(gc, fen = fen)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "a7a8q"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertTrue(result.value.fen.contains("Q"))
    }

    @Test
    fun `submitMove updates move history`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e7e5"))

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(listOf("e2e4", "e7e5"), state.value.moves)
    }

    // -- Resign --

    @Test
    fun `resign ends game`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val result = gc.resign(gameId, Color.WHITE)
        assertIs<Result.Success<Unit>>(result)

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.BLACK_WINS, state.value.result)
    }

    @Test
    fun `resign on finished game fails`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.resign(gameId, Color.WHITE)

        val result = gc.resign(gameId, Color.BLACK)
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameOver>(result.error)
    }

    // -- Draw offer/respond --

    @Test
    fun `draw offer and accept`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        gc.offerDraw(gameId, Color.WHITE)
        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.DRAW_OFFERED, state.value.status)

        gc.respondDraw(gameId, Color.BLACK, accept = true)
        val finalState = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(finalState)
        assertEquals(GameStatus.FINISHED, finalState.value.status)
        assertEquals(GameResult.DRAW, finalState.value.result)
    }

    @Test
    fun `draw offer and decline`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        gc.offerDraw(gameId, Color.WHITE)
        gc.respondDraw(gameId, Color.BLACK, accept = false)

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.IN_PROGRESS, state.value.status)
    }

    @Test
    fun `a player cannot answer their own draw offer`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.offerDraw(gameId, Color.WHITE)

        val result = gc.respondDraw(gameId, Color.WHITE, accept = true)

        assertIs<GameError.OwnDrawOffer>(assertIs<Result.Failure<GameError>>(result).error)
        val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
        assertEquals(GameStatus.DRAW_OFFERED, state.status, "the offer is still waiting for a reply")
        assertEquals(Color.WHITE, state.drawOffer)
    }

    @Test
    fun `respondDraw without pending offer fails`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        val result = gc.respondDraw(gameId, Color.BLACK, accept = true)
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.NoDrawOffer>(result.error)
    }

    @Test
    fun `draw offer cleared on move submission`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        gc.offerDraw(gameId, Color.WHITE)
        assertEquals(GameStatus.DRAW_OFFERED, getStatus(gc, gameId))

        // Black plays a move instead of responding to draw
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e7e5"))
        assertEquals(GameStatus.IN_PROGRESS, getStatus(gc, gameId))
    }

    // -- Claim draw --

    @Test
    fun `claimDraw rejected in external authority`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, authority = Authority.EXTERNAL)

        val result = gc.claimDraw(gameId)
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.ExternalAuthority>(result.error)
    }

    @Test
    fun `claimDraw fails when not applicable`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        val result = gc.claimDraw(gameId)
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.InvalidClaim>(result.error)
    }

    // -- Abort --

    @Test
    fun `abort ends game`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val result = gc.abort(gameId)
        assertIs<Result.Success<Unit>>(result)

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.IN_PROGRESS, state.value.result)
    }

    // -- GetHistory --

    @Test
    fun `getHistory returns move details`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e7e5"))

        val history = gc.getHistory(gameId)
        assertIs<Result.Success<GameHistory>>(history)
        assertEquals(2, history.value.moves.size)
        assertEquals("e2e4", history.value.moves[0].move)
        assertEquals("e4", history.value.moves[0].san)
        assertEquals("e7e5", history.value.moves[1].move)
        assertEquals("e5", history.value.moves[1].san)
        assertTrue(history.value.moves[0].timestamp.isNotEmpty())
    }

    // -- SetResult --

    @Test
    fun `setResult works for external authority`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, authority = Authority.EXTERNAL)
        gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        val result = gc.setResult(SetResultParams(gameId, GameResult.BLACK_WINS, Termination.Resignation))
        assertIs<Result.Success<Unit>>(result)

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.BLACK_WINS, state.value.result)
    }

    @Test
    fun `setResult relays the authority's termination untouched`() = runTest {
        // There is no longer a status to derive from it. The status says the game is over, the
        // result says how it turned out, and the termination says why - each once.
        val cases = listOf(
            Termination.Checkmate,
            Termination.Resignation,
            Termination.Timeout,
            Termination.Aborted,
            Termination.Agreement,
            Termination.ThreefoldRepetition,
            Termination.Other(authorityCode = "variantEnd"),
        )

        for (termination in cases) {
            val gc = GameController(backgroundScope)
            val endings = mutableListOf<GameEndedEvent>()
            val collector = launch { gc.gameEnded.collect { endings.add(it) } }
            val gameId = createGame(gc, authority = Authority.EXTERNAL)

            gc.setResult(SetResultParams(gameId, GameResult.WHITE_WINS, termination))
            advanceUntilIdle()

            val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
            assertEquals(GameStatus.FINISHED, state.status, "for $termination")
            assertEquals(GameResult.WHITE_WINS, state.result, "for $termination")
            assertEquals(termination, endings.single().termination, "for $termination")
            collector.cancel()
        }
    }

    @Test
    fun `setResult fails for local authority`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val result = gc.setResult(SetResultParams(gameId, GameResult.BLACK_WINS, Termination.Resignation))
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.InvalidAuthority>(result.error)
    }

    @Test
    fun `setResult fails on finished game`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, authority = Authority.EXTERNAL)
        gc.setResult(SetResultParams(gameId, GameResult.BLACK_WINS, Termination.Resignation))

        val result = gc.setResult(SetResultParams(gameId, GameResult.WHITE_WINS, Termination.Timeout))
        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameOver>(result.error)
    }

    // -- External authority mode --

    @Test
    fun `external authority does not auto-end on insufficient material`() = runTest {
        val gc = GameController(backgroundScope)
        // White king on d4, black king on d7, black pawn on e5. Capture leaves K vs K.
        val fen = "8/3k4/8/4p3/3K4/8/8/8 w - - 0 1"
        val gameId = createGame(gc, fen = fen, authority = Authority.EXTERNAL)

        // King captures pawn → K vs K
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "d4e5"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals(GameStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `external authority does not auto-end on 75-move rule`() = runTest {
        val gc = GameController(backgroundScope)
        // Half-move clock at 149 — next move is the 75th full move
        val fen = "3k4/2b5/8/3r4/8/8/3K4/7B w - - 149 1"
        val gameId = createGame(gc, fen = fen, authority = Authority.EXTERNAL)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "d2c2"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals(GameStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `external authority does not auto-end on fivefold repetition`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, authority = Authority.EXTERNAL)

        // 4 full rounds of knight moves = fivefold repetition
        val oneRound = listOf("g1f3", "g8f6", "f3g1", "f6g8")
        repeat(4) {
            for (move in oneRound) {
                val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = move))
                assertIs<Result.Success<SubmitMoveResult>>(result)
            }
        }

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.IN_PROGRESS, state.value.status)
        assertEquals(GameResult.IN_PROGRESS, state.value.result)
    }

    @Test
    fun `external authority checkmate still detected via submitMove`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, authority = Authority.EXTERNAL)

        for (move in scholarsMate()) {
            gc.submitMove(SubmitMoveParams(gameId = gameId, move = move))
        }

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.WHITE_WINS, state.value.result)
    }

    @Test
    fun `external authority stalemate still detected via submitMove`() = runTest {
        val gc = GameController(backgroundScope)
        val fen = "k7/8/KQ6/8/8/8/8/8 w - - 0 1"
        val gameId = createGame(gc, fen = fen, authority = Authority.EXTERNAL)

        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "b6c7"))
        assertIs<Result.Success<SubmitMoveResult>>(result)
        assertEquals(GameStatus.FINISHED, result.value.status)
    }

    // -- Retention and concurrency --

    @Test
    fun `finished games are eventually forgotten, and live ones never are`() = runTest {
        val gc = GameController(backgroundScope)

        // A game that is still being played, created first so it is the oldest of them all.
        val live = createGame(gc)

        // Then more finished games than the controller keeps.
        val finished = (1..40).map { createGame(gc).also { id -> gc.resign(id, Color.WHITE) } }

        // The live one survives however many games are played around it.
        assertIs<Result.Success<GameState>>(gc.getState(live))

        // The oldest finished ones are gone; the most recent are still readable, because a caller
        // reads the history and the final position after a game ends.
        assertIs<Result.Failure<GameError>>(gc.getState(finished.first()))
        assertIs<Result.Success<GameState>>(gc.getState(finished.last()))
    }

    @Test
    fun `a move and the flag falling do not both end the game`() = runTest {
        // A single test dispatcher cannot reproduce a data race, so this pins the outcome rather
        // than the memory model: whichever of the two gets there first, the game ends once and
        // its result agrees with its status. The lock is what makes that hold when the timer and
        // the move really are on different threads, which they are as soon as the controller is
        // given a scope backed by more than one.
        repeat(40) {
            val timeSource = FakeTimeSource(0L)
            val gc = GameController(backgroundScope, timeSource)
            val endings = mutableListOf<GameEndedEvent>()
            val collector = launch { gc.gameEnded.collect { endings.add(it) } }

            val tc = TimeControl(TimeControlType.FISCHER, initialMs = 1_000, incrementMs = 0)
            val gameId = createGame(gc, timeControl = tc)

            // The clock says the flag has fallen, and a move arrives at the same moment.
            timeSource.advance(1_000)
            val moved = async { gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4")) }
            advanceUntilIdle()
            moved.await()

            // Whichever won, the game ended exactly once and agrees with itself.
            val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
            assertEquals(GameStatus.FINISHED, state.status)
            assertEquals(1, endings.count { it.gameId == gameId }, "ended more than once")
            assertEquals(GameResult.BLACK_WINS, state.result)
            collector.cancel()
        }
    }

    // -- Turn order and draw offers --

    @Test
    fun `a move by the wrong side is refused as out of turn`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        // Black moves while it is white's turn.
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e7e5"))

        val error = assertIs<Result.Failure<GameError>>(result).error
        // Not ILLEGAL_MOVE: the rules engine only ever offers moves for the side to move, so
        // asking it makes the two indistinguishable - and only one of them is the player's doing.
        assertIs<GameError.NotYourTurn>(error)
    }

    @Test
    fun `a move that is simply illegal is still ILLEGAL_MOVE`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        // White's own piece, nowhere it can go.
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e1e5"))

        assertIs<GameError.IllegalMove>(assertIs<Result.Failure<GameError>>(result).error)
    }

    @Test
    fun `a refused move leaves a pending draw offer standing`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.offerDraw(gameId, Color.WHITE)

        // Any misread of the physical board arrives here as an illegal move.
        assertIs<Result.Failure<GameError>>(gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e5")))

        val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
        assertEquals(GameStatus.DRAW_OFFERED, state.status, "the offer was not the move's to withdraw")
    }

    @Test
    fun `a played move answers a draw offer by ignoring it`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)
        gc.offerDraw(gameId, Color.BLACK)

        assertIs<Result.Success<SubmitMoveResult>>(gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4")))

        val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
        assertEquals(GameStatus.IN_PROGRESS, state.status)
        // And the offer is gone, so responding to it now is not possible.
        assertIs<GameError.NoDrawOffer>(
            assertIs<Result.Failure<GameError>>(gc.respondDraw(gameId, Color.BLACK, accept = true)).error
        )
    }

    // -- Clock management --

    @Test
    fun `fischer increment adds time after move`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 60000, incrementMs = 2000)
        val gameId = createGame(gc, timeControl = tc)

        // White thinks for 10 seconds
        timeSource.advance(10000)
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Success<SubmitMoveResult>>(result)

        // White: 60000 - 10000 + 2000 = 52000
        assertEquals(52000, result.value.clocks!!.whiteMs)
        assertEquals(60000, result.value.clocks!!.blackMs)
    }

    @Test
    fun `a move made after the flag ends the game on time`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val endings = mutableListOf<GameEndedEvent>()
        val collector = launch { gc.gameEnded.collect { endings.add(it) } }

        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 1000, incrementMs = 5000)
        val gameId = createGame(gc, timeControl = tc)

        // White thinks for three seconds on a one second clock. The flag-fall timer is a
        // coroutine and has not resumed, so the move is what discovers the overrun.
        timeSource.advance(3000)
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))

        assertIs<Result.Failure<GameError>>(result)
        assertIs<GameError.GameOver>(result.error)
        advanceUntilIdle()

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)
        assertEquals(GameResult.BLACK_WINS, state.value.result)
        // The increment used to be added to the overrun, leaving white better off than they
        // started: 1000 - 3000 + 5000 = 3000.
        assertEquals(0, state.value.clocks!!.whiteMs)
        assertEquals(Termination.Timeout, endings.single().termination)

        collector.cancel()
    }

    @Test
    fun `a clock is never left negative or topped up past zero`() = runTest {
        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 1000, incrementMs = 5000)
        val after = computeClockAfterMove(
            clocks = Clocks(whiteMs = 1000, blackMs = 60000),
            timeControl = tc,
            elapsedMs = 3000,
            movingColor = Color.WHITE,
        )
        assertEquals(0, after.whiteMs)
        assertEquals(60000, after.blackMs)
    }

    @Test
    fun `a none time control tracks no clocks`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc, timeControl = TimeControl(TimeControlType.NONE, initialMs = 0))

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertNull(state.value.clocks, "a `none` time control is the absence of one")
    }

    @Test
    fun `bronstein increment caps at pre-move time`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val tc = TimeControl(TimeControlType.BRONSTEIN, initialMs = 60000, incrementMs = 5000)
        val gameId = createGame(gc, timeControl = tc)

        // White thinks for 3 seconds (less than increment)
        timeSource.advance(3000)
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Success<SubmitMoveResult>>(result)

        // White: 60000 - 3000 + 3000 = 60000 (gets back what they used, capped at original)
        assertEquals(60000, result.value.clocks!!.whiteMs)
    }

    @Test
    fun `bronstein increment with long thinking`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val tc = TimeControl(TimeControlType.BRONSTEIN, initialMs = 60000, incrementMs = 5000)
        val gameId = createGame(gc, timeControl = tc)

        // White thinks for 10 seconds (more than increment)
        timeSource.advance(10000)
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Success<SubmitMoveResult>>(result)

        // White: 60000 - 10000 + 5000 = 55000
        assertEquals(55000, result.value.clocks!!.whiteMs)
    }

    @Test
    fun `simple time control has no increment`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val tc = TimeControl(TimeControlType.SIMPLE, initialMs = 60000)
        val gameId = createGame(gc, timeControl = tc)

        timeSource.advance(5000)
        val result = gc.submitMove(SubmitMoveParams(gameId = gameId, move = "e2e4"))
        assertIs<Result.Success<SubmitMoveResult>>(result)

        assertEquals(55000, result.value.clocks!!.whiteMs)
        assertEquals(60000, result.value.clocks!!.blackMs)
    }

    @Test
    fun `external clock values override local computation`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(backgroundScope, timeSource)
        val tc = TimeControl(TimeControlType.FISCHER, initialMs = 60000, incrementMs = 2000)
        val gameId = createGame(gc, timeControl = tc)

        timeSource.advance(5000)
        val result = gc.submitMove(
            SubmitMoveParams(gameId = gameId, move = "e2e4", whiteMs = 42000, blackMs = 60000)
        )
        assertIs<Result.Success<SubmitMoveResult>>(result)

        assertEquals(42000, result.value.clocks!!.whiteMs)
        assertEquals(60000, result.value.clocks!!.blackMs)
    }

    // -- Flag fall --

    @Test
    fun `flag fall fires after timeout`() = runTest {
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(this, timeSource)
        val tc = TimeControl(TimeControlType.SIMPLE, initialMs = 5000)
        val gameId = createGame(gc, timeControl = tc)

        // Advance virtual time past the timeout
        testScheduler.advanceTimeBy(6000)
        testScheduler.runCurrent()

        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        assertEquals(GameStatus.FINISHED, state.value.status)

        // With replay=1, the last emitted event is available
        val events = mutableListOf<FlagFallEvent>()
        val collectJob = launch { gc.flagFall.collect { events.add(it) } }
        testScheduler.runCurrent()
        assertTrue(events.any { it.gameId == gameId && it.color == Color.WHITE })

        collectJob.cancel()
    }

    @Test
    fun `a flag fall under FIDE 6 9 is both a timeout and a draw`() = runTest {
        // The ending that the old vocabulary could not express. It had to be reported either as a
        // timeout, losing that nobody won, or as DRAWN, losing that a clock is what stopped it -
        // and the code did both at once, saying `timeout` in the status and `drawn` in the
        // termination. With the cause and the outcome in separate fields, both are simply true.
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(this, timeSource)
        val endings = mutableListOf<GameEndedEvent>()
        val collector = launch { gc.gameEnded.collect { endings.add(it) } }

        val tc = TimeControl(TimeControlType.SIMPLE, initialMs = 5000)
        // White has K+Q and is to move; Black has a bare king. If White flags, Black cannot
        // checkmate with what is left, so the game is drawn rather than lost.
        val fen = "3k4/8/8/8/8/8/3K4/3Q4 w - - 0 1"
        val gameId = createGame(gc, fen = fen, timeControl = tc)

        testScheduler.advanceTimeBy(6000)
        testScheduler.runCurrent()
        advanceUntilIdle()

        val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
        assertEquals(GameStatus.FINISHED, state.status, "where the game is")
        assertEquals(GameResult.DRAW, state.result, "how it turned out - nobody won")
        assertEquals(Termination.Timeout, endings.single().termination, "why it stopped - the clock")

        collector.cancel()
    }

    @Test
    fun `a flag fall the opponent could have mated from is a loss`() = runTest {
        // Same cause, different outcome. Only the result tells them apart.
        val timeSource = FakeTimeSource(0L)
        val gc = GameController(this, timeSource)
        val endings = mutableListOf<GameEndedEvent>()
        val collector = launch { gc.gameEnded.collect { endings.add(it) } }

        val tc = TimeControl(TimeControlType.SIMPLE, initialMs = 5000)
        val gameId = createGame(gc, timeControl = tc)

        testScheduler.advanceTimeBy(6000)
        testScheduler.runCurrent()
        advanceUntilIdle()

        val state = assertIs<Result.Success<GameState>>(gc.getState(gameId)).value
        assertEquals(GameResult.BLACK_WINS, state.result)
        assertEquals(Termination.Timeout, endings.single().termination)

        collector.cancel()
    }

    // -- Game ended events --

    @Test
    fun `game ended event emitted on checkmate`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val events = mutableListOf<GameEndedEvent>()
        val collectJob = launch { gc.gameEnded.collect { events.add(it) } }

        for (move in scholarsMate()) {
            gc.submitMove(SubmitMoveParams(gameId = gameId, move = move))
        }

        advanceUntilIdle()
        assertTrue(events.any { it.gameId == gameId && it.result == GameResult.WHITE_WINS && it.termination == Termination.Checkmate })

        collectJob.cancel()
    }

    @Test
    fun `game ended event emitted on resign`() = runTest {
        val gc = GameController(backgroundScope)
        val gameId = createGame(gc)

        val events = mutableListOf<GameEndedEvent>()
        val collectJob = launch { gc.gameEnded.collect { events.add(it) } }

        gc.resign(gameId, Color.BLACK)

        advanceUntilIdle()
        assertTrue(events.any { it.gameId == gameId && it.result == GameResult.WHITE_WINS && it.termination == Termination.Resignation })

        collectJob.cancel()
    }

    // -- Helper functions --

    private suspend fun createGame(
        gc: GameController,
        fen: String? = null,
        authority: Authority = Authority.LOCAL,
        timeControl: TimeControl? = null,
    ): String {
        val result = gc.create(
            CreateGameParams(fen = fen, authority = authority, timeControl = timeControl)
        )
        assertIs<Result.Success<CreateGameResult>>(result)
        return result.value.gameId
    }

    private suspend fun getStatus(gc: GameController, gameId: String): GameStatus {
        val state = gc.getState(gameId)
        assertIs<Result.Success<GameState>>(state)
        return state.value.status
    }
}
