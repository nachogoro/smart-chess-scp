package scp.orchestrator

import scp.protocol.Color
import scp.protocol.Piece
import scp.protocol.PieceTransfer
import scp.protocol.PieceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveDecompositionTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    /** The transfers for [uci], or a failure if the move could not be decomposed at all. */
    private fun transfers(
        fen: String,
        uci: String,
        canPlacePieces: Boolean = true,
    ): List<PieceTransfer> = assertNotNull(decomposeMove(fen, uci, canPlacePieces)).transfers

    // -----------------------------------------------------------------------
    // Moves that only move something
    // -----------------------------------------------------------------------

    @Test
    fun `a quiet move is one transfer`() {
        assertEquals(listOf(PieceTransfer.Move("e2", "e4")), transfers(startFen, "e2e4"))
    }

    // -----------------------------------------------------------------------
    // Captures: the captured piece comes off first
    // -----------------------------------------------------------------------

    // Italian Game: white bishop captures on f7.
    private val captureFen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

    @Test
    fun `a capture takes the captured piece off before the capturing one lands`() {
        assertEquals(
            listOf(PieceTransfer.Remove("f7"), PieceTransfer.Move("c4", "f7")),
            transfers(captureFen, "c4f7"),
        )
    }

    // White pawn on e5, black just played d7d5.
    private val enPassantFen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"

    @Test
    fun `en passant removes a pawn from a square the move does not name`() {
        val result = transfers(enPassantFen, "e5d6")
        assertEquals(
            listOf(PieceTransfer.Remove("d5"), PieceTransfer.Move("e5", "d6")),
            result,
        )
        assertTrue(result.none { it is PieceTransfer.Remove && it.from == "d6" })
    }

    // -----------------------------------------------------------------------
    // Castling: the rook follows the king
    // -----------------------------------------------------------------------

    private val whiteKingsideFen = "rnbqkbnr/pppppppp/8/8/8/5NP1/PPPPPPBP/RNBQK2R w KQkq - 2 3"
    private val whiteQueensideFen = "rnbqkbnr/pppppppp/8/8/8/2NQB3/PPPPPPPP/R3KBNR w KQkq - 4 4"
    private val blackKingsideFen = "rnbqk2r/ppppppbp/5np1/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 2 3"
    private val blackQueensideFen = "r3kbnr/pppppppp/2nqb3/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 4 4"

    @Test
    fun `white castling kingside moves the rook after the king`() {
        assertEquals(
            listOf(PieceTransfer.Move("e1", "g1"), PieceTransfer.Move("h1", "f1")),
            transfers(whiteKingsideFen, "e1g1"),
        )
    }

    @Test
    fun `white castling queenside moves the rook after the king`() {
        assertEquals(
            listOf(PieceTransfer.Move("e1", "c1"), PieceTransfer.Move("a1", "d1")),
            transfers(whiteQueensideFen, "e1c1"),
        )
    }

    @Test
    fun `black castling kingside moves the rook after the king`() {
        assertEquals(
            listOf(PieceTransfer.Move("e8", "g8"), PieceTransfer.Move("h8", "f8")),
            transfers(blackKingsideFen, "e8g8"),
        )
    }

    @Test
    fun `black castling queenside moves the rook after the king`() {
        assertEquals(
            listOf(PieceTransfer.Move("e8", "c8"), PieceTransfer.Move("a8", "d8")),
            transfers(blackQueensideFen, "e8c8"),
        )
    }

    @Test
    fun `a king moving one square is not a castle`() {
        // Guards the file-distance test: only two squares makes it a castle.
        assertEquals(
            listOf(PieceTransfer.Move("e1", "f1")),
            transfers(whiteKingsideFen, "e1f1"),
        )
    }

    // -----------------------------------------------------------------------
    // Promotion: the piece that arrives is not the piece that left
    // -----------------------------------------------------------------------

    // White pawn on e7, only kings besides.
    private val promotionFen = "6k1/4P3/8/8/8/8/8/4K3 w - - 0 1"

    @Test
    fun `a promotion swaps the pawn for the piece it becomes`() {
        val result = assertNotNull(decomposeMove(promotionFen, "e7e8q", canPlacePieces = true))
        assertEquals(
            listOf(
                PieceTransfer.Remove("e7"),
                PieceTransfer.Place(Piece(PieceType.QUEEN, Color.WHITE), "e8"),
            ),
            result.transfers,
        )
        assertEquals(false, result.leavesPieceToPlace)
    }

    @Test
    fun `an underpromotion places the piece that was chosen`() {
        val result = assertNotNull(decomposeMove(promotionFen, "e7e8n", canPlacePieces = true))
        assertEquals(
            listOf(
                PieceTransfer.Remove("e7"),
                PieceTransfer.Place(Piece(PieceType.KNIGHT, Color.WHITE), "e8"),
            ),
            result.transfers,
        )
    }

    @Test
    fun `a board with nothing to place is asked only to take the pawn off`() {
        val result = assertNotNull(decomposeMove(promotionFen, "e7e8q", canPlacePieces = false))
        // The pawn still comes off. Left standing it would be a position that looks legal, is not
        // this game's, and would be inferred against on the next move.
        assertEquals(listOf(PieceTransfer.Remove("e7")), result.transfers)
        assertTrue(result.leavesPieceToPlace)
    }

    // White pawn on e7, black rook on f8.
    private val capturePromotionFen = "5r1k/4P3/8/8/8/8/8/4K3 w - - 0 1"

    @Test
    fun `a capture that promotes takes both pieces off first`() {
        assertEquals(
            listOf(
                PieceTransfer.Remove("f8"),
                PieceTransfer.Remove("e7"),
                PieceTransfer.Place(Piece(PieceType.QUEEN, Color.WHITE), "f8"),
            ),
            transfers(capturePromotionFen, "e7f8q"),
        )
    }

    @Test
    fun `a black promotion places a black piece`() {
        val fen = "4k3/8/8/8/8/8/4p3/6K1 b - - 0 1"
        assertEquals(
            listOf(
                PieceTransfer.Remove("e2"),
                PieceTransfer.Place(Piece(PieceType.QUEEN, Color.BLACK), "e1"),
            ),
            transfers(fen, "e2e1q"),
        )
    }

    // -----------------------------------------------------------------------
    // Nothing to decompose
    // -----------------------------------------------------------------------

    @Test
    fun `a move that is not legal in the position decomposes to nothing`() {
        assertNull(decomposeMove(startFen, "e2e5", canPlacePieces = true))
    }

    @Test
    fun `a malformed position decomposes to nothing`() {
        assertNull(decomposeMove("not a fen", "e2e4", canPlacePieces = true))
    }
}
