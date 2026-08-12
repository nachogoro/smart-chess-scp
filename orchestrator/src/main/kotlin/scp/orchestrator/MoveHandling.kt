package scp.orchestrator

import scp.protocol.*
import kotlinx.coroutines.launch
import scp.protocol.debugLog

internal suspend fun Orchestrator.handleMoveReady() {
    val s = session ?: return
    val gameState = gameController.getState(s.gameId).getOrNull() ?: return

    val boardState = when (val read = board.getState()) {
        is Result.Failure -> {
            ui.displayError(UIMessage.BoardReadFailed(read.error))
            return
        }
        is Result.Success -> read.value
    }

    val outcome = inferMove(gameState.fen, boardState, s.boardLevel)

    val uci = when (outcome) {
        is InferenceOutcome.SingleMove -> outcome.uci
        is InferenceOutcome.NeedsPromotion -> {
            val piece = ui.promptPromotion(outcome.fromTo, gameState.turn).getOrNull() ?: return
            outcome.fromTo + piece.uci
        }
        is InferenceOutcome.Ambiguous -> {
            val chosen = ui.promptMoveDisambiguation(outcome.moves, gameState.turn).getOrNull() ?: return
            chosen
        }
        // Not a desync: the position is exactly the one expected, so there is nothing to reconcile
        // and no need for the heavier out-of-sync dialog.
        is InferenceOutcome.Unchanged -> {
            ui.displayError(UIMessage.BoardUnchanged)
            return
        }

        is InferenceOutcome.NoMatch -> {
            ui.boardDesync(gameState.fen, UIMessage.NoLegalMoveMatches)
            return
        }
    }

    val submitResult = gameController.submitMove(SubmitMoveParams(gameId = s.gameId, move = uci))
    when (submitResult) {
        is Result.Failure -> {
            ui.displayError(UIMessage.MoveRejected(submitResult.error))
            return
        }
        is Result.Success -> {
            if (s.mode == GameMode.REMOTE) {
                // Hand the turn over locally at once, then send the move to the server in the
                // background, so the clock switches to the opponent immediately rather than waiting
                // on the network round-trip. Lichess's own client stops your clock the moment you
                // move; the authoritative time returns on the opponent's reply.
                transitionToAwaitingMove()
                scope.launch {
                    val sent = s.remote?.sendMove(uci)
                    if (sent is Result.Failure) {
                        ui.displayError(UIMessage.MoveNotSent(sent.error))
                    }
                }
            } else {
                transitionToAwaitingMove()
            }
        }
    }
}

/** Moves the engine is assumed to still have to play, for dividing up what is left on its clock. */
private const val ASSUMED_MOVES_REMAINING = 30

private const val MIN_THINK_MS = 50L
private const val MAX_THINK_MS = 10_000L

/** Left on the clock for the move itself to be submitted after the search stops. */
private const val CLOCK_SAFETY_MARGIN_MS = 200L

/** Depth to search to when there is no clock to divide up. */
private const val UNTIMED_SEARCH_DEPTH = 12

/**
 * How long the engine may think, given what is left on its clock.
 *
 * A share of the remaining time plus most of the increment the move will earn back, held below
 * what the clock can actually afford. An engine given no budget at all searches to whatever fixed
 * depth its adapter defaults to, which bears no relation to the time control and is how a player
 * ends up watching their opponent flag them.
 */
internal fun engineThinkTimeMs(remainingMs: Long, incrementMs: Long): Long {
    val share = remainingMs / ASSUMED_MOVES_REMAINING + (incrementMs * 3) / 4
    val affordable = (remainingMs - CLOCK_SAFETY_MARGIN_MS).coerceAtLeast(MIN_THINK_MS)
    return share.coerceIn(MIN_THINK_MS, MAX_THINK_MS).coerceAtMost(affordable)
}

internal suspend fun Orchestrator.handleEngineMove() {
    val s = session ?: return
    val eng = s.engine ?: return
    val gameState = gameController.getState(s.gameId).getOrNull() ?: return

    // The engine is handed the game, not just a position: a starting FEN plus the moves played
    // from it, which is the shape the protocol gives the call. Sending only the current position
    // leaves it unable to see a
    // repetition it is walking into, or one it could claim.
    val startingFen = s.initialFen
    val searchFen = startingFen ?: gameState.fen
    val history = if (startingFen != null) gameState.moves else null

    // It is the engine's turn, so the side to move is the engine.
    val remainingMs = gameState.clocks?.let {
        when (gameState.turn) {
            Color.WHITE -> it.whiteMs
            Color.BLACK -> it.blackMs
        }
    }
    val thinkMs = remainingMs?.let { engineThinkTimeMs(it, s.timeControl?.incrementMs ?: 0) }

    debugLog("[orchestrator] Requesting engine move: fen=${gameState.fen} moves=${history?.size ?: 0} thinkMs=$thinkMs")

    val bestMove = eng.getBestMove(GetBestMoveParams(
        fen = searchFen,
        moves = history,
        // Untimed games still get a ceiling, so an adapter that would otherwise think until it is
        // stopped (which nothing here ever does) cannot hang the game.
        timeMs = thinkMs ?: MAX_THINK_MS,
        maxDepth = if (thinkMs == null) UNTIMED_SEARCH_DEPTH else null,
    ))

    when (bestMove) {
        is Result.Failure -> {
            debugLog("[orchestrator] Engine returned error: ${bestMove.error.code} ${bestMove.error.diagnostic.orEmpty()}")
            ui.displayError(UIMessage.EngineFailed(bestMove.error))
            return
        }
        is Result.Success -> {
            val moveUci = bestMove.value.move
            debugLog("[orchestrator] Engine suggested: $moveUci (score=${bestMove.value.score} depth=${bestMove.value.depth})")
            val submitResult = gameController.submitMove(SubmitMoveParams(gameId = s.gameId, move = moveUci))
            when (submitResult) {
                is Result.Failure -> {
                    debugLog("[orchestrator] Engine move REJECTED: move=$moveUci fen=${gameState.fen} error=${submitResult.error.code}")
                    ui.displayError(UIMessage.EngineMoveRejected(submitResult.error))
                    return
                }
                is Result.Success -> {
                    deliverOpponentMove(
                        s = s,
                        fenBefore = gameState.fen,
                        uci = moveUci,
                        // gameState was read before the move, so the side to move is the engine.
                        moverColor = gameState.turn,
                        submitted = submitResult.value,
                    )
                    transitionToAwaitingMove()
                }
            }
        }
    }
}

internal suspend fun Orchestrator.handleRemoteOpponentMove(event: OpponentMoveEvent) {
    val s = session ?: return

    // Read before the move is applied: the side to move now is the one making it, and the position
    // is the one the move is played from. Afterwards the turn has passed back to us and nothing
    // left says whose move this was, nor which squares it emptied.
    val before = gameController.getState(s.gameId).getOrNull() ?: return

    val submitResult = gameController.submitMove(SubmitMoveParams(
        gameId = s.gameId,
        move = event.move,
        whiteMs = event.clocks?.whiteMs,
        blackMs = event.clocks?.blackMs,
    ))
    when (submitResult) {
        is Result.Failure -> {
            ui.displayError(UIMessage.OpponentMoveRejected(submitResult.error))
            return
        }
        is Result.Success -> {
            deliverOpponentMove(
                s = s,
                fenBefore = before.fen,
                uci = event.move,
                moverColor = before.turn,
                submitted = submitResult.value,
            )
            transitionToAwaitingMove()
        }
    }
}

/**
 * Delivers an opponent's move to the board, where the board can play it, and then to the player.
 *
 * The board is asked first because what the player is told depends on what the board managed:
 * "update the board" is the wrong instruction to give about a board that has already updated
 * itself, and only half right about one that got most of the way.
 *
 * Only the opponent's moves come through here. The player's own has been made by hand before the
 * orchestrator knows what it was, because knowing what it was means having read it off the board.
 */
private suspend fun Orchestrator.deliverOpponentMove(
    s: GameSession,
    fenBefore: String,
    uci: String,
    moverColor: Color,
    submitted: SubmitMoveResult,
) {
    val decomposition = s.boardActuation?.let { decomposeMove(fenBefore, uci, it.canPlacePieces) }

    var update = BoardUpdate.MANUAL
    var failure: BoardError? = null
    if (decomposition != null) {
        debugLog("[orchestrator] Actuating $uci as ${decomposition.transfers}")
        when (val moved = board.movePieces(decomposition.transfers)) {
            is Result.Success -> {
                update = if (decomposition.leavesPieceToPlace) BoardUpdate.PARTIAL
                else BoardUpdate.COMPLETE
            }
            is Result.Failure -> {
                debugLog("[orchestrator] Actuation failed: ${moved.error.code}")
                failure = moved.error
                update = BoardUpdate.PARTIAL
            }
        }
    }

    ui.showOpponentMove(ShowOpponentMoveParams(
        move = uci,
        san = submitted.san,
        fen = submitted.fen,
        color = moverColor,
        clocks = submitted.clocks,
        isEnPassant = submitted.isEnPassant,
        boardUpdate = update,
    ))

    // Only a board that gave up is read back. It has left the pieces somewhere between two
    // positions and nothing but a read says where — and it may not have moved at all, a failure to
    // report not being a failure to move. A board that reported success is not read: the player
    // signalling reads it anyway, and a board one move out of step matches no legal move, which is
    // the same desynchronization arriving a moment later without a second read after every move.
    if (failure != null) {
        val shown = board.getState().getOrNull()
        if (shown == null || !boardShows(submitted.fen, shown, s.boardLevel)) {
            ui.boardDesync(submitted.fen, UIMessage.BoardFailed(failure))
        }
    }
}

/**
 * A player resigns.
 *
 * [color] comes from the UI, which is the only module that knows whose request it was: a local
 * game is two people at one board, and whether Resign belongs to the player to move or to each of
 * them separately is a question about the screen. Whose turn it happens to be does not answer it.
 */
internal suspend fun Orchestrator.handleResign(color: Color) {
    val s = session ?: return
    when (s.mode) {
        GameMode.LOCAL, GameMode.ENGINE -> {
            gameController.resign(s.gameId, color)
        }
        GameMode.REMOTE -> {
            // The server is the authority on how a remote game ends, so this
            // asks and waits for remote.gameEnded rather than deciding. Resigning locally as well
            // would commit to an outcome the server has not agreed to: were it to refuse the
            // request (not your game, already over, lost in transit) the local game would be over
            // regardless, and the setResult relaying the real ending would find it already
            // finished.
            val sent = s.remote?.resign()
            if (sent is Result.Failure) {
                ui.displayError(UIMessage.RemoteFailed(sent.error))
            }
        }
    }
}

/**
 * A player's clock has run out.
 *
 * The protocol lets a game controller either end the game itself or leave the decision here, so
 * this has
 * to work for both. Where it ended the game, `game.ended` is already on its way carrying the
 * result, and ending it here as well would win that race and report a timeout with no winner in
 * it.
 */
internal suspend fun Orchestrator.handleFlagFell(event: FlagFallEvent) {
    val s = session ?: return
    if (event.gameId != s.gameId) return

    // Under external authority the server is the timekeeper and decides timeouts itself.
    if (s.mode == GameMode.REMOTE) return

    if (gameController.getState(s.gameId).getOrNull()?.status?.isFinished == true) return

    // A controller that only reports the flag leaves the decision here, and whose clock ran out
    // is enough to settle it.
    endGame(
        result = when (event.color) {
            Color.WHITE -> GameResult.BLACK_WINS
            Color.BLACK -> GameResult.WHITE_WINS
        },
        termination = Termination.Timeout,
    )
}

/**
 * Aborts the game, by whichever route this mode's authority requires.
 *
 * The local game controller announces the ending itself on game.ended, so nothing here ends the
 * session: doing both would race that event, and the ending invented here would win.
 */
internal suspend fun Orchestrator.handleAbort() {
    val s = session ?: return

    if (s.mode == GameMode.REMOTE) {
        val sent = s.remote?.abort()
        if (sent is Result.Failure) {
            ui.displayError(UIMessage.RemoteFailed(sent.error))
        }
        return
    }

    val aborted = gameController.abort(s.gameId)
    if (aborted is Result.Failure) {
        ui.displayError(UIMessage.UnexpectedFailure(aborted.error.code))
    }
}

/**
 * Whose request a Draw tap is, given the mode.
 *
 * Local games are played by two people at one board, so it belongs to whoever is to move. In the
 * others there is only one person here to make it.
 */
private fun Orchestrator.drawRequester(s: GameSession): Color? = when (s.mode) {
    GameMode.LOCAL -> (state as? OrchestratorState.AwaitingPlayerMove)?.color
    GameMode.ENGINE, GameMode.REMOTE -> s.playerColor
}

internal suspend fun Orchestrator.handleDrawRequested(gameId: String) {
    val s = session ?: return
    val asker = drawRequester(s) ?: return

    // 1. Try claiming a draw (threefold repetition, 50-move rule, etc.)
    val claimResult = gameController.claimDraw(gameId)
    if (claimResult is Result.Success) return

    // 2. If the *opponent* has offered a draw, accept it. Whose offer it is has to be checked:
    //    a remote offer is left standing while the server is asked, so without this a second tap
    //    of Draw would accept the player's own offer and end their game for them.
    val gameState = gameController.getState(gameId).getOrNull()
    val pendingOffer = gameState?.takeIf { it.status == GameStatus.DRAW_OFFERED }?.drawOffer
    if (pendingOffer != null && pendingOffer != asker) {
        if (s.mode == GameMode.REMOTE) s.remote?.respondDraw(true)
        gameController.respondDraw(gameId, asker, true)
        return
    }
    // An offer of the player's own is already with the opponent; there is nothing more to do.
    if (pendingOffer == asker) return

    // 3. Otherwise, offer a draw
    when (s.mode) {
        GameMode.LOCAL -> {
            gameController.offerDraw(s.gameId, asker)

            // Both players share one screen, so the opponent is asked outright rather than left an
            // offer to discover.
            val offeredBy = when (asker) {
                Color.WHITE -> DrawOffer(s.whiteName, Color.WHITE)
                Color.BLACK -> DrawOffer(s.blackName, Color.BLACK)
            }
            val accepted = ui.promptDrawResponse(s.gameId, offeredBy).getOrNull() ?: false
            gameController.respondDraw(s.gameId, asker.opposite(), accepted)
        }
        GameMode.ENGINE -> {
            ui.displayError(UIMessage.DrawNotSupportedByEngine)
        }
        GameMode.REMOTE -> {
            s.remote?.offerDraw()
            gameController.offerDraw(s.gameId, asker)
        }
    }
}

internal suspend fun Orchestrator.handleRemoteDrawOffer() {
    val s = session ?: return
    val playerColor = s.playerColor ?: return
    val accept = ui.promptDrawResponse(s.gameId, DrawOffer(color = playerColor.opposite()))
        .getOrNull() ?: return

    s.remote?.respondDraw(accept)
    if (!accept) return

    // Record the opponent's offer before answering it. Nothing local made this offer, it arrived
    // from the server, so without this the answer is to an offer the game controller has never
    // heard of, which it rightly refuses.
    gameController.offerDraw(s.gameId, playerColor.opposite())
    gameController.respondDraw(s.gameId, playerColor, true)
}

private fun Color.opposite(): Color = when (this) {
    Color.WHITE -> Color.BLACK
    Color.BLACK -> Color.WHITE
}
