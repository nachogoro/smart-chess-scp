package scp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import scp.orchestrator.GameSession
import scp.orchestrator.Orchestrator
import scp.orchestrator.OrchestratorState
import scp.protocol.*

@Composable
fun GameScreen(
    orchestrator: Orchestrator,
    uiModule: ComposeUIModule,
    boardFileManager: BoardFileManager,
    boardLevel: String,
    onGameEnd: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Board pieces state — initialize from starting position
    var pieces by remember { mutableStateOf(startingPosition()) }

    // Poll Orchestrator state (not Compose-observable) for display
    var orchestratorState by remember { mutableStateOf(orchestrator.state) }
    var gameId by remember { mutableStateOf(orchestrator.session?.gameId) }

    LaunchedEffect(Unit) {
        while (true) {
            orchestratorState = orchestrator.state
            gameId = orchestrator.session?.gameId
            kotlinx.coroutines.delay(100)
        }
    }

    // Game ended — driven by onGameEnded notification
    val gameSummary = uiModule.lastGameSummary
    if (gameSummary != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Game Over") },
            text = {
                Column {
                    Text("Result: ${gameSummary.result}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Reason: ${gameSummary.reason}", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    uiModule.clearGameSummary()
                    onGameEnd()
                }) { Text("OK") }
            },
        )
    }

    // Promotion dialog
    val pendingPromotion = uiModule.pendingPromotion
    if (pendingPromotion != null) {
        PromotionDialog(
            color = pendingPromotion.input.color,
            onSelect = { pendingPromotion.complete(it) },
            onDismiss = { pendingPromotion.cancel() },
        )
    }

    // Draw response dialog
    val pendingDrawResponse = uiModule.pendingDrawResponse
    if (pendingDrawResponse != null) {
        AlertDialog(
            onDismissRequest = { pendingDrawResponse.complete(false) },
            title = { Text("Draw Offered") },
            text = { Text("Your opponent offers a draw. Accept?") },
            confirmButton = {
                Button(onClick = { pendingDrawResponse.complete(true) }) { Text("Accept") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDrawResponse.complete(false) }) { Text("Decline") }
            },
        )
    }

    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Left panel — board
        ChessBoardWidget(
            pieces = pieces,
            onPieceMoved = { from, to ->
                val updated = pieces.toMutableMap()
                val piece = updated.remove(from) ?: return@ChessBoardWidget
                updated[to] = piece
                pieces = updated
                boardFileManager.write(pieces, boardLevel)
            },
            onPieceRemoved = { square ->
                val updated = pieces.toMutableMap()
                updated.remove(square)
                pieces = updated
                boardFileManager.write(pieces, boardLevel)
            },
            onPieceAdded = { square, piece ->
                val updated = pieces.toMutableMap()
                updated[square] = piece
                pieces = updated
                boardFileManager.write(pieces, boardLevel)
            },
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(16.dp))

        // Right panel — game info and controls
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Status
            Column(modifier = Modifier.weight(1f)) {
                val stateText = when (val s = orchestratorState) {
                    is OrchestratorState.Idle -> "Idle"
                    is OrchestratorState.SeekingGame -> "Seeking game..."
                    is OrchestratorState.AwaitingPlayerMove ->
                        "${if (s.color == Color.WHITE) "White" else "Black"} to move"
                    is OrchestratorState.AwaitingOpponentMove ->
                        "Waiting for ${if (s.color == Color.WHITE) "White" else "Black"}..."
                }
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(stateText, style = MaterialTheme.typography.bodyLarge)

                Spacer(Modifier.height(16.dp))

                // Clocks (if applicable)
                if (orchestrator.session?.clocks != null) {
                    ClocksDisplay(orchestrator, orchestratorState)
                    Spacer(Modifier.height(16.dp))
                }

                // Notification area
                NotificationArea(uiModule)
            }

            // Game controls
            Column {
                val currentGameId = gameId
                if (currentGameId != null) {
                    // Confirm move button
                    Button(
                        onClick = { scope.launch { uiModule.signalMoveReady(currentGameId) } },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Confirm Move")
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { uiModule.requestResign(currentGameId) } },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Resign", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { uiModule.requestDraw(currentGameId) } },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("Draw", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { uiModule.requestAbort(currentGameId) } },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("Abort", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClocksDisplay(orchestrator: Orchestrator, orchestratorState: OrchestratorState) {
    val session = orchestrator.session ?: return
    val baseClocks = session.clocks ?: return
    val activeTurn = when (orchestratorState) {
        is OrchestratorState.AwaitingPlayerMove -> orchestratorState.color
        is OrchestratorState.AwaitingOpponentMove -> orchestratorState.color
        else -> null
    }

    // Live countdown: tick down the active player's clock every 100ms
    var elapsed by remember { mutableStateOf(0L) }
    val turnStartTime = remember(baseClocks, activeTurn) { System.currentTimeMillis() }

    LaunchedEffect(baseClocks, activeTurn) {
        elapsed = 0L
        if (activeTurn != null) {
            while (true) {
                kotlinx.coroutines.delay(100)
                elapsed = System.currentTimeMillis() - turnStartTime
            }
        }
    }

    val whiteMs = if (activeTurn == Color.WHITE) (baseClocks.whiteMs - elapsed).coerceAtLeast(0) else baseClocks.whiteMs
    val blackMs = if (activeTurn == Color.BLACK) (baseClocks.blackMs - elapsed).coerceAtLeast(0) else baseClocks.blackMs

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ClockBox("White", whiteMs, isActive = activeTurn == Color.WHITE)
        ClockBox("Black", blackMs, isActive = activeTurn == Color.BLACK)
    }
}

@Composable
private fun ClockBox(label: String, timeMs: Long, isActive: Boolean) {
    val minutes = timeMs / 60_000
    val seconds = (timeMs % 60_000) / 1000

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Surface(
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "%d:%02d".format(minutes, seconds),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun NotificationArea(uiModule: ComposeUIModule) {
    Column {
        Text("Notifications", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                val opponentMove = uiModule.lastOpponentMove
                if (opponentMove != null) {
                    Text(
                        "Opponent played: ${opponentMove.san} (${opponentMove.move})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Update the board and confirm your move.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { uiModule.clearOpponentMove() }) {
                        Text("Dismiss")
                    }
                }

                val error = uiModule.lastError
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { uiModule.clearError() }) {
                        Text("Dismiss")
                    }
                }

                val desync = uiModule.desyncInfo
                if (desync != null) {
                    Text(
                        "Board mismatch: ${desync.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Expected: ${desync.expectedFen}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { uiModule.clearDesync() }) {
                        Text("Dismiss")
                    }
                }

                if (opponentMove == null && error == null && desync == null) {
                    Text(
                        "No notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromotionDialog(
    color: Color,
    onSelect: (PromotionPiece) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promote to:") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromotionPiece.entries.forEach { piece ->
                    val pieceType = when (piece) {
                        PromotionPiece.QUEEN -> scp.protocol.PieceType.QUEEN
                        PromotionPiece.ROOK -> scp.protocol.PieceType.ROOK
                        PromotionPiece.BISHOP -> scp.protocol.PieceType.BISHOP
                        PromotionPiece.KNIGHT -> scp.protocol.PieceType.KNIGHT
                    }
                    val boardPiece = BoardPiece(pieceType, color)
                    OutlinedButton(onClick = { onSelect(piece) }) {
                        Image(
                            painter = painterResource(PIECE_IMAGE_PATHS[boardPiece] ?: ""),
                            contentDescription = piece.name,
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

fun startingPosition(): Map<String, BoardPiece> {
    val pieces = mutableMapOf<String, BoardPiece>()
    val backRank = listOf(
        scp.protocol.PieceType.ROOK, scp.protocol.PieceType.KNIGHT, scp.protocol.PieceType.BISHOP,
        scp.protocol.PieceType.QUEEN, scp.protocol.PieceType.KING, scp.protocol.PieceType.BISHOP,
        scp.protocol.PieceType.KNIGHT, scp.protocol.PieceType.ROOK,
    )
    val files = listOf("a", "b", "c", "d", "e", "f", "g", "h")

    for ((i, file) in files.withIndex()) {
        pieces["${file}1"] = BoardPiece(backRank[i], Color.WHITE)
        pieces["${file}2"] = BoardPiece(scp.protocol.PieceType.PAWN, Color.WHITE)
        pieces["${file}7"] = BoardPiece(scp.protocol.PieceType.PAWN, Color.BLACK)
        pieces["${file}8"] = BoardPiece(backRank[i], Color.BLACK)
    }

    return pieces
}
