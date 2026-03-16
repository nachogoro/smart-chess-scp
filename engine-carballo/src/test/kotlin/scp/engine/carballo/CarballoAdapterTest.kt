package scp.engine.carballo

import kotlinx.coroutines.test.runTest
import scp.protocol.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class CarballoAdapterTest {

    private suspend fun configuredAdapter(
        settings: Map<String, Any> = emptyMap(),
    ): CarballoAdapter {
        val adapter = CarballoAdapter()
        val result = adapter.configure(settings)
        assertIs<Result.Success<Unit>>(result)
        return adapter
    }

    // -- getCapabilities --

    @Test
    fun `getCapabilities returns engine info and parameters`() = runTest {
        val adapter = CarballoAdapter()
        val result = adapter.getCapabilities()
        assertIs<Result.Success<EngineCapabilities>>(result)

        val caps = result.value
        assertEquals("Carballo 1.8", caps.name)
        assertEquals("Alberto Alonso Ruibal", caps.author)
        assertTrue(caps.parameters.any { it.name == "UCI_LimitStrength" })
        assertTrue(caps.parameters.any { it.name == "UCI_Elo" })
        assertTrue(caps.parameters.any { it.name == "Hash" })
        assertTrue(caps.parameters.any { it.name == "UseBook" })
    }

    @Test
    fun `UCI_Elo is visible only when UCI_LimitStrength is true`() = runTest {
        val adapter = CarballoAdapter()
        val caps = (adapter.getCapabilities() as Result.Success).value
        val eloParam = caps.parameters.first { it.name == "UCI_Elo" }
        assertEquals("UCI_LimitStrength", eloParam.visibleWhen?.field)
        assertEquals(true, eloParam.visibleWhen?.equals)
    }

    // -- configure --

    @Test
    fun `configure with empty settings uses defaults`() = runTest {
        val adapter = configuredAdapter()
        // Should not crash — engine is usable with defaults
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            maxDepth = 1,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
    }

    @Test
    fun `configure with strength limiting`() = runTest {
        val adapter = configuredAdapter(mapOf(
            "UCI_LimitStrength" to true,
            "UCI_Elo" to 800,
        ))
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            maxDepth = 3,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
    }

    @Test
    fun `configure rejects invalid Elo`() = runTest {
        val adapter = CarballoAdapter()
        val result = adapter.configure(mapOf("UCI_Elo" to 5000))
        assertIs<Result.Failure<EngineError>>(result)
        assertIs<EngineError.InvalidParams>(result.error)
    }

    @Test
    fun `configure rejects invalid Hash`() = runTest {
        val adapter = CarballoAdapter()
        val result = adapter.configure(mapOf("Hash" to 0))
        assertIs<Result.Failure<EngineError>>(result)
        assertIs<EngineError.InvalidParams>(result.error)
    }

    @Test
    fun `configure rejects unknown parameter`() = runTest {
        val adapter = CarballoAdapter()
        val result = adapter.configure(mapOf("UnknownParam" to 42))
        assertIs<Result.Failure<EngineError>>(result)
        assertIs<EngineError.InvalidParams>(result.error)
    }

    // -- getBestMove --

    @Test
    fun `getBestMove with no depth or time uses default depth`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
        assertNotNull(result.value.depth, "Should have searched to some depth")
        assertTrue(result.value.depth!! > 0, "Depth should be > 0")
    }

    @Test
    fun `getBestMove without configure fails`() = runTest {
        val adapter = CarballoAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        ))
        assertIs<Result.Failure<EngineError>>(result)
        assertIs<EngineError.NotConfigured>(result.error)
    }

    @Test
    fun `getBestMove returns valid UCI move from starting position`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            maxDepth = 4,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)

        val move = result.value.move
        assertTrue(move.length in 4..5, "UCI move should be 4-5 chars, got: $move")
        // Move should start with a valid square
        assertTrue(move[0] in 'a'..'h', "Move should start with a-h")
        assertTrue(move[1] in '1'..'8', "Second char should be 1-8")
    }

    @Test
    fun `getBestMove returns score and depth`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            maxDepth = 5,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
        assertNotNull(result.value.score, "Should return a score")
        assertNotNull(result.value.depth, "Should return search depth")
        assertTrue(result.value.depth!! > 0)
    }

    @Test
    fun `getBestMove respects depth limit`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            maxDepth = 2,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
        assertTrue(result.value.depth!! <= 2, "Depth should not exceed limit")
    }

    @Test
    fun `getBestMove finds mate in one`() = runTest(timeout = 30.seconds) {
        // White to move, Qh7# is mate in 1
        val adapter = configuredAdapter()
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "6k1/5ppp/8/8/8/8/8/4Q2K w - - 0 1",
            maxDepth = 4,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)

        val score = result.value.score
        assertNotNull(score)
        assertEquals(ScoreType.MATE, score.type, "Should detect mate")
    }

    @Test
    fun `getBestMove searches the position given by FEN`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()
        // Position after 1. e4 — black to move
        val result = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            maxDepth = 3,
        ))
        assertIs<Result.Success<BestMoveResult>>(result)
        val move = result.value.move
        assertTrue(move.length in 4..5)
        // Black's move should start from rank 7 or 8 (black's territory)
        assertTrue(move[1] in '7'..'8' || move[1] == '6', "Black should move from back ranks, got: $move")
    }

    // -- newGame --

    @Test
    fun `newGame resets engine state`() = runTest(timeout = 30.seconds) {
        val adapter = configuredAdapter()

        // Play a position
        adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            maxDepth = 2,
        ))

        // Reset
        val result = adapter.newGame()
        assertIs<Result.Success<Unit>>(result)

        // Should still work after reset
        val moveResult = adapter.getBestMove(GetBestMoveParams(
            fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            maxDepth = 2,
        ))
        assertIs<Result.Success<BestMoveResult>>(moveResult)
    }
}
