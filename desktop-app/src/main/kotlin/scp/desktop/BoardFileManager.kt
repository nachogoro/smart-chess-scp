package scp.desktop

import scp.protocol.PieceType
import scp.protocol.Color as ChessColor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BoardFileManager(val filePath: Path) {

    init {
        filePath.parent.createDirectories()
    }

    fun write(pieces: Map<String, BoardPiece>, level: String) {
        val sb = StringBuilder()
        sb.appendLine(level)
        for ((square, piece) in pieces.entries.sortedBy { it.key }) {
            when (level) {
                "full" -> {
                    val color = if (piece.color == ChessColor.WHITE) "w" else "b"
                    val type = pieceTypeChar(piece.type)
                    sb.appendLine("$square $color$type")
                }
                "color" -> {
                    val color = if (piece.color == ChessColor.WHITE) "w" else "b"
                    sb.appendLine("$square $color")
                }
                "occupancy" -> {
                    sb.appendLine(square)
                }
            }
        }
        filePath.writeText(sb.toString())
    }

    private fun pieceTypeChar(type: PieceType): Char = when (type) {
        PieceType.PAWN -> 'P'
        PieceType.KNIGHT -> 'N'
        PieceType.BISHOP -> 'B'
        PieceType.ROOK -> 'R'
        PieceType.QUEEN -> 'Q'
        PieceType.KING -> 'K'
    }
}
