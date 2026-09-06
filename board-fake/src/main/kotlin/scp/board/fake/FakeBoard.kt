package scp.board.fake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * A fake [BoardModule] that reads board state from a file on disk.
 *
 * Designed for desktop demos where a visual UI writes piece positions to a file
 * and this module reads them on each [getState] call. The capability level is
 * determined by the first line of the file, so it can change dynamically.
 *
 * ## File format
 *
 * Line-based text file. The first line declares the capability level and determines
 * how subsequent lines are interpreted:
 *
 * ```
 * full
 * a1 wR
 * b1 wN
 * e2 wP
 * e7 bP
 * e8 bK
 * ```
 *
 * **First line**: the capability level, one of `full`, `color`, or `occupancy`, optionally
 * followed by what the board can do about moving pieces: `moves` if it can move a piece from one
 * square to another, and `places` if it can also put down a piece that was not on the board. A
 * first line with neither describes a board that only senses; any other word makes the file
 * unreadable rather than a quietly non-moving board.
 *
 * ```
 * full moves places
 * ```
 *
 * **Subsequent lines**: one per occupied square. Empty squares are not listed.
 * The format of each line depends on the capability level:
 *
 * - **`full`**: `<square> <color><piece>`, e.g. `e2 wP`, `a8 bR`.
 *   Color is `w`/`b`. Piece is `P`/`N`/`B`/`R`/`Q`/`K`.
 *
 * - **`color`**: `<square> <color>`, e.g. `e2 w`, `e7 b`.
 *
 * - **`occupancy`**: `<square>`, e.g. `e2`, `e7`. Just lists which squares are occupied.
 *
 * Blank lines and lines starting with `#` are ignored.
 *
 * ## Moving pieces
 *
 * A board whose first line says it moves pieces rewrites the file when it is asked to, which is the
 * only thing "moving a piece" can mean for a board that is a file. Whether it can is declared in
 * the file rather than in code for the same reason the level is: the file is the board, and a
 * board that changes between games should be one edit, not a rebuild. Whatever else displays the
 * file has to read it back after the board has moved something, as the desktop client does.
 *
 * @param filePath Path to the board state file.
 */
class FakeBoard(private val filePath: Path) : BoardModule {

    private val _errors = MutableSharedFlow<BoardError>()
    override val errors: SharedFlow<BoardError> = _errors

    /**
     * Returns the board's capabilities, read from the first line of the file.
     */
    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        val parsed = readFile() ?: return Result.Failure(BoardError.NotReady(diagnostic = "the board file could not be read"))
        return Result.Success(BoardCapabilities(parsed.level, parsed.actuation))
    }

    /**
     * Returns the current board state by reading and parsing the file.
     *
     * Each call re-reads the file, so external changes (e.g. from a UI) are picked up
     * immediately.
     */
    override suspend fun getState(): Result<BoardError, BoardState> {
        val parsed = readFile() ?: return Result.Failure(BoardError.NotReady(diagnostic = "the board file could not be read"))
        return Result.Success(BoardState(parsed.squares))
    }

    /**
     * Applies [transfers] by rewriting the file.
     *
     * The file is re-read first for the same reason [getState] re-reads it: the file is the board,
     * and anything else editing it has as much right to be there as this does.
     */
    override suspend fun movePieces(transfers: List<PieceTransfer>): Result<BoardError, Unit> {
        val parsed = readFile()
            ?: return Result.Failure(BoardError.NotReady(diagnostic = "the board file could not be read"))
        val actuation = parsed.actuation ?: return super.movePieces(transfers)

        // Refused whole rather than up to the offending transfer: a half-applied list leaves the
        // pieces in a position nobody can name.
        transfers.filterIsInstance<PieceTransfer.Place>().firstOrNull()?.let {
            if (!actuation.canPlacePieces) {
                return Result.Failure(BoardError.InvalidParams(
                    diagnostic = "asked to place a ${it.piece} on ${it.to} with nothing to place",
                ))
            }
        }

        val squares = parsed.squares.toMutableMap()
        transfers.forEachIndexed { index, transfer ->
            when (transfer) {
                is PieceTransfer.Move -> {
                    val moving = squares[transfer.from]
                    if (moving == null || moving is SquareState.Empty) {
                        // The transfers before this one were carried out, and `completed` says so;
                        // the file has to agree, or a caller reading the board back after the
                        // failure would find the pieces where they stood before any of them.
                        writeFile(parsed, squares)
                        return Result.Failure(BoardError.ActuationFailed(
                            completed = index,
                            diagnostic = "${transfer.from} is empty, so nothing could move to ${transfer.to}",
                        ))
                    }
                    squares[transfer.to] = moving
                    squares[transfer.from] = SquareState.Empty
                }
                // Clearing an already-empty square is not an error. The board is told which square
                // to empty, not why, and is in no position to judge whether it should have been.
                is PieceTransfer.Remove -> squares[transfer.from] = SquareState.Empty
                is PieceTransfer.Place -> squares[transfer.to] = occupiedBy(transfer.piece, parsed.level)
            }
        }

        return if (writeFile(parsed, squares)) Result.Success(Unit)
        else Result.Failure(BoardError.ActuationFailed(
            completed = transfers.size,
            diagnostic = "the board file could not be written",
        ))
    }

    private fun writeFile(parsed: ParsedBoardFile, squares: Map<String, SquareState>): Boolean =
        try {
            filePath.writeText(render(parsed.level, parsed.actuation, squares))
            true
        } catch (_: Exception) {
            false
        }

    private fun readFile(): ParsedBoardFile? {
        val lines = try {
            filePath.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        } catch (_: Exception) {
            return null
        }

        if (lines.isEmpty()) return null

        val header = lines[0].trim().lowercase().split("\\s+".toRegex())
        val level = when (header[0]) {
            "full" -> BoardCapabilityLevel.FULL
            "color" -> BoardCapabilityLevel.COLOR
            "occupancy" -> BoardCapabilityLevel.OCCUPANCY
            else -> return null
        }
        val actuation = (parseActuation(header.drop(1)) ?: return null).value

        val squares = mutableMapOf<String, SquareState>()

        // Initialize all 64 squares as empty
        for (file in 'a'..'h') {
            for (rank in 1..8) {
                squares["$file$rank"] = SquareState.Empty
            }
        }

        // Parse occupied squares
        for (line in lines.drop(1)) {
            val parts = line.trim().split("\\s+".toRegex())
            val square = parts[0].lowercase()

            val state = when (level) {
                BoardCapabilityLevel.OCCUPANCY -> SquareState.Occupied(occupied = true)
                BoardCapabilityLevel.COLOR -> {
                    val color = parseColor(parts.getOrNull(1)) ?: continue
                    SquareState.OccupiedByColor(color)
                }
                BoardCapabilityLevel.FULL -> {
                    val piece = parsePiece(parts.getOrNull(1)) ?: continue
                    SquareState.OccupiedByPiece(piece)
                }
            }

            squares[square] = state
        }

        return ParsedBoardFile(level, actuation, squares)
    }

    private data class ParsedBoardFile(
        val level: BoardCapabilityLevel,
        val actuation: BoardActuation?,
        val squares: Map<String, SquareState>,
    )
}

/**
 * The words after the level on the first line, as what the board declares about moving pieces.
 *
 * Wrapped so that a board that only senses (an empty list) can be told from a first line that
 * says something this parser does not know (null), which makes the whole file unreadable: a typo
 * should surface as a board that will not answer, not as one that quietly never moves.
 */
private fun parseActuation(flags: List<String>): Optional<BoardActuation?>? {
    if (flags.any { it != MOVES && it != PLACES }) return null
    if (flags.isEmpty()) return Optional(null)
    return Optional(BoardActuation(canPlacePieces = PLACES in flags))
}

private const val MOVES = "moves"
private const val PLACES = "places"

private data class Optional<T>(val value: T)

/**
 * A placed piece as much of it as [level] can hold.
 *
 * A board that cannot see what a piece is does not become able to by being handed one, so the
 * detail is dropped here rather than written into a file the next read would misinterpret. It is
 * what makes actuation independent of sensing level rather than quietly requiring `full`.
 */
private fun occupiedBy(piece: Piece, level: BoardCapabilityLevel): SquareState = when (level) {
    BoardCapabilityLevel.FULL -> SquareState.OccupiedByPiece(piece)
    BoardCapabilityLevel.COLOR -> SquareState.OccupiedByColor(piece.color)
    BoardCapabilityLevel.OCCUPANCY -> SquareState.Occupied(occupied = true)
}

/** The inverse of the parser above: the same file, with the pieces where they now stand. */
private fun render(
    level: BoardCapabilityLevel,
    actuation: BoardActuation?,
    squares: Map<String, SquareState>,
): String {
    val header = listOfNotNull(
        when (level) {
            BoardCapabilityLevel.FULL -> "full"
            BoardCapabilityLevel.COLOR -> "color"
            BoardCapabilityLevel.OCCUPANCY -> "occupancy"
        },
        MOVES.takeIf { actuation != null },
        PLACES.takeIf { actuation?.canPlacePieces == true },
    ).joinToString(" ")
    val occupied = squares.entries
        .filter { it.value !is SquareState.Empty }
        .sortedBy { it.key }
        .mapNotNull { (square, state) ->
            when (state) {
                is SquareState.Empty -> null
                is SquareState.Occupied -> square
                is SquareState.OccupiedByColor -> "$square ${renderColor(state.color)}"
                is SquareState.OccupiedByPiece ->
                    "$square ${renderColor(state.piece.color)}${renderType(state.piece.type)}"
            }
        }
    return (listOf(header) + occupied).joinToString("\n", postfix = "\n")
}

private fun renderColor(color: Color): String = when (color) {
    Color.WHITE -> "w"
    Color.BLACK -> "b"
}

private fun renderType(type: PieceType): String = when (type) {
    PieceType.PAWN -> "P"
    PieceType.KNIGHT -> "N"
    PieceType.BISHOP -> "B"
    PieceType.ROOK -> "R"
    PieceType.QUEEN -> "Q"
    PieceType.KING -> "K"
}

private fun parseColor(token: String?): Color? = when (token?.lowercase()) {
    "w" -> Color.WHITE
    "b" -> Color.BLACK
    else -> null
}

private fun parsePiece(token: String?): Piece? {
    if (token == null || token.length != 2) return null
    val color = parseColor(token[0].toString()) ?: return null
    val type = when (token[1].uppercaseChar()) {
        'P' -> PieceType.PAWN
        'N' -> PieceType.KNIGHT
        'B' -> PieceType.BISHOP
        'R' -> PieceType.ROOK
        'Q' -> PieceType.QUEEN
        'K' -> PieceType.KING
        else -> return null
    }
    return Piece(type, color)
}
