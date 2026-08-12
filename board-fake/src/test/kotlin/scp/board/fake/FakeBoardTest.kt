package scp.board.fake

import kotlinx.coroutines.test.runTest
import scp.protocol.*
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

class FakeBoardTest {

    private fun tempBoardFile(content: String): java.nio.file.Path {
        val file = Files.createTempFile("board-", ".txt")
        file.writeText(content)
        return file
    }

    // -- Capabilities --

    @Test
    fun `full capability level`() = runTest {
        val file = tempBoardFile("full\n")
        val board = FakeBoard(file)

        val caps = board.getCapabilities()
        assertIs<Result.Success<BoardCapabilities>>(caps)
        assertEquals(BoardCapabilityLevel.FULL, caps.value.level)
    }

    @Test
    fun `color capability level`() = runTest {
        val file = tempBoardFile("color\n")
        val board = FakeBoard(file)

        val caps = board.getCapabilities()
        assertIs<Result.Success<BoardCapabilities>>(caps)
        assertEquals(BoardCapabilityLevel.COLOR, caps.value.level)
    }

    @Test
    fun `occupancy capability level`() = runTest {
        val file = tempBoardFile("occupancy\n")
        val board = FakeBoard(file)

        val caps = board.getCapabilities()
        assertIs<Result.Success<BoardCapabilities>>(caps)
        assertEquals(BoardCapabilityLevel.OCCUPANCY, caps.value.level)
    }

    // -- Full mode --

    @Test
    fun `full mode parses pieces`() = runTest {
        val file = tempBoardFile("""
            full
            e1 wK
            d8 bQ
            a2 wP
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val e1 = state.value.squares["e1"]
        assertIs<SquareState.OccupiedByPiece>(e1)
        assertEquals(PieceType.KING, e1.piece.type)
        assertEquals(Color.WHITE, e1.piece.color)

        val d8 = state.value.squares["d8"]
        assertIs<SquareState.OccupiedByPiece>(d8)
        assertEquals(PieceType.QUEEN, d8.piece.type)
        assertEquals(Color.BLACK, d8.piece.color)

        val a2 = state.value.squares["a2"]
        assertIs<SquareState.OccupiedByPiece>(a2)
        assertEquals(PieceType.PAWN, a2.piece.type)
    }

    @Test
    fun `full mode empty squares are Empty`() = runTest {
        val file = tempBoardFile("full\ne1 wK\n")
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        assertIs<SquareState.Empty>(state.value.squares["e4"])
        assertIs<SquareState.Empty>(state.value.squares["h8"])
    }

    @Test
    fun `full mode all 64 squares present`() = runTest {
        val file = tempBoardFile("full\n")
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)
        assertEquals(64, state.value.squares.size)
    }

    @Test
    fun `full mode all piece types`() = runTest {
        val file = tempBoardFile("""
            full
            a1 wR
            b1 wN
            c1 wB
            d1 wQ
            e1 wK
            a2 wP
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val pieces = state.value.squares.values.filterIsInstance<SquareState.OccupiedByPiece>()
        assertEquals(6, pieces.size)

        val types = pieces.map { it.piece.type }.toSet()
        assertEquals(setOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN, PieceType.KING, PieceType.PAWN), types)
    }

    // -- Color mode --

    @Test
    fun `color mode parses colors`() = runTest {
        val file = tempBoardFile("""
            color
            e1 w
            e8 b
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val e1 = state.value.squares["e1"]
        assertIs<SquareState.OccupiedByColor>(e1)
        assertEquals(Color.WHITE, e1.color)

        val e8 = state.value.squares["e8"]
        assertIs<SquareState.OccupiedByColor>(e8)
        assertEquals(Color.BLACK, e8.color)
    }

    // -- Occupancy mode --

    @Test
    fun `occupancy mode marks squares as occupied`() = runTest {
        val file = tempBoardFile("""
            occupancy
            e2
            e7
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val e2 = state.value.squares["e2"]
        assertIs<SquareState.Occupied>(e2)
        assertTrue(e2.occupied)

        assertIs<SquareState.Empty>(state.value.squares["e4"])
    }

    // -- File re-reading --

    @Test
    fun `getState re-reads file on each call`() = runTest {
        val file = tempBoardFile("full\ne1 wK\n")
        val board = FakeBoard(file)

        val state1 = board.getState()
        assertIs<Result.Success<BoardState>>(state1)
        assertIs<SquareState.OccupiedByPiece>(state1.value.squares["e1"])
        assertIs<SquareState.Empty>(state1.value.squares["e2"])

        // Simulate UI moving a piece
        file.writeText("full\ne2 wK\n")

        val state2 = board.getState()
        assertIs<Result.Success<BoardState>>(state2)
        assertIs<SquareState.Empty>(state2.value.squares["e1"])
        assertIs<SquareState.OccupiedByPiece>(state2.value.squares["e2"])
    }

    @Test
    fun `capability level can change between reads`() = runTest {
        val file = tempBoardFile("full\ne1 wK\n")
        val board = FakeBoard(file)

        val caps1 = board.getCapabilities()
        assertIs<Result.Success<BoardCapabilities>>(caps1)
        assertEquals(BoardCapabilityLevel.FULL, caps1.value.level)

        file.writeText("color\ne1 w\n")

        val caps2 = board.getCapabilities()
        assertIs<Result.Success<BoardCapabilities>>(caps2)
        assertEquals(BoardCapabilityLevel.COLOR, caps2.value.level)
    }

    // -- Error handling --

    @Test
    fun `missing file returns NotReady`() = runTest {
        val file = java.nio.file.Path.of("/tmp/nonexistent-board-file-12345.txt")
        val board = FakeBoard(file)

        val result = board.getState()
        assertIs<Result.Failure<BoardError>>(result)
        assertIs<BoardError.NotReady>(result.error)
    }

    @Test
    fun `empty file returns NotReady`() = runTest {
        val file = tempBoardFile("")
        val board = FakeBoard(file)

        val result = board.getState()
        assertIs<Result.Failure<BoardError>>(result)
        assertIs<BoardError.NotReady>(result.error)
    }

    @Test
    fun `invalid capability level returns NotReady`() = runTest {
        val file = tempBoardFile("unknown\ne1 wK\n")
        val board = FakeBoard(file)

        val result = board.getState()
        assertIs<Result.Failure<BoardError>>(result)
        assertIs<BoardError.NotReady>(result.error)
    }

    // -- Comments and blank lines --

    @Test
    fun `comments and blank lines are ignored`() = runTest {
        val file = tempBoardFile("""
            # Board state
            full

            # White king
            e1 wK

            # Black king
            e8 bK
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val pieces = state.value.squares.values.filterIsInstance<SquareState.OccupiedByPiece>()
        assertEquals(2, pieces.size)
    }

    @Test
    fun `malformed piece lines are skipped`() = runTest {
        val file = tempBoardFile("""
            full
            e1 wK
            bad line here
            e8 bK
        """.trimIndent())
        val board = FakeBoard(file)

        val state = board.getState()
        assertIs<Result.Success<BoardState>>(state)

        val pieces = state.value.squares.values.filterIsInstance<SquareState.OccupiedByPiece>()
        assertEquals(2, pieces.size)
    }

    // -- Moving pieces --

    private suspend fun FakeBoard.squares(): Map<String, SquareState> =
        assertIs<Result.Success<BoardState>>(getState()).value.squares

    @Test
    fun `capabilities carry the actuation the board was given`() = runTest {
        val board = FakeBoard(tempBoardFile("full\ne2 wP\n"), BoardActuation(canPlacePieces = true))

        val caps = assertIs<Result.Success<BoardCapabilities>>(board.getCapabilities())
        assertEquals(BoardActuation(canPlacePieces = true), caps.value.actuation)
    }

    @Test
    fun `a board that does not actuate refuses to move pieces`() = runTest {
        val board = FakeBoard(tempBoardFile("full\ne2 wP\n"))

        assertNull(assertIs<Result.Success<BoardCapabilities>>(board.getCapabilities()).value.actuation)
        val moved = assertIs<Result.Failure<BoardError>>(board.movePieces(listOf(PieceTransfer.Move("e2", "e4"))))
        assertEquals("METHOD_NOT_FOUND", moved.error.code)
    }

    @Test
    fun `moving a piece rewrites the file`() = runTest {
        val file = tempBoardFile("full\ne2 wP\ne1 wK\n")
        val board = FakeBoard(file, BoardActuation())

        assertIs<Result.Success<Unit>>(board.movePieces(listOf(PieceTransfer.Move("e2", "e4"))))

        // Read back through a second board, so the assertion is about the file and not about
        // anything the first one remembered.
        val squares = FakeBoard(file).squares()
        assertEquals(SquareState.Empty, squares["e2"])
        assertEquals(SquareState.OccupiedByPiece(Piece(PieceType.PAWN, Color.WHITE)), squares["e4"])
        assertEquals(SquareState.OccupiedByPiece(Piece(PieceType.KING, Color.WHITE)), squares["e1"])
    }

    @Test
    fun `a capture is a remove and a move, in that order`() = runTest {
        val file = tempBoardFile("full\nd5 wP\ne4 bP\n")
        val board = FakeBoard(file, BoardActuation())

        assertIs<Result.Success<Unit>>(board.movePieces(listOf(
            PieceTransfer.Remove("e4"),
            PieceTransfer.Move("d5", "e4"),
        )))

        val squares = board.squares()
        assertEquals(SquareState.Empty, squares["d5"])
        assertEquals(SquareState.OccupiedByPiece(Piece(PieceType.PAWN, Color.WHITE)), squares["e4"])
    }

    @Test
    fun `moving from an empty square fails and says how far it got`() = runTest {
        val board = FakeBoard(tempBoardFile("full\ne2 wP\n"), BoardActuation())

        val moved = assertIs<Result.Failure<BoardError>>(board.movePieces(listOf(
            PieceTransfer.Move("e2", "e4"),
            PieceTransfer.Move("a1", "a2"),
        )))
        val error = assertIs<BoardError.ActuationFailed>(moved.error)
        assertEquals(1, error.completed)
    }

    @Test
    fun `clearing an empty square is not an error`() = runTest {
        // The board is told which square to empty, not why, and cannot judge whether it should
        // have been occupied.
        val board = FakeBoard(tempBoardFile("full\ne2 wP\n"), BoardActuation())

        assertIs<Result.Success<Unit>>(board.movePieces(listOf(PieceTransfer.Remove("h7"))))
    }

    @Test
    fun `a place transfer is refused by a board with nothing to place`() = runTest {
        val file = tempBoardFile("full\ne7 wP\n")
        val board = FakeBoard(file, BoardActuation(canPlacePieces = false))

        val moved = assertIs<Result.Failure<BoardError>>(board.movePieces(listOf(
            PieceTransfer.Remove("e7"),
            PieceTransfer.Place(Piece(PieceType.QUEEN, Color.WHITE), "e8"),
        )))
        assertEquals("INVALID_PARAMS", moved.error.code)

        // Refused whole: the pawn it could have removed is still standing.
        assertEquals(SquareState.OccupiedByPiece(Piece(PieceType.PAWN, Color.WHITE)), board.squares()["e7"])
    }

    @Test
    fun `a placed piece is written at whatever the level can hold`() = runTest {
        val full = FakeBoard(tempBoardFile("full\ne7 wP\n"), BoardActuation(canPlacePieces = true))
        assertIs<Result.Success<Unit>>(full.movePieces(listOf(
            PieceTransfer.Place(Piece(PieceType.QUEEN, Color.WHITE), "e8"),
        )))
        assertEquals(SquareState.OccupiedByPiece(Piece(PieceType.QUEEN, Color.WHITE)), full.squares()["e8"])

        // A board that cannot see what a piece is does not become able to by being handed one.
        val occupancy = FakeBoard(tempBoardFile("occupancy\ne7\n"), BoardActuation(canPlacePieces = true))
        assertIs<Result.Success<Unit>>(occupancy.movePieces(listOf(
            PieceTransfer.Place(Piece(PieceType.QUEEN, Color.WHITE), "e8"),
        )))
        assertEquals(SquareState.Occupied(true), occupancy.squares()["e8"])
    }
}
