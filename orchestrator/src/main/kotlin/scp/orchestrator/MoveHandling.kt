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
    // from it (PROTOCOL.md §4.3). Sending only the current position leaves it unable to see a
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
        // stopped - which nothing here ever does - cannot hang the game.
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
                    ui.showOpponentMove(ShowOpponentMoveParams(
                        move = moveUci,
                        san = submitResult.value.san,
                        fen = submitResult.value.fen,
                        clocks = submitResult.value.clocks,
                        isEnPassant = submitResult.value.isEnPassant,
                    ))
                    transitionToAwaitingMove()
                }
            }
        }
    }
}

internal suspend fun Orchestrator.handleRemoteOpponentMove(event: OpponentMoveEvent) {
    val s = session ?: return
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
            ui.showOpponentMove(ShowOpponentMoveParams(
                move = event.move,
                san = submitResult.value.san,
                fen = submitResult.value.fen,
                clocks = submitResult.value.clocks,
                isEnPassant = submitResult.value.isEnPassant,
            ))
            transitionToAwaitingMove()
        }
    }
}

internal suspend fun Orchestrator.handleResign() {
    val s = session ?: return
    when (s.mode) {
        GameMode.LOCAL -> {
            // In local mode, resign for the player whose turn it is
            val turnColor = (state as? OrchestratorState.AwaitingPlayerMove)?.color ?: return
            gameController.resign(s.gameId, turnColor)
        }
        GameMode.ENGINE -> {
            val playerColor = s.playerColor ?: return
            gameController.resign(s.gameId, playerColor)
        }
        GameMode.REMOTE -> {
            // The server is the authority on how a remote game ends (PROTOCOL.md §6.3), so this
            // asks and waits for remote.gameEnded rather than deciding. Resigning locally as well
            // committed to an outcome the server had not agreed to: if it refused the request -
            // not your game, already over, lost in transit - the local game was over anyway, and
            // the setResult that should have relayed the real ending arrived to find it finished.
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
 * §6.3 lets a game controller either end the game itself or leave the decision here, so this has
 * to work for both. Where it ended the game, `game.ended` is already on its way carrying the
 * result, and ending it here as well would win that race and report a timeout with no winner in
 * it - which is what used to happen, so a player was never told who won on time.
 */
internal suspend fun Orchestrator.handleFlagFell(event: FlagFallEvent) {
    val s = session ?: return
    if (event.gameId != s.gameId) return

    // Under external authority the server is the timekeeper and decides timeouts itself (§4.2).
    if (s.mode == GameMode.REMOTE) return

    val status = gameController.getState(s.gameId).getOrNull()?.status
    val stillRunning = status == null ||
        status == GameStatus.NOT_STARTED ||
        status == GameStatus.IN_PROGRESS ||
        status == GameStatus.DRAW_OFFERED
    if (!stillRunning) return

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
 * session: doing both is what made every locally decided ending arrive as OTHER.
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
    //    of Draw accepted the player's own offer and ended their game for them.
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

    // Record the opponent's offer before answering it. Nothing local made this offer - it arrived
    // from the server - so without it the answer is one to an offer the game controller has never
    // heard of, which it rightly refuses.
    gameController.offerDraw(s.gameId, playerColor.opposite())
    gameController.respondDraw(s.gameId, playerColor, true)
}

private fun Color.opposite(): Color = when (this) {
    Color.WHITE -> Color.BLACK
    Color.BLACK -> Color.WHITE
}
