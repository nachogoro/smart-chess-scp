package scp.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scp.protocol.PieceType
import scp.protocol.Color as ChessColor

private val LIGHT_SQUARE = Color(0xFFF0D9B5)
private val DARK_SQUARE = Color(0xFFB58863)
private val BOARD_BORDER = Color(0xFF302E2B)

internal val PIECE_IMAGE_PATHS = mapOf(
    BoardPiece(PieceType.KING, ChessColor.WHITE) to "pieces/king-white.png",
    BoardPiece(PieceType.QUEEN, ChessColor.WHITE) to "pieces/queen-white.png",
    BoardPiece(PieceType.ROOK, ChessColor.WHITE) to "pieces/rook-white.png",
    BoardPiece(PieceType.BISHOP, ChessColor.WHITE) to "pieces/bishop-white.png",
    BoardPiece(PieceType.KNIGHT, ChessColor.WHITE) to "pieces/knight-white.png",
    BoardPiece(PieceType.PAWN, ChessColor.WHITE) to "pieces/pawn-white.png",
    BoardPiece(PieceType.KING, ChessColor.BLACK) to "pieces/king-black.png",
    BoardPiece(PieceType.QUEEN, ChessColor.BLACK) to "pieces/queen-black.png",
    BoardPiece(PieceType.ROOK, ChessColor.BLACK) to "pieces/rook-black.png",
    BoardPiece(PieceType.BISHOP, ChessColor.BLACK) to "pieces/bishop-black.png",
    BoardPiece(PieceType.KNIGHT, ChessColor.BLACK) to "pieces/knight-black.png",
    BoardPiece(PieceType.PAWN, ChessColor.BLACK) to "pieces/pawn-black.png",
)

data class BoardPiece(
    val type: PieceType,
    val color: ChessColor,
)

@Composable
private fun loadPieceImages(): Map<BoardPiece, ImageBitmap> {
    return remember {
        PIECE_IMAGE_PATHS.mapValues { (_, path) ->
            useResource(path) { loadImageBitmap(it) }
        }
    }
}

@Composable
fun ChessBoardWidget(
    pieces: Map<String, BoardPiece>,
    onPieceMoved: (from: String, to: String) -> Unit,
    onPieceRemoved: (square: String) -> Unit,
    onPieceAdded: (square: String, piece: BoardPiece) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = listOf("a", "b", "c", "d", "e", "f", "g", "h")
    val ranks = listOf("8", "7", "6", "5", "4", "3", "2", "1")
    val pieceImages = loadPieceImages()

    var dragState by remember { mutableStateOf<DragState?>(null) }
    var selectedTrayPiece by remember { mutableStateOf<BoardPiece?>(null) }

    Row(modifier = modifier) {
        // White piece tray (left side)
        PieceTray(
            color = ChessColor.WHITE,
            selectedPiece = selectedTrayPiece,
            onPieceSelected = { piece ->
                selectedTrayPiece = if (selectedTrayPiece == piece) null else piece
            },
        )

        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Board area with labels
            Row {
                // Rank labels (left)
                Column(
                    modifier = Modifier.width(20.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Spacer(Modifier.height(2.dp))
                    ranks.forEach { rank ->
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(rank, fontSize = 12.sp, color = BOARD_BORDER)
                        }
                    }
                }

                // The actual board
                BoxWithConstraints(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f, fill = false)
                ) {
                    val boardSize = maxWidth

                    Box(
                        modifier = Modifier
                            .size(boardSize)
                            .background(BOARD_BORDER)
                            .pointerInput(pieces, selectedTrayPiece) {
                                detectTapGestures { offset ->
                                    val col = (offset.x / (size.width / 8)).toInt().coerceIn(0, 7)
                                    val row = (offset.y / (size.height / 8)).toInt().coerceIn(0, 7)
                                    val square = "${files[col]}${ranks[row]}"
                                    val trayPiece = selectedTrayPiece
                                    if (trayPiece != null) {
                                        onPieceAdded(square, trayPiece)
                                        selectedTrayPiece = null
                                    }
                                }
                            }
                            .pointerInput(pieces) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        // Cancel any tray selection when dragging
                                        selectedTrayPiece = null
                                        val col = (offset.x / (size.width / 8)).toInt().coerceIn(0, 7)
                                        val row = (offset.y / (size.height / 8)).toInt().coerceIn(0, 7)
                                        val square = "${files[col]}${ranks[row]}"
                                        val piece = pieces[square]
                                        if (piece != null) {
                                            dragState = DragState(
                                                piece = piece,
                                                source = DragSource.Square(square),
                                                currentOffset = offset,
                                            )
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragState = dragState?.copy(
                                            currentOffset = dragState!!.currentOffset + dragAmount,
                                        )
                                    },
                                    onDragEnd = {
                                        val state = dragState ?: return@detectDragGestures
                                        val col = (state.currentOffset.x / (size.width / 8)).toInt()
                                        val row = (state.currentOffset.y / (size.height / 8)).toInt()

                                        if (col in 0..7 && row in 0..7) {
                                            val targetSquare = "${files[col]}${ranks[row]}"
                                            when (val source = state.source) {
                                                is DragSource.Square -> {
                                                    if (source.square != targetSquare) {
                                                        onPieceMoved(source.square, targetSquare)
                                                    }
                                                }
                                                is DragSource.Tray -> {
                                                    onPieceAdded(targetSquare, state.piece)
                                                }
                                            }
                                        } else {
                                            // Dropped outside the board — remove
                                            when (val source = state.source) {
                                                is DragSource.Square -> onPieceRemoved(source.square)
                                                is DragSource.Tray -> {} // Nothing to remove
                                            }
                                        }
                                        dragState = null
                                    },
                                    onDragCancel = { dragState = null },
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sqSize = size.width / 8

                            // Draw squares
                            for (row in 0..7) {
                                for (col in 0..7) {
                                    val isLight = (row + col) % 2 == 0
                                    drawRect(
                                        color = if (isLight) LIGHT_SQUARE else DARK_SQUARE,
                                        topLeft = Offset(col * sqSize, row * sqSize),
                                        size = Size(sqSize, sqSize),
                                    )
                                }
                            }

                            // Draw pieces (skip the one being dragged)
                            val draggedSquare = (dragState?.source as? DragSource.Square)?.square
                            for (row in 0..7) {
                                for (col in 0..7) {
                                    val square = "${files[col]}${ranks[row]}"
                                    if (square == draggedSquare) continue
                                    val piece = pieces[square] ?: continue
                                    val image = pieceImages[piece] ?: continue
                                    drawPieceImage(image, col, row, sqSize)
                                }
                            }

                            // Draw dragged piece at cursor position
                            val drag = dragState
                            if (drag != null) {
                                val image = pieceImages[drag.piece]
                                if (image != null) {
                                    drawPieceImageAt(
                                        image,
                                        drag.currentOffset - Offset(sqSize / 2, sqSize / 2),
                                        sqSize,
                                    )
                                }
                            }
                        }
                    }
                }

                // Rank labels (right)
                Column(
                    modifier = Modifier.width(20.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Spacer(Modifier.height(2.dp))
                    ranks.forEach { rank ->
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(rank, fontSize = 12.sp, color = BOARD_BORDER)
                        }
                    }
                }
            }

            // File labels (bottom)
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                files.forEach { file ->
                    Box(
                        modifier = Modifier.weight(1f).height(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(file, fontSize = 12.sp, color = BOARD_BORDER)
                    }
                }
            }
        }

        Spacer(Modifier.width(4.dp))

        // Black piece tray (right side)
        PieceTray(
            color = ChessColor.BLACK,
            selectedPiece = selectedTrayPiece,
            onPieceSelected = { piece ->
                selectedTrayPiece = if (selectedTrayPiece == piece) null else piece
            },
        )
    }
}

@Composable
private fun PieceTray(
    color: ChessColor,
    selectedPiece: BoardPiece?,
    onPieceSelected: (BoardPiece) -> Unit,
) {
    val trayPieces = listOf(
        PieceType.KING, PieceType.QUEEN, PieceType.ROOK,
        PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN,
    )

    Column(
        modifier = Modifier
            .width(56.dp)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val label = if (color == ChessColor.WHITE) "White" else "Black"
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        trayPieces.forEach { type ->
            val piece = BoardPiece(type, color)
            val isSelected = selectedPiece == piece
            val resourcePath = PIECE_IMAGE_PATHS[piece]
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable { onPieceSelected(piece) },
                contentAlignment = Alignment.Center,
            ) {
                if (resourcePath != null) {
                    Image(
                        painter = painterResource(resourcePath),
                        contentDescription = "$color $type",
                        modifier = Modifier.size(40.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPieceImage(
    image: ImageBitmap,
    col: Int,
    row: Int,
    sqSize: Float,
) {
    drawPieceImageAt(image, Offset(col * sqSize, row * sqSize), sqSize)
}

private fun DrawScope.drawPieceImageAt(
    image: ImageBitmap,
    topLeft: Offset,
    sqSize: Float,
) {
    val padding = sqSize * 0.10f
    val drawSize = sqSize - padding * 2
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            (topLeft.x + padding).toInt(),
            (topLeft.y + padding).toInt(),
        ),
        dstSize = IntSize(drawSize.toInt(), drawSize.toInt()),
    )
}

private data class DragState(
    val piece: BoardPiece,
    val source: DragSource,
    val currentOffset: Offset,
)

private sealed class DragSource {
    data class Square(val square: String) : DragSource()
    data object Tray : DragSource()
}
