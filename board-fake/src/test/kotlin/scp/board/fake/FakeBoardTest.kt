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
}
