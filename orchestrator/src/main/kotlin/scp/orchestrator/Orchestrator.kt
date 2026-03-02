package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import scp.protocol.*

class Orchestrator(
    private val board: BoardModule,
    private val gameController: GameControllerModule,
    private val ui: UIModule,
    private val engine: EngineAdapterModule? = null,
    private val remote: RemoteAdapterModule? = null,
) {
    private val eventChannel = Channel<OrchestratorEvent>(Channel.UNLIMITED)

    var state: OrchestratorState = OrchestratorState.Idle
        private set

    var session: GameSession? = null
        private set

    suspend fun start() = coroutineScope {
        launchCollectors()
        for (event in eventChannel) {
            handleEvent(event)
        }
    }

    fun stop() {
        eventChannel.close()
    }

    private fun CoroutineScope.launchCollectors() {
        // UI events
        launch { ui.newGameRequested.collect { eventChannel.send(OrchestratorEvent.NewGameRequested) } }
        launch { ui.moveReady.collect { eventChannel.send(OrchestratorEvent.MoveReady(it.gameId)) } }
        launch { ui.resignRequested.collect { eventChannel.send(OrchestratorEvent.ResignRequested(it.gameId)) } }
        launch { ui.drawOfferRequested.collect { eventChannel.send(OrchestratorEvent.DrawOfferRequested(it.gameId)) } }
        launch { ui.drawClaimRequested.collect { eventChannel.send(OrchestratorEvent.DrawClaimRequested(it.gameId, it.reason)) } }
        launch { ui.abortRequested.collect { eventChannel.send(OrchestratorEvent.AbortRequested(it.gameId)) } }

        // Game Controller events
        launch { gameController.gameEnded.collect { eventChannel.send(OrchestratorEvent.GameEnded(it)) } }
        launch { gameController.flagFall.collect { eventChannel.send(OrchestratorEvent.FlagFell(it)) } }

        // Board errors
        launch { board.errors.collect { eventChannel.send(OrchestratorEvent.BoardError(it)) } }

        // Engine events (optional)
        engine?.let { e ->
            launch { e.errors.collect { eventChannel.send(OrchestratorEvent.EngineError(it)) } }
        }

        // Remote events (optional)
        remote?.let { r ->
            launch { r.gameStarted.collect { eventChannel.send(OrchestratorEvent.RemoteGameStarted(it)) } }
            launch { r.opponentMove.collect { eventChannel.send(OrchestratorEvent.RemoteOpponentMove(it)) } }
            launch { r.gameEnded.collect { eventChannel.send(OrchestratorEvent.RemoteGameEnded(it)) } }
            launch { r.drawOffered.collect { eventChannel.send(OrchestratorEvent.RemoteDrawOffered) } }
            launch { r.errors.collect { eventChannel.send(OrchestratorEvent.RemoteError(it)) } }
        }
    }

    private suspend fun handleEvent(event: OrchestratorEvent) {
        // Global interrupts — checked first when a game is active
        if (session != null) {
            when (event) {
                is OrchestratorEvent.GameEnded -> { endGame(); return }
                is OrchestratorEvent.FlagFell -> {
                    if (session?.mode != GameMode.REMOTE) endGame()
                    return
                }
                is OrchestratorEvent.RemoteGameEnded -> {
                    gameController.setResult(SetResultParams(
                        gameId = session!!.gameId,
                        result = event.event.result,
                        reason = event.event.reason,
                    ))
                    endGame()
                    return
                }
                is OrchestratorEvent.ResignRequested -> { handleResign(); return }
                is OrchestratorEvent.AbortRequested -> {
                    gameController.abort(session!!.gameId)
                    if (session?.mode == GameMode.REMOTE) remote?.disconnect()
                    endGame()
                    return
                }
                is OrchestratorEvent.DrawClaimRequested -> {
                    gameController.claimDraw(event.gameId, event.reason)
                    // If valid, GC fires GameEnded — we'll handle it on next event
                    return
                }
                is OrchestratorEvent.BoardError -> {
                    ui.displayError("Board error: ${event.event.message}")
                    return
                }
                is OrchestratorEvent.EngineError -> {
                    ui.displayError("Engine error: ${event.event.message}")
                    return
                }
                is OrchestratorEvent.RemoteError -> {
                    ui.displayError("Remote error: ${event.event.message}")
                    return
                }
                else -> {} // Fall through to state-specific handling
            }
        }

        // State-specific handling
        when (val s = state) {
            is OrchestratorState.Idle -> handleIdle(event)
            is OrchestratorState.SeekingGame -> handleSeekingGame(event)
            is OrchestratorState.AwaitingPlayerMove -> handleAwaitingPlayerMove(event, s.color)
            is OrchestratorState.AwaitingOpponentMove -> handleAwaitingOpponentMove(event)
        }
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    private suspend fun handleIdle(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.NewGameRequested -> runSetup()
            else -> {} // Ignore events in idle
        }
    }

    private suspend fun handleSeekingGame(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.RemoteGameStarted -> {
                val e = event.event
                val createResult = gameController.create(CreateGameParams(
                    fen = e.fen,
                    timeControl = e.timeControl,
                    authority = Authority.EXTERNAL,
                ))
                when (createResult) {
                    is Result.Failure -> {
                        ui.displayError("Failed to create game: ${createResult.error.message}")
                        remote?.disconnect()
                        endGame()
                    }
                    is Result.Success -> {
                        val gameId = createResult.value.gameId
                        session = session!!.copy(
                            gameId = gameId,
                            playerColor = e.color,
                            timeControl = e.timeControl,
                        )
                        transitionToAwaitingMove()
                    }
                }
            }
            else -> {} // Ignore other events while seeking
        }
    }

    private suspend fun handleAwaitingPlayerMove(event: OrchestratorEvent, color: Color) {
        when (event) {
            is OrchestratorEvent.MoveReady -> handleMoveReady()
            is OrchestratorEvent.DrawOfferRequested -> handleDrawOffer()
            is OrchestratorEvent.RemoteDrawOffered -> handleRemoteDrawOffer()
            else -> {} // Ignore
        }
    }

    private suspend fun handleAwaitingOpponentMove(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.RemoteOpponentMove -> handleRemoteOpponentMove(event.event)
            is OrchestratorEvent.RemoteDrawOffered -> handleRemoteDrawOffer()
            else -> {} // Ignore
        }
    }

    // -----------------------------------------------------------------------
    // Setup flows
    // -----------------------------------------------------------------------

    private suspend fun runSetup() {
        val availableModes = buildList {
            add(GameMode.LOCAL)
            if (engine != null) add(GameMode.ENGINE)
            if (remote != null) add(GameMode.REMOTE)
        }

        val modeResult = ui.selectGameMode(availableModes)
        val mode = when (modeResult) {
            is Result.Failure -> return // Cancelled
            is Result.Success -> modeResult.value
        }

        when (mode) {
            GameMode.LOCAL -> setupLocal()
            GameMode.ENGINE -> setupEngine()
            GameMode.REMOTE -> setupRemote()
        }
    }

    private suspend fun setupLocal() {
        val config = ui.configureLocalGame().getOrNull() ?: return

        val boardCaps = board.getCapabilities().getOrNull() ?: run {
            ui.displayError("Failed to read board capabilities")
            return
        }

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
        ))
        val gameId = when (createResult) {
            is Result.Failure -> {
                ui.displayError("Failed to create game: ${createResult.error.message}")
                return
            }
            is Result.Success -> createResult.value.gameId
        }

        session = GameSession(
            gameId = gameId,
            mode = GameMode.LOCAL,
            boardLevel = boardCaps.level,
            timeControl = config.timeControl,
        )
        transitionToAwaitingMove()
    }

    private suspend fun setupEngine() {
        val eng = engine ?: return

        val caps = eng.getCapabilities().getOrNull() ?: run {
            ui.displayError("Failed to read engine capabilities")
            return
        }

        val config = ui.configureEngineGame(ConfigureEngineGameParams(
            engineName = caps.name,
            parameters = caps.parameters,
        )).getOrNull() ?: return

        eng.configure(config.engineSettings).getOrNull() ?: run {
            ui.displayError("Failed to configure engine")
            return
        }

        val boardCaps = board.getCapabilities().getOrNull() ?: run {
            ui.displayError("Failed to read board capabilities")
            return
        }

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
        ))
        val gameId = when (createResult) {
            is Result.Failure -> {
                ui.displayError("Failed to create game: ${createResult.error.message}")
                return
            }
            is Result.Success -> createResult.value.gameId
        }

        eng.newGame()

        session = GameSession(
            gameId = gameId,
            mode = GameMode.ENGINE,
            boardLevel = boardCaps.level,
            playerColor = config.playerColor,
            timeControl = config.timeControl,
        )
        transitionToAwaitingMove()
    }

    private suspend fun setupRemote() {
        val rem = remote ?: return

        val caps = rem.getCapabilities().getOrNull() ?: run {
            ui.displayError("Failed to read remote capabilities")
            return
        }

        val config = ui.configureRemoteGame(ConfigureRemoteGameParams(
            platform = caps.platform,
            parameters = caps.parameters,
            seekParameters = caps.seekParameters,
        )).getOrNull() ?: return

        rem.configure(config.platformSettings).getOrNull() ?: run {
            ui.displayError("Failed to configure remote platform")
            return
        }

        val boardCaps = board.getCapabilities().getOrNull() ?: run {
            ui.displayError("Failed to read board capabilities")
            return
        }

        rem.seekGame(config.seekSettings).getOrNull() ?: run {
            ui.displayError("Failed to seek game")
            return
        }

        session = GameSession(
            gameId = "", // Will be set when game starts
            mode = GameMode.REMOTE,
            boardLevel = boardCaps.level,
        )
        state = OrchestratorState.SeekingGame
    }

    // -----------------------------------------------------------------------
    // Move handling
    // -----------------------------------------------------------------------

    private suspend fun handleMoveReady() {
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
                    remote?.sendMove(uci)
                }
                transitionToAwaitingMove()
            }
        }
    }

    private suspend fun handleEngineMove() {
        val s = session ?: return
        val eng = engine ?: return
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

    private suspend fun handleRemoteOpponentMove(event: OpponentMoveEvent) {
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

    // -----------------------------------------------------------------------
    // Draw / Resign
    // -----------------------------------------------------------------------

    private suspend fun handleResign() {
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
                remote?.resign()
                val playerColor = s.playerColor ?: return
                gameController.resign(s.gameId, playerColor)
            }
        }
    }

    private suspend fun handleDrawOffer() {
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
                remote?.offerDraw()
                val playerColor = s.playerColor ?: return
                gameController.offerDraw(s.gameId, playerColor)
            }
        }
    }

    private suspend fun handleRemoteDrawOffer() {
        val s = session ?: return
        val accept = ui.promptDrawResponse(s.gameId, "opponent").getOrNull() ?: return
        remote?.respondDraw(accept)
        if (accept) {
            gameController.respondDraw(s.gameId, true)
        }
    }

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    private suspend fun transitionToAwaitingMove() {
        val s = session ?: return
        val gameState = gameController.getState(s.gameId).getOrNull() ?: return

        // Check if game already ended
        if (gameState.status != GameStatus.IN_PROGRESS && gameState.status != GameStatus.DRAW_OFFERED) {
            endGame()
            return
        }

        when (s.mode) {
            GameMode.LOCAL -> {
                state = OrchestratorState.AwaitingPlayerMove(gameState.turn)
            }
            GameMode.ENGINE -> {
                val playerColor = s.playerColor ?: return
                if (gameState.turn == playerColor) {
                    state = OrchestratorState.AwaitingPlayerMove(gameState.turn)
                } else {
                    // Engine's turn — handle inline
                    state = OrchestratorState.AwaitingPlayerMove(gameState.turn) // Temporary state during engine think
                    handleEngineMove()
                }
            }
            GameMode.REMOTE -> {
                val playerColor = s.playerColor ?: return
                if (gameState.turn == playerColor) {
                    state = OrchestratorState.AwaitingPlayerMove(gameState.turn)
                } else {
                    state = OrchestratorState.AwaitingOpponentMove(gameState.turn)
                }
            }
        }
    }

    private fun endGame() {
        session = null
        state = OrchestratorState.Idle
    }

}
