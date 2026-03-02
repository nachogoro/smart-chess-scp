package scp.orchestrator

import com.nachogoro.simplechess.Game
import scp.protocol.BoardCapabilityLevel
import scp.protocol.BoardState
import scp.protocol.SquareState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MoveInferenceTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    // Helper: make a move in the chess library and return the resulting FEN
    private fun fenAfterMove(fen: String, uci: String): String {
        val game = Game.fromFen(fen).getOrThrow()
        val move = game.availableMoves.first { moveToUci(it) == uci }
        return game.makeMove(move).getOrThrow().currentPosition.fen
    }

    // --- Standard moves at FULL level ---

    @Test
    fun `infer e2e4 from starting position at FULL level`() {
        val afterFen = fenAfterMove(startFen, "e2e4")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(startFen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e2e4", result.uci)
    }

    @Test
    fun `infer g1f3 knight move at FULL level`() {
        val afterFen = fenAfterMove(startFen, "g1f3")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(startFen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("g1f3", result.uci)
    }

    // --- Standard moves at COLOR level ---

    @Test
    fun `infer e2e4 at COLOR level`() {
        val afterFen = fenAfterMove(startFen, "e2e4")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.COLOR)

        val result = inferMove(startFen, boardState, BoardCapabilityLevel.COLOR)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e2e4", result.uci)
    }

    // --- Standard moves at OCCUPANCY level ---

    @Test
    fun `infer e2e4 at OCCUPANCY level`() {
        val afterFen = fenAfterMove(startFen, "e2e4")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.OCCUPANCY)

        val result = inferMove(startFen, boardState, BoardCapabilityLevel.OCCUPANCY)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e2e4", result.uci)
    }

    // --- Castling ---

    @Test
    fun `infer kingside castling at FULL level`() {
        // Position where white can castle kingside
        val fen = "rnbqkbnr/pppppppp/8/8/8/5NP1/PPPPPPBP/RNBQK2R w KQkq - 2 3"
        val afterFen = fenAfterMove(fen, "e1g1")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e1g1", result.uci)
    }

    // --- En passant ---

    @Test
    fun `infer en passant capture at FULL level`() {
        // White pawn on e5, black just played d7d5
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        val afterFen = fenAfterMove(fen, "e5d6")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e5d6", result.uci)
    }

    // --- Promotion ---

    @Test
    fun `infer promotion with specific piece at FULL level`() {
        // White pawn on e7 about to promote
        val fen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val afterFen = fenAfterMove(fen, "e7e8q")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e7e8q", result.uci)
    }

    @Test
    fun `infer promotion to knight at FULL level`() {
        val fen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val afterFen = fenAfterMove(fen, "e7e8n")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("e7e8n", result.uci)
    }

    @Test
    fun `promotion at OCCUPANCY level returns NeedsPromotion`() {
        val fen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"
        // At occupancy level, all 4 promotion pieces look the same
        val afterFen = fenAfterMove(fen, "e7e8q")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.OCCUPANCY)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.OCCUPANCY)
        assertIs<InferenceOutcome.NeedsPromotion>(result)
        assertEquals("e7e8", result.fromTo)
    }

    @Test
    fun `promotion at COLOR level returns NeedsPromotion`() {
        val fen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val afterFen = fenAfterMove(fen, "e7e8q")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.COLOR)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.COLOR)
        assertIs<InferenceOutcome.NeedsPromotion>(result)
        assertEquals("e7e8", result.fromTo)
    }

    // --- Desync / NoMatch ---

    @Test
    fun `no matching move returns NoMatch`() {
        // Board state doesn't correspond to any legal move
        val boardState = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)

        // Pass the starting FEN as both the current position and the board state —
        // no legal move results in the same position
        val result = inferMove(startFen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.NoMatch>(result)
    }

    @Test
    fun `invalid FEN returns NoMatch`() {
        val boardState = BoardState(emptyMap())
        val result = inferMove("invalid fen", boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.NoMatch>(result)
    }

    // --- Capture ---

    @Test
    fun `infer capture at FULL level`() {
        // Italian Game position — white bishop can capture on f7
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        // Check if Bxf7+ is available, if not use a different capture
        val game = Game.fromFen(fen).getOrThrow()
        val captureMove = game.availableMoves.firstOrNull { moveToUci(it) == "c4f7" }
        if (captureMove != null) {
            val afterFen = fenAfterMove(fen, "c4f7")
            val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

            val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
            assertIs<InferenceOutcome.SingleMove>(result)
            assertEquals("c4f7", result.uci)
        }
    }

    // --- Midgame position ---

    @Test
    fun `infer move in midgame position at FULL level`() {
        // After 1.e4 e5 2.Nf3
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val afterFen = fenAfterMove(fen, "b8c6")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.FULL)

        val result = inferMove(fen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals("b8c6", result.uci)
    }

    // --- boardStateFromFen helper ---

    @Test
    fun `boardStateFromFen produces correct FULL state`() {
        val state = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)
        // e1 should be white king
        val e1 = state.squares["e1"]
        assertIs<SquareState.OccupiedByPiece>(e1)
        assertEquals(scp.protocol.PieceType.KING, e1.piece.type)
        assertEquals(scp.protocol.Color.WHITE, e1.piece.color)

        // e4 should be empty
        val e4 = state.squares["e4"]
        assertIs<SquareState.Empty>(e4)
    }

    @Test
    fun `boardStateFromFen produces correct OCCUPANCY state`() {
        val state = boardStateFromFen(startFen, BoardCapabilityLevel.OCCUPANCY)
        val e1 = state.squares["e1"]
        assertIs<SquareState.Occupied>(e1)
        assertEquals(true, e1.occupied)

        val e4 = state.squares["e4"]
        assertIs<SquareState.Empty>(e4)
    }
}
