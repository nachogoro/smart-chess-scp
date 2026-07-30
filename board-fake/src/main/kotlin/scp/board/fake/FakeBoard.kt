package scp.board.fake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*
import java.nio.file.Path
import kotlin.io.path.readLines

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
 * **First line**: one of `full`, `color`, or `occupancy`.
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
 * @param filePath Path to the board state file.
 */
class FakeBoard(
    private val filePath: Path,
) : BoardModule {

    private val _errors = MutableSharedFlow<BoardError>()
    override val errors: SharedFlow<BoardError> = _errors

    /**
     * Returns the board's capability level, read from the first line of the file.
     */
    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        val parsed = readFile() ?: return Result.Failure(BoardError.NotReady(diagnostic = "the board file could not be read"))
        return Result.Success(BoardCapabilities(parsed.level))
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

    private fun readFile(): ParsedBoardFile? {
        val lines = try {
            filePath.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        } catch (_: Exception) {
            return null
        }

        if (lines.isEmpty()) return null

        val level = when (lines[0].trim().lowercase()) {
            "full" -> BoardCapabilityLevel.FULL
            "color" -> BoardCapabilityLevel.COLOR
            "occupancy" -> BoardCapabilityLevel.OCCUPANCY
            else -> return null
        }

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

        return ParsedBoardFile(level, squares)
    }

    private data class ParsedBoardFile(
        val level: BoardCapabilityLevel,
        val squares: Map<String, SquareState>,
    )
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
