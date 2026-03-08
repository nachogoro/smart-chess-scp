package scp.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import scp.protocol.*

class Orchestrator(
    internal val board: BoardModule,
    internal val gameController: GameControllerModule,
    internal val ui: UIModule,
    internal val engines: List<EngineAdapterModule> = emptyList(),
    internal val remotes: List<RemoteAdapterModule> = emptyList(),
) {
    internal val eventChannel = Channel<OrchestratorEvent>(Channel.UNLIMITED)
    internal lateinit var scope: CoroutineScope

    var state: OrchestratorState = OrchestratorState.Idle
        internal set

    var session: GameSession? = null
        internal set

    internal var moduleCollectorJobs: List<Job> = emptyList()

    suspend fun start() = coroutineScope {
        scope = this
        launchCollectors()
        for (event in eventChannel) {
            handleEvent(event)
        }
        coroutineContext.cancelChildren()
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
        launch { ui.drawClaimRequested.collect { eventChannel.send(OrchestratorEvent.DrawClaimRequested(it.gameId)) } }
        launch { ui.abortRequested.collect { eventChannel.send(OrchestratorEvent.AbortRequested(it.gameId)) } }

        // Game Controller events
        launch { gameController.gameEnded.collect { eventChannel.send(OrchestratorEvent.GameEnded(it)) } }
        launch { gameController.flagFall.collect { eventChannel.send(OrchestratorEvent.FlagFell(it)) } }

        // Board errors
        launch { board.errors.collect { eventChannel.send(OrchestratorEvent.BoardError(it)) } }
    }

    internal fun startModuleCollectors(session: GameSession) {
        val jobs = mutableListOf<Job>()
        session.engine?.let { eng ->
            jobs += scope.launch { eng.errors.collect { eventChannel.send(OrchestratorEvent.EngineError(it)) } }
        }
        session.remote?.let { rem ->
            jobs += scope.launch { rem.gameStarted.collect { eventChannel.send(OrchestratorEvent.RemoteGameStarted(it)) } }
            jobs += scope.launch { rem.opponentMove.collect { eventChannel.send(OrchestratorEvent.RemoteOpponentMove(it)) } }
            jobs += scope.launch { rem.gameEnded.collect { eventChannel.send(OrchestratorEvent.RemoteGameEnded(it)) } }
            jobs += scope.launch { rem.drawOffered.collect { eventChannel.send(OrchestratorEvent.RemoteDrawOffered) } }
            jobs += scope.launch { rem.errors.collect { eventChannel.send(OrchestratorEvent.RemoteError(it)) } }
        }
        moduleCollectorJobs = jobs
    }

    internal fun stopModuleCollectors() {
        moduleCollectorJobs.forEach { it.cancel() }
        moduleCollectorJobs = emptyList()
    }

    private suspend fun handleEvent(event: OrchestratorEvent) {
        // Global interrupts — checked first when a game is active
        if (session != null) {
            when (event) {
                is OrchestratorEvent.GameEnded -> {
                    endGame()
                    return
                }
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
                is OrchestratorEvent.ResignRequested -> {
                    handleResign()
                    return
                }
                is OrchestratorEvent.AbortRequested -> {
                    gameController.abort(session!!.gameId)
                    if (session?.mode == GameMode.REMOTE) session?.remote?.disconnect()
                    endGame()
                    return
                }
                is OrchestratorEvent.DrawClaimRequested -> {
                    gameController.claimDraw(event.gameId)
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
                        session?.remote?.disconnect()
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
    // State transitions
    // -----------------------------------------------------------------------

    internal suspend fun transitionToAwaitingMove() {
        val s = session ?: return
        val gameState = gameController.getState(s.gameId).getOrNull() ?: return

        // Check if game already ended
        if (gameState.status != GameStatus.IN_PROGRESS
                && gameState.status != GameStatus.DRAW_OFFERED) {
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
                    state = OrchestratorState.AwaitingOpponentMove(gameState.turn)
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

    internal fun endGame() {
        stopModuleCollectors()
        session = null
        state = OrchestratorState.Idle
    }

}
