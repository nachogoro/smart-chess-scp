package scp.game

import com.nachogoro.simplechess.DrawEnforcement
import com.nachogoro.simplechess.DrawReason
import com.nachogoro.simplechess.GameState as ChessGameState
import com.nachogoro.simplechess.Game as ChessGame
import com.nachogoro.simplechess.PieceType as ChessPieceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import scp.protocol.*
import java.time.Instant
import java.util.UUID

class GameController(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = SystemTimeSource(),
) : GameControllerModule {

    private val games = mutableMapOf<String, InternalGame>()

    private val _gameEnded = MutableSharedFlow<GameEndedEvent>(replay = 1)
    override val gameEnded: SharedFlow<GameEndedEvent> = _gameEnded

    private val _flagFall = MutableSharedFlow<FlagFallEvent>(replay = 1)
    override val flagFall: SharedFlow<FlagFallEvent> = _flagFall

    override suspend fun create(params: CreateGameParams): Result<GameError, CreateGameResult> {
        val drawEnforcement = when (params.authority) {
            Authority.LOCAL -> DrawEnforcement.AUTOMATIC
            Authority.EXTERNAL -> DrawEnforcement.CLAIM_ONLY
        }

        val fen = params.fen
        val chessResult = if (fen != null) {
            ChessGame.fromFen(fen, drawEnforcement)
        } else {
            ChessGame.newGame(drawEnforcement)
        }

        val chessGame = chessResult.getOrNull()
            ?: return Result.Failure(GameError.InvalidFen(fen))

        val id = UUID.randomUUID().toString()
        val game = InternalGame(
            id = id,
            chessGame = chessGame,
            authority = params.authority,
            timeControl = params.timeControl,
        )

        games[id] = game

        if (params.authority == Authority.LOCAL && params.timeControl != null) {
            game.turnStartedAt = timeSource.currentTimeMs()
            startFlagFallTimer(game)
        }

        return Result.Success(CreateGameResult(id))
    }

    override suspend fun getState(gameId: String): Result<GameError, GameState> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        return Result.Success(game.toGameState())
    }

    override suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult> {
        val game = games[params.gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        // Clear any pending draw offer on move submission
        if (game.status == GameStatus.DRAW_OFFERED) {
            game.drawOffer = null
            game.status = GameStatus.IN_PROGRESS
        }

        val legalMove = parseUciMove(params.move, game.chessGame)
            ?: return Result.Failure(GameError.IllegalMove(params.move))

        // Determined before the move is applied: afterwards the vacated square is indistinguishable
        // from one that never held a piece. A pawn only ever leaves its file to capture, so a
        // diagonal pawn move onto an empty square is en passant and nothing else.
        val isEnPassant = legalMove.piece.type == ChessPieceType.PAWN
            && legalMove.from.file != legalMove.to.file
            && game.chessGame.currentPosition.board[legalMove.to] == null

        val moveResult = game.chessGame.makeMove(legalMove)
        val gameAfterMove = moveResult.getOrNull()
            ?: return Result.Failure(GameError.IllegalMove(params.move))

        // Get SAN from the last played move in the new game's history
        val playedMove = gameAfterMove.history.last().second
        val san = playedMove.algebraicNotation

        // Update clocks
        val movingColor = game.turn
        val newClocks = computeNewClocks(game, params, movingColor)

        game.chessGame = gameAfterMove
        game.clocks = newClocks

        // Determine new status
        val newStatus = determineStatusAfterMove(gameAfterMove, game.authority)
        game.status = newStatus

        // Record history
        game.moveHistory.add(
            GameHistoryMove(
                move = params.move,
                san = san,
                fen = gameAfterMove.currentPosition.fen,
                whiteMs = newClocks?.whiteMs,
                blackMs = newClocks?.blackMs,
                timestamp = Instant.now().toString(),
            )
        )

        // Handle terminal status
        if (game.isOver) {
            game.timerJob?.cancel()
            game.result = determineResult(newStatus, gameAfterMove)
            emitGameEnded(game, terminationFor(newStatus, gameAfterMove))
        } else if (game.authority == Authority.LOCAL && game.timeControl != null) {
            // Restart timer for next player
            game.turnStartedAt = timeSource.currentTimeMs()
            startFlagFallTimer(game)
        }

        return Result.Success(
            SubmitMoveResult(
                fen = gameAfterMove.currentPosition.fen,
                san = san,
                status = game.status,
                clocks = game.clocks,
                isEnPassant = isEnPassant,
            )
        )
    }

    override suspend fun resign(gameId: String, color: Color): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.timerJob?.cancel()
        game.status = GameStatus.RESIGNED
        game.result = when (color) {
            Color.WHITE -> GameResult.BLACK_WINS
            Color.BLACK -> GameResult.WHITE_WINS
        }

        emitGameEnded(game, Termination.Resignation)

        return Result.Success(Unit)
    }

    override suspend fun offerDraw(gameId: String, color: Color): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.drawOffer = color
        game.status = GameStatus.DRAW_OFFERED

        return Result.Success(Unit)
    }

    override suspend fun respondDraw(gameId: String, accept: Boolean): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        if (game.status != GameStatus.DRAW_OFFERED) {
            return Result.Failure(GameError.NoDrawOffer())
        }

        if (accept) {
            game.timerJob?.cancel()
            game.status = GameStatus.DRAW
            game.result = GameResult.DRAW
            game.drawOffer = null
            emitGameEnded(game, Termination.Drawn(DrawKind.Agreement))
        } else {
            game.drawOffer = null
            game.status = GameStatus.IN_PROGRESS
        }

        return Result.Success(Unit)
    }

    override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        if (game.authority == Authority.EXTERNAL) {
            return Result.Failure(GameError.ExternalAuthority())
        }

        val chessGame = game.chessGame
        if (!chessGame.canClaimDraw) {
            return Result.Failure(GameError.InvalidClaim())
        }

        val claimReason = chessGame.drawClaimReason
        val drawResult = chessGame.claimDraw()
        val newChessGame = drawResult.getOrNull()
            ?: return Result.Failure(GameError.InvalidClaim())

        game.timerJob?.cancel()
        game.chessGame = newChessGame
        game.status = GameStatus.DRAW
        game.result = GameResult.DRAW

        emitGameEnded(game, Termination.Drawn(drawKindOf(claimReason)))

        return Result.Success(Unit)
    }

    override suspend fun abort(gameId: String): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.timerJob?.cancel()
        game.status = GameStatus.ABORTED
        game.result = GameResult.IN_PROGRESS

        emitGameEnded(game, Termination.Aborted)

        return Result.Success(Unit)
    }

    override suspend fun getHistory(gameId: String): Result<GameError, GameHistory> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        return Result.Success(GameHistory(game.moveHistory.toList()))
    }

    override suspend fun setResult(params: SetResultParams): Result<GameError, Unit> {
        val game = games[params.gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        if (game.authority != Authority.EXTERNAL) {
            return Result.Failure(GameError.InvalidAuthority())
        }

        game.timerJob?.cancel()
        game.result = params.result
        game.status = statusFrom(params.termination, params.result)

        emitGameEnded(game, params.termination)

        return Result.Success(Unit)
    }

    private fun computeNewClocks(
        game: InternalGame,
        params: SubmitMoveParams,
        movingColor: Color,
    ): Clocks? {
        // External clock values take priority
        val extWhite = params.whiteMs
        val extBlack = params.blackMs
        if (extWhite != null && extBlack != null) {
            return Clocks(extWhite, extBlack)
        }

        // In external authority mode, clocks only update when explicitly provided
        if (game.authority == Authority.EXTERNAL) {
            return game.clocks
        }

        // Local authority: compute from elapsed time
        val clocks = game.clocks ?: return null
        val timeControl = game.timeControl ?: return clocks
        val elapsed = timeSource.currentTimeMs() - game.turnStartedAt

        return computeClockAfterMove(clocks, timeControl, elapsed, movingColor)
    }

    private fun determineStatusAfterMove(
        chessGame: ChessGame,
        authority: Authority,
    ): GameStatus {
        return when (chessGame.gameState) {
            ChessGameState.WHITE_WON, ChessGameState.BLACK_WON -> GameStatus.CHECKMATE
            ChessGameState.DRAWN -> {
                val drawReason = chessGame.drawReason
                when {
                    drawReason == DrawReason.STALEMATE -> GameStatus.STALEMATE
                    // In external mode, only checkmate/stalemate are terminal from submitMove
                    authority == Authority.EXTERNAL -> GameStatus.IN_PROGRESS
                    else -> GameStatus.DRAW
                }
            }
            ChessGameState.PLAYING -> {
                if (chessGame.history.isEmpty()) GameStatus.NOT_STARTED
                else GameStatus.IN_PROGRESS
            }
        }
    }

    private fun determineResult(
        status: GameStatus,
        chessGame: ChessGame,
    ): GameResult {
        return when (status) {
            GameStatus.CHECKMATE -> when (chessGame.gameState) {
                ChessGameState.WHITE_WON -> GameResult.WHITE_WINS
                ChessGameState.BLACK_WON -> GameResult.BLACK_WINS
                else -> GameResult.IN_PROGRESS
            }
            GameStatus.STALEMATE, GameStatus.DRAW -> GameResult.DRAW
            else -> GameResult.IN_PROGRESS
        }
    }

    private fun terminationFor(
        status: GameStatus,
        chessGame: ChessGame,
    ): Termination = when (status) {
        GameStatus.CHECKMATE -> Termination.Checkmate
        GameStatus.STALEMATE -> Termination.Stalemate
        // The status says the game was drawn; only the rules engine knows under which rule.
        GameStatus.DRAW -> Termination.Drawn(drawKindOf(chessGame.drawReason))
        else -> Termination.Other()
    }

    private fun drawKindOf(reason: DrawReason?): DrawKind = when (reason) {
        DrawReason.OFFERED_AND_ACCEPTED -> DrawKind.Agreement
        DrawReason.INSUFFICIENT_MATERIAL,
        DrawReason.OPPONENT_INSUFFICIENT_MATERIAL -> DrawKind.InsufficientMaterial
        DrawReason.FIFTY_MOVE_RULE -> DrawKind.FiftyMoveRule
        DrawReason.SEVENTY_FIVE_MOVE_RULE -> DrawKind.SeventyFiveMoveRule
        DrawReason.THREE_FOLD_REPETITION -> DrawKind.ThreefoldRepetition
        DrawReason.FIVE_FOLD_REPETITION -> DrawKind.FivefoldRepetition
        // A rule the library knows and this vocabulary does not: still a draw, kind unnamed.
        else -> DrawKind.Other()
    }

    /**
     * The status an external authority's ending puts the game into.
     *
     * Total, and decided by the termination rather than by the wording of it. The exception is
     * [Termination.Other], where the authority reported an ending this protocol does not name and
     * the result is genuinely all there is to go on.
     */
    private fun statusFrom(termination: Termination, result: GameResult): GameStatus =
        when (termination) {
            Termination.Checkmate -> GameStatus.CHECKMATE
            Termination.Stalemate -> GameStatus.STALEMATE
            Termination.Resignation -> GameStatus.RESIGNED
            Termination.Timeout -> GameStatus.TIMEOUT
            Termination.Aborted -> GameStatus.ABORTED
            is Termination.Drawn -> GameStatus.DRAW
            is Termination.Other -> when (result) {
                GameResult.WHITE_WINS, GameResult.BLACK_WINS -> GameStatus.RESIGNED
                GameResult.DRAW, GameResult.IN_PROGRESS -> GameStatus.DRAW
            }
        }

    private fun startFlagFallTimer(game: InternalGame) {
        game.timerJob?.cancel()

        val clocks = game.clocks ?: return
        val remaining = when (game.turn) {
            Color.WHITE -> clocks.whiteMs
            Color.BLACK -> clocks.blackMs
        }

        if (remaining <= 0) return

        val color = game.turn
        val gameId = game.id

        game.timerJob = scope.launch {
            delay(remaining)
            // After delay, check if the game is still active and it's still this player's turn
            val currentGame = games[gameId] ?: return@launch
            if (currentGame.isOver) return@launch
            if (currentGame.turn != color) return@launch

            // Flag fall
            _flagFall.emit(FlagFallEvent(gameId = gameId, color = color))

            // Update clocks to reflect timeout
            currentGame.clocks = when (color) {
                Color.WHITE -> currentGame.clocks?.copy(whiteMs = 0)
                Color.BLACK -> currentGame.clocks?.copy(blackMs = 0)
            }

            // FIDE 6.9: if the opponent has insufficient mating material, it's a draw
            val claimReason = currentGame.chessGame.drawClaimReason
            val insufficientMaterial =
                claimReason == DrawReason.INSUFFICIENT_MATERIAL
                    || claimReason == DrawReason.OPPONENT_INSUFFICIENT_MATERIAL

            if (insufficientMaterial) {
                currentGame.status = GameStatus.TIMEOUT
                currentGame.result = GameResult.DRAW
                emitGameEnded(currentGame, Termination.Drawn(DrawKind.InsufficientMaterial))
            } else {
                currentGame.status = GameStatus.TIMEOUT
                currentGame.result = when (color) {
                    Color.WHITE -> GameResult.BLACK_WINS
                    Color.BLACK -> GameResult.WHITE_WINS
                }
                emitGameEnded(currentGame, Termination.Timeout)
            }
        }
    }

    private suspend fun emitGameEnded(game: InternalGame, termination: Termination) {
        _gameEnded.emit(
            GameEndedEvent(
                gameId = game.id,
                result = game.result,
                termination = termination,
                fen = game.fen,
            )
        )
    }

    private fun InternalGame.toGameState() = GameState(
        gameId = id,
        fen = fen,
        moves = uciMoves,
        status = status,
        turn = turn,
        clocks = clocks,
        result = result,
    )
}
