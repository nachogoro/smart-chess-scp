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

    private fun fenAfterMove(fen: String, uci: String): String {
        val game = Game.fromFen(fen).getOrThrow()
        val move = game.availableMoves.first { moveToUci(it) == uci }
        return game.makeMove(move).getOrThrow().currentPosition.fen
    }

    private fun assertSingleMove(fen: String, uci: String, level: BoardCapabilityLevel) {
        val afterFen = fenAfterMove(fen, uci)
        val boardState = boardStateFromFen(afterFen, level)
        val result = inferMove(fen, boardState, level)
        assertIs<InferenceOutcome.SingleMove>(result)
        assertEquals(uci, result.uci)
    }

    // -----------------------------------------------------------------------
    // Regular pawn move
    // -----------------------------------------------------------------------

    @Test
    fun `pawn move at FULL level`() = assertSingleMove(startFen, "e2e4", BoardCapabilityLevel.FULL)

    @Test
    fun `pawn move at COLOR level`() = assertSingleMove(startFen, "e2e4", BoardCapabilityLevel.COLOR)

    @Test
    fun `pawn move at OCCUPANCY level`() = assertSingleMove(startFen, "e2e4", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // Regular piece move
    // -----------------------------------------------------------------------

    @Test
    fun `knight move at FULL level`() = assertSingleMove(startFen, "g1f3", BoardCapabilityLevel.FULL)

    @Test
    fun `knight move at COLOR level`() = assertSingleMove(startFen, "g1f3", BoardCapabilityLevel.COLOR)

    @Test
    fun `knight move at OCCUPANCY level`() = assertSingleMove(startFen, "g1f3", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // En passant
    // -----------------------------------------------------------------------

    // White pawn on e5, black just played d7d5
    private val enPassantFen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"

    @Test
    fun `en passant at FULL level`() = assertSingleMove(enPassantFen, "e5d6", BoardCapabilityLevel.FULL)

    @Test
    fun `en passant at COLOR level`() = assertSingleMove(enPassantFen, "e5d6", BoardCapabilityLevel.COLOR)

    @Test
    fun `en passant at OCCUPANCY level`() = assertSingleMove(enPassantFen, "e5d6", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // Kingside castling
    // -----------------------------------------------------------------------

    // White can castle kingside (f1 and g1 clear, king and rook unmoved)
    private val kingsideCastleFen = "rnbqkbnr/pppppppp/8/8/8/5NP1/PPPPPPBP/RNBQK2R w KQkq - 2 3"

    @Test
    fun `kingside castling at FULL level`() = assertSingleMove(kingsideCastleFen, "e1g1", BoardCapabilityLevel.FULL)

    @Test
    fun `kingside castling at COLOR level`() = assertSingleMove(kingsideCastleFen, "e1g1", BoardCapabilityLevel.COLOR)

    @Test
    fun `kingside castling at OCCUPANCY level`() = assertSingleMove(kingsideCastleFen, "e1g1", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // Queenside castling
    // -----------------------------------------------------------------------

    // White can castle queenside (b1, c1, d1 clear, king and rook unmoved)
    private val queensideCastleFen = "rnbqkbnr/pppppppp/8/8/8/2NQB3/PPPPPPPP/R3KBNR w KQkq - 4 4"

    @Test
    fun `queenside castling at FULL level`() = assertSingleMove(queensideCastleFen, "e1c1", BoardCapabilityLevel.FULL)

    @Test
    fun `queenside castling at COLOR level`() = assertSingleMove(queensideCastleFen, "e1c1", BoardCapabilityLevel.COLOR)

    @Test
    fun `queenside castling at OCCUPANCY level`() = assertSingleMove(queensideCastleFen, "e1c1", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    // Italian Game: white bishop captures on f7
    private val captureFen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

    @Test
    fun `capture at FULL level`() = assertSingleMove(captureFen, "c4f7", BoardCapabilityLevel.FULL)

    @Test
    fun `capture at COLOR level`() = assertSingleMove(captureFen, "c4f7", BoardCapabilityLevel.COLOR)

    @Test
    fun `capture at OCCUPANCY level`() = assertSingleMove(captureFen, "c4f7", BoardCapabilityLevel.OCCUPANCY)

    // -----------------------------------------------------------------------
    // Promotion
    // -----------------------------------------------------------------------

    // White pawn on e7, only kings on the board
    private val promotionFen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"

    @Test
    fun `promotion to queen at FULL level resolves to SingleMove`() =
        assertSingleMove(promotionFen, "e7e8q", BoardCapabilityLevel.FULL)

    @Test
    fun `promotion to knight at FULL level resolves to SingleMove`() =
        assertSingleMove(promotionFen, "e7e8n", BoardCapabilityLevel.FULL)

    @Test
    fun `promotion at COLOR level returns NeedsPromotion`() {
        val afterFen = fenAfterMove(promotionFen, "e7e8q")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.COLOR)
        val result = inferMove(promotionFen, boardState, BoardCapabilityLevel.COLOR)
        assertIs<InferenceOutcome.NeedsPromotion>(result)
        assertEquals("e7e8", result.fromTo)
    }

    @Test
    fun `promotion at OCCUPANCY level returns NeedsPromotion`() {
        val afterFen = fenAfterMove(promotionFen, "e7e8q")
        val boardState = boardStateFromFen(afterFen, BoardCapabilityLevel.OCCUPANCY)
        val result = inferMove(promotionFen, boardState, BoardCapabilityLevel.OCCUPANCY)
        assertIs<InferenceOutcome.NeedsPromotion>(result)
        assertEquals("e7e8", result.fromTo)
    }

    // -----------------------------------------------------------------------
    // Board still showing the current position (Unchanged)
    // -----------------------------------------------------------------------

    // A board identical to the position being played means the player signalled before moving,
    // which is reported as Unchanged rather than as an unexplainable board.

    @Test
    fun `board still at the current position at FULL level returns Unchanged`() {
        val boardState = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)
        val result = inferMove(startFen, boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.Unchanged>(result)
    }

    @Test
    fun `board still at the current position at COLOR level returns Unchanged`() {
        val boardState = boardStateFromFen(startFen, BoardCapabilityLevel.COLOR)
        val result = inferMove(startFen, boardState, BoardCapabilityLevel.COLOR)
        assertIs<InferenceOutcome.Unchanged>(result)
    }

    @Test
    fun `board still at the current position at OCCUPANCY level returns Unchanged`() {
        val boardState = boardStateFromFen(startFen, BoardCapabilityLevel.OCCUPANCY)
        val result = inferMove(startFen, boardState, BoardCapabilityLevel.OCCUPANCY)
        assertIs<InferenceOutcome.Unchanged>(result)
    }

    // -----------------------------------------------------------------------
    // Invalid move (NoMatch)
    // -----------------------------------------------------------------------

    @Test
    fun `board two moves ahead returns NoMatch`() {
        // Two plies on from the position being played, so it is neither unchanged nor reachable by
        // any single legal move: the case that genuinely means the board and the game disagree.
        val twoMovesOn = fenAfterMove(fenAfterMove(startFen, "e2e4"), "e7e5")
        val boardState = boardStateFromFen(twoMovesOn, BoardCapabilityLevel.COLOR)
        val result = inferMove(startFen, boardState, BoardCapabilityLevel.COLOR)
        assertIs<InferenceOutcome.NoMatch>(result)
    }

    @Test
    fun `invalid FEN returns NoMatch`() {
        val boardState = BoardState(emptyMap())
        val result = inferMove("invalid fen", boardState, BoardCapabilityLevel.FULL)
        assertIs<InferenceOutcome.NoMatch>(result)
    }

    // -----------------------------------------------------------------------
    // boardStateFromFen helper
    // -----------------------------------------------------------------------

    @Test
    fun `boardStateFromFen produces correct FULL state`() {
        val state = boardStateFromFen(startFen, BoardCapabilityLevel.FULL)
        val e1 = state.squares["e1"]
        assertIs<SquareState.OccupiedByPiece>(e1)
        assertEquals(scp.protocol.PieceType.KING, e1.piece.type)
        assertEquals(scp.protocol.Color.WHITE, e1.piece.color)

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
