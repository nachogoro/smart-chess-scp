package scp.orchestrator

import scp.protocol.*
import kotlinx.coroutines.launch
import scp.protocol.debugLog

internal suspend fun Orchestrator.handleMoveReady() {
    val s = session ?: return
    val gameState = gameController.getState(s.gameId).getOrNull() ?: return

    val boardState = board.getState().getOrNull() ?: run {
        ui.displayError("Failed to read board state")
        return
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
            ui.displayError("The board has not changed. Move a piece, then tap again.")
            return
        }

        is InferenceOutcome.NoMatch -> {
            ui.boardDesync(gameState.fen, "Board does not match any legal move")
            return
        }
    }

    val submitResult = gameController.submitMove(SubmitMoveParams(gameId = s.gameId, move = uci))
    when (submitResult) {
        is Result.Failure -> {
            ui.displayError("Move rejected: ${submitResult.error.message}")
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
                        ui.displayError("Failed to send move to the server: ${sent.error.message}")
                    }
                }
            } else {
                transitionToAwaitingMove()
            }
        }
    }
}

internal suspend fun Orchestrator.handleEngineMove() {
    val s = session ?: return
    val eng = s.engine ?: return
    val gameState = gameController.getState(s.gameId).getOrNull() ?: return

    debugLog("[orchestrator] Requesting engine move: fen=${gameState.fen}")

    val bestMove = eng.getBestMove(GetBestMoveParams(
        fen = gameState.fen,
    ))

    when (bestMove) {
        is Result.Failure -> {
            debugLog("[orchestrator] Engine returned error: ${bestMove.error.code}: ${bestMove.error.message}")
            ui.displayError("Engine error: ${bestMove.error.message}")
            return
        }
        is Result.Success -> {
            val moveUci = bestMove.value.move
            debugLog("[orchestrator] Engine suggested: $moveUci (score=${bestMove.value.score} depth=${bestMove.value.depth})")
            val submitResult = gameController.submitMove(SubmitMoveParams(gameId = s.gameId, move = moveUci))
            when (submitResult) {
                is Result.Failure -> {
                    debugLog("[orchestrator] Engine move REJECTED: move=$moveUci fen=${gameState.fen} error=${submitResult.error.message}")
                    ui.displayError("Engine move rejected: ${submitResult.error.message}")
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
            ui.displayError("Opponent move rejected: ${submitResult.error.message}")
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
            s.remote?.resign()
            val playerColor = s.playerColor ?: return
            gameController.resign(s.gameId, playerColor)
        }
    }
}

internal suspend fun Orchestrator.handleDrawRequested(gameId: String) {
    val s = session ?: return

    // 1. Try claiming a draw (threefold repetition, 50-move rule, etc.)
    val claimResult = gameController.claimDraw(gameId)
    if (claimResult is Result.Success) return

    // 2. If the opponent has offered a draw, accept it
    val gameState = gameController.getState(gameId).getOrNull()
    if (gameState?.status == GameStatus.DRAW_OFFERED) {
        if (s.mode == GameMode.REMOTE) s.remote?.respondDraw(true)
        gameController.respondDraw(gameId, true)
        return
    }

    // 3. Otherwise, offer a draw
    when (s.mode) {
        GameMode.LOCAL -> {
            val turnColor = (state as? OrchestratorState.AwaitingPlayerMove)?.color ?: return
            gameController.offerDraw(s.gameId, turnColor)

            // Both players share one screen, so the opponent is asked outright rather than left an
            // offer to discover. Leaving it pending would mean the next Draw tap agreed the draw
            // with no confirmation - including a second tap from the player who offered it.
            val offeredBy = when (turnColor) {
                Color.WHITE -> s.whiteName ?: "White"
                Color.BLACK -> s.blackName ?: "Black"
            }
            val accepted = ui.promptDrawResponse(s.gameId, offeredBy).getOrNull() ?: false
            gameController.respondDraw(s.gameId, accepted)
        }
        GameMode.ENGINE -> {
            ui.displayError("Draw offers are not supported against the engine")
        }
        GameMode.REMOTE -> {
            s.remote?.offerDraw()
            val playerColor = s.playerColor ?: return
            gameController.offerDraw(s.gameId, playerColor)
        }
    }
}

internal suspend fun Orchestrator.handleRemoteDrawOffer() {
    val s = session ?: return
    val accept = ui.promptDrawResponse(s.gameId, "opponent").getOrNull() ?: return
    s.remote?.respondDraw(accept)
    if (accept) {
        gameController.respondDraw(s.gameId, true)
    }
}
