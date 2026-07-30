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
            try {
                handleEvent(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't let a single event crash the event loop.
                // Reset to idle so the orchestrator can accept new games.
                stopModuleCollectors()
                session = null
                state = OrchestratorState.Idle
                try {
                    ui.displayError(UIMessage.UnexpectedFailure(e.message))
                } catch (_: Exception) {}
            }
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
        launch { ui.drawRequested.collect { eventChannel.send(OrchestratorEvent.DrawRequested(it.gameId)) } }
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

    /** The game a user action is about, for the events that name one. */
    private fun OrchestratorEvent.targetGameId(): String? = when (this) {
        is OrchestratorEvent.MoveReady -> gameId
        is OrchestratorEvent.ResignRequested -> gameId
        is OrchestratorEvent.DrawRequested -> gameId
        is OrchestratorEvent.AbortRequested -> gameId
        else -> null
    }

    private suspend fun handleEvent(event: OrchestratorEvent) {
        // NewGameRequested is always handled — clean up any active state first
        if (event is OrchestratorEvent.NewGameRequested && state !is OrchestratorState.Idle) {
            session?.remote?.cancelSeek()
            stopModuleCollectors()
            session = null
            state = OrchestratorState.Idle
            // Fall through to handleIdle which will call runSetup()
        }

        // An event naming a game it is no longer time for is dropped. A UI can queue an action
        // that outlives the game it was made in - a resignation pressed as a game ends and
        // delivered after the next one has started - and acting on it would end a game nobody
        // asked to end. game.ended and game.flagFall make the same check for themselves, because
        // they have somewhere to put the id they carry.
        event.targetGameId()?.let { target ->
            if (target != session?.gameId) return
        }

        // Global interrupts — checked first when a game is active
        if (session != null) {
            when (event) {
                is OrchestratorEvent.GameEnded -> {
                    if (event.event.gameId == session?.gameId) {
                        endGame(result = event.event.result, termination = event.event.termination)
                    }
                    return
                }
                is OrchestratorEvent.FlagFell -> {
                    handleFlagFell(event.event)
                    return
                }
                is OrchestratorEvent.RemoteGameEnded -> {
                    gameController.setResult(SetResultParams(
                        gameId = session!!.gameId,
                        result = event.event.result,
                        termination = event.event.termination,
                    ))
                    endGame(result = event.event.result, termination = event.event.termination)
                    return
                }
                is OrchestratorEvent.ResignRequested -> {
                    handleResign()
                    return
                }
                is OrchestratorEvent.AbortRequested -> {
                    handleAbort()
                    return
                }
                is OrchestratorEvent.DrawRequested -> {
                    handleDrawRequested(event.gameId)
                    return
                }
                is OrchestratorEvent.BoardError -> {
                    ui.displayError(UIMessage.BoardFailed(event.error))
                    return
                }
                is OrchestratorEvent.EngineError -> {
                    ui.displayError(UIMessage.EngineFailed(event.error))
                    return
                }
                is OrchestratorEvent.RemoteError -> {
                    ui.displayError(UIMessage.RemoteFailed(event.error))
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
                        ui.displayError(UIMessage.GameCreationFailed(createResult.error))
                        session?.remote?.disconnect()
                        endGame(termination = Termination.Other())
                    }
                    is Result.Success -> {
                        val gameId = createResult.value.gameId
                        session = session!!.copy(
                            gameId = gameId,
                            playerColor = e.color,
                            timeControl = e.timeControl,
                            initialFen = e.fen,
                        )
                        notifyGameStarted(GameStartedInfo(
                            gameId = gameId,
                            mode = GameMode.REMOTE,
                            playerColor = e.color,
                            timeControl = e.timeControl,
                            whiteName = null,
                            blackName = null,
                            initialFen = e.fen,
                        ))
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

        // Publish the position and the clocks the move produced. This runs after every move
        // whatever its source, which is why the position is carried here rather than alongside the
        // notifications the UI receives: those cover the opponent's moves and not the player's own.
        session = s.copy(
            fen = gameState.fen,
            clocks = gameState.clocks ?: s.clocks,
        )

        // A game that has already ended is not one to await a move in. Ending it is not this
        // method's to do, though: the game controller announces every ending on game.ended,
        // carrying the termination, and that event is already on its way - submitMove emits it
        // before it returns. Ending the game here as well meant racing it and winning, so the
        // real termination was dropped along with the event and every checkmate reached the UI
        // as OTHER. Returning leaves the ending to the one party that knows what it was.
        if (gameState.status != GameStatus.NOT_STARTED
                && gameState.status != GameStatus.IN_PROGRESS
                && gameState.status != GameStatus.DRAW_OFFERED) {
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

    internal suspend fun endGame(
        result: GameResult = GameResult.IN_PROGRESS,
        termination: Termination = Termination.Other(),
    ) {
        val s = session
        if (s != null) {
            val history = gameController.getHistory(s.gameId).getOrNull()
            val gameState = gameController.getState(s.gameId).getOrNull()
            val summary = GameSummary(
                gameId = s.gameId,
                mode = s.mode,
                whiteName = s.whiteName,
                blackName = s.blackName,
                playerColor = s.playerColor,
                timeControl = s.timeControl,
                result = result,
                termination = termination,
                finalFen = gameState?.fen ?: "",
                moves = history?.moves ?: emptyList(),
            )
            ui.onGameEnded(summary)
        }
        stopModuleCollectors()
        session = null
        state = OrchestratorState.Idle
    }

    internal suspend fun notifyGameStarted(info: GameStartedInfo) {
        ui.onGameStarted(info)
    }

}
