package scp.orchestrator

import scp.protocol.*

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
            // For remote games, send the move to the remote server
            if (s.mode == GameMode.REMOTE) {
                s.remote?.sendMove(uci)
            }
            transitionToAwaitingMove()
        }
    }
}

internal suspend fun Orchestrator.handleEngineMove() {
    val s = session ?: return
    val eng = s.engine ?: return
    val gameState = gameController.getState(s.gameId).getOrNull() ?: return

    val bestMove = eng.getBestMove(GetBestMoveParams(
        fen = gameState.fen,
        moves = gameState.moves,
    ))

    when (bestMove) {
        is Result.Failure -> {
            ui.displayError("Engine error: ${bestMove.error.message}")
            return
        }
        is Result.Success -> {
            val moveUci = bestMove.value.move
            val submitResult = gameController.submitMove(SubmitMoveParams(gameId = s.gameId, move = moveUci))
            when (submitResult) {
                is Result.Failure -> {
                    ui.displayError("Engine move rejected: ${submitResult.error.message}")
                    return
                }
                is Result.Success -> {
                    ui.showOpponentMove(ShowOpponentMoveParams(
                        move = moveUci,
                        san = submitResult.value.san,
                        fen = submitResult.value.fen,
                        clocks = submitResult.value.clocks,
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

internal suspend fun Orchestrator.handleDrawOffer() {
    val s = session ?: return
    when (s.mode) {
        GameMode.LOCAL -> {
            val turnColor = (state as? OrchestratorState.AwaitingPlayerMove)?.color ?: return
            gameController.offerDraw(s.gameId, turnColor)
        }
        GameMode.ENGINE -> {
            // Engine games don't support draw offers
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
