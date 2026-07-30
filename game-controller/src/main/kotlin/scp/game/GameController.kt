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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import scp.protocol.*
import java.time.Instant
import java.util.UUID

class GameController(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = SystemTimeSource(),
) : GameControllerModule {

    /**
     * Games by id, in the order they were created.
     *
     * Guarded by [mutex]: the flag-fall timers run on [scope], so this map and the games in it are
     * reached from the caller's coroutine and from a timer's at the same time, and nothing says
     * the scope is single-threaded. The timers do check-then-act on state a move may be changing
     * underneath them, which is exactly what a lock is for.
     *
     * Bounded, because a game is never finished with from this end: a caller reads the history and
     * the final position after the ending, so they cannot be dropped the moment they end. Finished
     * games are evicted oldest-first once there are more than [MAX_RETAINED_GAMES] of them, which
     * keeps a long session from accumulating every game it ever played, each holding a native
     * handle and its whole move list.
     */
    private val games = LinkedHashMap<String, InternalGame>()

    private val mutex = Mutex()

    private val _gameEnded = MutableSharedFlow<GameEndedEvent>(replay = 1)
    override val gameEnded: SharedFlow<GameEndedEvent> = _gameEnded

    private val _flagFall = MutableSharedFlow<FlagFallEvent>(replay = 1)
    override val flagFall: SharedFlow<FlagFallEvent> = _flagFall

    override suspend fun create(params: CreateGameParams): Result<GameError, CreateGameResult> = mutex.withLock {
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

        evictOldFinishedGames()
        games[id] = game

        if (params.authority == Authority.LOCAL && params.timeControl != null) {
            game.turnStartedAt = timeSource.currentTimeMs()
            startFlagFallTimer(game)
        }

        return Result.Success(CreateGameResult(id))
    }

    override suspend fun getState(gameId: String): Result<GameError, GameState> = mutex.withLock {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        return Result.Success(game.toGameState())
    }

    override suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult> {
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { submitMoveLocked(params, pending) }
        publish(pending)
        return result
    }

    private fun submitMoveLocked(
        params: SubmitMoveParams,
        pending: MutableList<Any>,
    ): Result<GameError, SubmitMoveResult> {
        val game = games[params.gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        // The flag may have fallen while this move was being made. The timer that reports it is a
        // coroutine, and nothing guarantees it resumed before the move arrived - so the clock
        // decides, not whether a timer happened to fire first. Without this a player whose time
        // had run out could keep moving indefinitely, because each move cancels and re-arms the
        // timer and a clock already at zero never arms one at all.
        flagFallBeforeMove(game)?.let { flagged ->
            pending += FlagFallEvent(gameId = game.id, color = flagged)
            pending += endOnTime(game, flagged)
            return Result.Failure(GameError.GameOver())
        }

        // Its own code rather than ILLEGAL_MOVE. Both refuse the move, but only one of them is
        // something the player can do anything about, and a UI cannot tell them apart from the
        // rules engine's answer alone: it only ever offers moves for the side to move, so a move
        // by the wrong side looks exactly like one that is not legal.
        if (movesWrongSide(game.chessGame, params.move)) {
            return Result.Failure(GameError.NotYourTurn())
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

        // A move answers a draw offer by ignoring it - but only a move that was actually played.
        // Withdrawing the offer up front meant a move the rules engine went on to refuse, which
        // here means any misread of the physical board, silently took the opponent's offer with
        // it. The status is set from the new position just below, so clearing who offered is all
        // that is left to do.
        game.drawOffer = null

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
            game.result = determineResult(gameAfterMove)
            pending += endedEvent(game, terminationFor(gameAfterMove))
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
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { resignLocked(gameId, color, pending) }
        publish(pending)
        return result
    }

    private fun resignLocked(
        gameId: String,
        color: Color,
        pending: MutableList<Any>,
    ): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.timerJob?.cancel()
        game.status = GameStatus.FINISHED
        game.result = when (color) {
            Color.WHITE -> GameResult.BLACK_WINS
            Color.BLACK -> GameResult.WHITE_WINS
        }

        pending += endedEvent(game, Termination.Resignation)

        return Result.Success(Unit)
    }

    override suspend fun offerDraw(gameId: String, color: Color): Result<GameError, Unit> = mutex.withLock {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.drawOffer = color
        game.status = GameStatus.DRAW_OFFERED

        return Result.Success(Unit)
    }

    override suspend fun respondDraw(gameId: String, color: Color, accept: Boolean): Result<GameError, Unit> {
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { respondDrawLocked(gameId, color, accept, pending) }
        publish(pending)
        return result
    }

    private fun respondDrawLocked(
        gameId: String,
        color: Color,
        accept: Boolean,
        pending: MutableList<Any>,
    ): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        if (game.status != GameStatus.DRAW_OFFERED) {
            return Result.Failure(GameError.NoDrawOffer())
        }

        // A draw takes two players. This call used to carry no colour at all, so nothing here
        // could tell agreement from a player answering their own offer - and in a remote game,
        // where the offer is left standing while the server is asked, a second tap of Draw did
        // exactly that and ended the game.
        if (game.drawOffer == color) {
            return Result.Failure(GameError.OwnDrawOffer())
        }

        if (accept) {
            game.timerJob?.cancel()
            game.status = GameStatus.FINISHED
            game.result = GameResult.DRAW
            game.drawOffer = null
            pending += endedEvent(game, Termination.Agreement)
        } else {
            game.drawOffer = null
            game.status = GameStatus.IN_PROGRESS
        }

        return Result.Success(Unit)
    }

    override suspend fun claimDraw(gameId: String): Result<GameError, Unit> {
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { claimDrawLocked(gameId, pending) }
        publish(pending)
        return result
    }

    private fun claimDrawLocked(gameId: String, pending: MutableList<Any>): Result<GameError, Unit> {
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
        game.status = GameStatus.FINISHED
        game.result = GameResult.DRAW

        pending += endedEvent(game, terminationForDraw(claimReason))

        return Result.Success(Unit)
    }

    override suspend fun abort(gameId: String): Result<GameError, Unit> {
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { abortLocked(gameId, pending) }
        publish(pending)
        return result
    }

    private fun abortLocked(gameId: String, pending: MutableList<Any>): Result<GameError, Unit> {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        game.timerJob?.cancel()
        game.status = GameStatus.FINISHED
        game.result = GameResult.IN_PROGRESS

        pending += endedEvent(game, Termination.Aborted)

        return Result.Success(Unit)
    }

    override suspend fun getHistory(gameId: String): Result<GameError, GameHistory> = mutex.withLock {
        val game = games[gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        return Result.Success(GameHistory(game.moveHistory.toList()))
    }

    override suspend fun setResult(params: SetResultParams): Result<GameError, Unit> {
        val pending = mutableListOf<Any>()
        val result = mutex.withLock { setResultLocked(params, pending) }
        publish(pending)
        return result
    }

    private fun setResultLocked(params: SetResultParams, pending: MutableList<Any>): Result<GameError, Unit> {
        val game = games[params.gameId]
            ?: return Result.Failure(GameError.GameNotFound())

        if (game.isOver) return Result.Failure(GameError.GameOver())

        if (game.authority != Authority.EXTERNAL) {
            return Result.Failure(GameError.InvalidAuthority())
        }

        game.timerJob?.cancel()
        game.result = params.result
        game.status = GameStatus.FINISHED

        pending += endedEvent(game, params.termination)

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
            ChessGameState.WHITE_WON, ChessGameState.BLACK_WON -> GameStatus.FINISHED
            ChessGameState.DRAWN -> when {
                chessGame.drawReason == DrawReason.STALEMATE -> GameStatus.FINISHED
                // In external mode, only checkmate and stalemate are terminal from submitMove
                authority == Authority.EXTERNAL -> GameStatus.IN_PROGRESS
                else -> GameStatus.FINISHED
            }
            ChessGameState.PLAYING -> GameStatus.IN_PROGRESS
        }
    }

    private fun determineResult(chessGame: ChessGame): GameResult = when (chessGame.gameState) {
        ChessGameState.WHITE_WON -> GameResult.WHITE_WINS
        ChessGameState.BLACK_WON -> GameResult.BLACK_WINS
        ChessGameState.DRAWN -> GameResult.DRAW
        ChessGameState.PLAYING -> GameResult.IN_PROGRESS
    }

    private fun terminationFor(chessGame: ChessGame): Termination = when (chessGame.gameState) {
        ChessGameState.WHITE_WON, ChessGameState.BLACK_WON -> Termination.Checkmate
        ChessGameState.DRAWN -> terminationForDraw(chessGame.drawReason)
        ChessGameState.PLAYING -> Termination.Other()
    }

    /**
     * The rule the library drew a game under, as a cause.
     *
     * Siblings of checkmate and resignation rather than variants of a `DRAWN` umbrella: each of
     * these is a reason a game stopped, and that the game was drawn is already in its result.
     */
    private fun terminationForDraw(reason: DrawReason?): Termination = when (reason) {
        DrawReason.STALEMATE -> Termination.Stalemate
        DrawReason.OFFERED_AND_ACCEPTED -> Termination.Agreement
        DrawReason.INSUFFICIENT_MATERIAL,
        DrawReason.OPPONENT_INSUFFICIENT_MATERIAL -> Termination.InsufficientMaterial
        DrawReason.FIFTY_MOVE_RULE -> Termination.FiftyMoveRule
        DrawReason.SEVENTY_FIVE_MOVE_RULE -> Termination.SeventyFiveMoveRule
        DrawReason.THREE_FOLD_REPETITION -> Termination.ThreefoldRepetition
        DrawReason.FIVE_FOLD_REPETITION -> Termination.FivefoldRepetition
        // A rule the library knows and this vocabulary does not. The result still says it was
        // drawn, so only the reason is lost.
        else -> Termination.Other()
    }

    /**
     * The colour whose clock has already run out, if the side to move has no time left.
     *
     * Only meaningful under [Authority.LOCAL] with a time control: in `external` mode the remote
     * server is the timekeeper and this controller must not decide a game on time (§4.2).
     */
    private fun flagFallBeforeMove(game: InternalGame): Color? {
        if (game.authority != Authority.LOCAL) return null
        val clocks = game.clocks ?: return null
        if (game.timeControl == null) return null

        val mover = game.turn
        val remaining = when (mover) {
            Color.WHITE -> clocks.whiteMs
            Color.BLACK -> clocks.blackMs
        }
        val elapsed = timeSource.currentTimeMs() - game.turnStartedAt
        return if (elapsed >= remaining) mover else null
    }

    /**
     * Ends [game] because [color] ran out of time.
     *
     * FIDE 6.9: the player whose flag fell loses, unless their opponent cannot checkmate with the
     * material left, in which case the game is drawn. `drawClaimReason` is evaluated for the side
     * to move - which at a flag fall is the flagged player - so `OpponentInsufficientMaterial`
     * answers exactly that question.
     */
    private fun endOnTime(game: InternalGame, color: Color): GameEndedEvent {
        game.timerJob?.cancel()

        game.clocks = when (color) {
            Color.WHITE -> game.clocks?.copy(whiteMs = 0)
            Color.BLACK -> game.clocks?.copy(blackMs = 0)
        }

        val claimReason = game.chessGame.drawClaimReason
        val opponentCannotMate = claimReason == DrawReason.INSUFFICIENT_MATERIAL
            || claimReason == DrawReason.OPPONENT_INSUFFICIENT_MATERIAL

        game.status = GameStatus.FINISHED
        // The cause is the clock either way. Whether that cost the player the game or only drew
        // it is the result's to say, which is why nothing here has to choose between the two.
        game.result = if (opponentCannotMate) {
            GameResult.DRAW
        } else {
            when (color) {
                Color.WHITE -> GameResult.BLACK_WINS
                Color.BLACK -> GameResult.WHITE_WINS
            }
        }
        return endedEvent(game, Termination.Timeout)
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
            // Whether the flag has really fallen is a question about state a move may be changing
            // right now, so deciding it and acting on it happen without letting go of the lock.
            val pending = mutex.withLock {
                val currentGame = games[gameId] ?: return@withLock emptyList()
                if (currentGame.isOver) return@withLock emptyList()
                if (currentGame.turn != color) return@withLock emptyList()
                listOf(FlagFallEvent(gameId = gameId, color = color), endOnTime(currentGame, color))
            }
            publish(pending)
        }
    }

    private fun endedEvent(game: InternalGame, termination: Termination) = GameEndedEvent(
        gameId = game.id,
        result = game.result,
        termination = termination,
        fen = game.fen,
    )

    /**
     * Publishes what a call decided, once its lock is released.
     *
     * Emitting under the lock would hand a collector the power to stall every other caller for as
     * long as it took to handle the event, and a collector that called back in would deadlock.
     */
    private suspend fun publish(events: List<Any>) {
        for (event in events) {
            when (event) {
                is FlagFallEvent -> _flagFall.emit(event)
                is GameEndedEvent -> _gameEnded.emit(event)
            }
        }
    }

    /** Drops the oldest finished games once too many have accumulated. A live game is never evicted. */
    private fun evictOldFinishedGames() {
        val excess = games.size - MAX_RETAINED_GAMES
        if (excess <= 0) return
        games.entries
            .filter { it.value.isOver }
            .take(excess)
            .forEach { games.remove(it.key) }
    }

    private companion object {
        /** Enough for a session's worth of review, few enough to stay bounded. */
        const val MAX_RETAINED_GAMES = 16
    }

    private fun InternalGame.toGameState() = GameState(
        gameId = id,
        fen = fen,
        moves = uciMoves,
        status = status,
        turn = turn,
        clocks = clocks,
        result = result,
        drawOffer = drawOffer,
    )
}
