package scp.orchestrator

import scp.protocol.*

/**
 * Only a fallback, for a controller that created a game it will not then describe.
 *
 * The starting position is read back from the game controller rather than assumed: it is the
 * party that decided it, and `game.create` takes a FEN.
 */
private const val STANDARD_START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

/** The position [gameId] starts from, as the game controller reports it. */
private suspend fun Orchestrator.initialFenOf(gameId: String): String =
    gameController.getState(gameId).getOrNull()?.fen ?: STANDARD_START_FEN

internal suspend fun Orchestrator.runSetup() {
    val availableModes = buildList {
        add(GameMode.LOCAL)
        if (engines.isNotEmpty()) add(GameMode.ENGINE)
        if (remotes.isNotEmpty()) add(GameMode.REMOTE)
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

internal suspend fun Orchestrator.setupLocal() {
    var errorMessage: UIMessage? = null

    while (true) {
        val config = ui.configureLocalGame(errorMessage).getOrNull() ?: return

        val boardCaps = when (val caps = board.getCapabilities()) {
            is Result.Failure -> {
                errorMessage = UIMessage.BoardCapabilitiesUnavailable(caps.error)
                continue
            }
            is Result.Success -> caps.value
        }

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
        ))
        if (createResult is Result.Failure) {
            errorMessage = UIMessage.GameCreationFailed(createResult.error)
            continue
        }
        val gameId = (createResult as Result.Success).value.gameId
        val initialFen = initialFenOf(gameId)

        session = GameSession(
            gameId = gameId,
            mode = GameMode.LOCAL,
            boardLevel = boardCaps.level,
            boardActuation = boardCaps.actuation,
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
            initialFen = initialFen,
        )
        notifyGameStarted(GameStartedInfo(
            gameId = gameId,
            mode = GameMode.LOCAL,
            playerColor = null,
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
            initialFen = initialFen,
        ))
        transitionToAwaitingMove()
        return
    }
}

internal suspend fun Orchestrator.setupEngine() {
    val eng = selectEngine() ?: return

    val caps = eng.getCapabilities().getOrNull() ?: run {
        ui.displayError(UIMessage.EngineCapabilitiesUnavailable)
        return
    }

    val baseParams = ConfigureEngineGameParams(
        engineName = caps.name,
        engineAuthor = caps.author,
        parameters = caps.parameters,
    )
    var errorMessage: UIMessage? = null

    while (true) {
        val config = ui.configureEngineGame(
            baseParams.copy(errorMessage = errorMessage)
        ).getOrNull() ?: return

        val configured = eng.configure(config.engineSettings)
        if (configured is Result.Failure) {
            errorMessage = UIMessage.EngineConfigurationFailed(configured.error)
            continue
        }

        val boardCaps = when (val caps = board.getCapabilities()) {
            is Result.Failure -> {
                errorMessage = UIMessage.BoardCapabilitiesUnavailable(caps.error)
                continue
            }
            is Result.Success -> caps.value
        }

        // Now that the player has a name, both sides of the board have one.
        val whiteName = if (config.playerColor == Color.WHITE) config.playerName else caps.name
        val blackName = if (config.playerColor == Color.WHITE) caps.name else config.playerName

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
            whiteName = whiteName,
            blackName = blackName,
        ))
        if (createResult is Result.Failure) {
            errorMessage = UIMessage.GameCreationFailed(createResult.error)
            continue
        }
        val gameId = (createResult as Result.Success).value.gameId
        val initialFen = initialFenOf(gameId)

        eng.newGame()

        session = GameSession(
            gameId = gameId,
            mode = GameMode.ENGINE,
            boardLevel = boardCaps.level,
            boardActuation = boardCaps.actuation,
            playerColor = config.playerColor,
            timeControl = config.timeControl,
            whiteName = whiteName,
            blackName = blackName,
            engine = eng,
            initialFen = initialFen,
        )
        notifyGameStarted(GameStartedInfo(
            gameId = gameId,
            mode = GameMode.ENGINE,
            playerColor = config.playerColor,
            timeControl = config.timeControl,
            whiteName = whiteName,
            blackName = blackName,
            initialFen = initialFen,
        ))
        startModuleCollectors(session!!)
        transitionToAwaitingMove()
        return
    }
}

internal suspend fun Orchestrator.setupRemote() {
    val rem = selectRemote() ?: return

    val caps = rem.getCapabilities().getOrNull() ?: run {
        ui.displayError(UIMessage.RemoteCapabilitiesUnavailable)
        return
    }

    val baseParams = ConfigureRemoteGameParams(
        platform = caps.platform,
        features = caps.features,
        parameters = caps.parameters,
        seekParameters = caps.seekParameters,
    )
    var errorMessage: UIMessage? = null

    while (true) {
        val config = ui.configureRemoteGame(
            baseParams.copy(errorMessage = errorMessage)
        ).getOrNull() ?: return

        val configured = rem.configure(config.platformSettings)
        if (configured is Result.Failure) {
            errorMessage = UIMessage.RemoteConfigurationFailed(configured.error)
            continue
        }

        val boardCaps = when (val caps = board.getCapabilities()) {
            is Result.Failure -> {
                errorMessage = UIMessage.BoardCapabilitiesUnavailable(caps.error)
                continue
            }
            is Result.Success -> caps.value
        }

        val sought = rem.seekGame(config.seekSettings)
        if (sought is Result.Failure) {
            errorMessage = UIMessage.SeekFailed(sought.error)
            continue
        }

        session = GameSession(
            gameId = "", // Will be set when game starts
            mode = GameMode.REMOTE,
            boardLevel = boardCaps.level,
            boardActuation = boardCaps.actuation,
            remote = rem,
            remoteUsername = configured.getOrNull()?.username,
        )
        startModuleCollectors(session!!)
        state = OrchestratorState.SeekingGame
        ui.onSeekingGame()
        return
    }
}

/**
 * An adapter's position in the list it was connected in.
 *
 * Stable for as long as the orchestrator is, which is as long as a selection needs to mean
 * anything, and unique in a way a name is not.
 */
private fun idOf(index: Int): String = index.toString()

private suspend fun Orchestrator.selectEngine(): EngineAdapterModule? {
    if (engines.isEmpty()) return null
    if (engines.size == 1) return engines.single()

    // Only the ones that can describe themselves. An adapter that will not answer
    // getCapabilities cannot complete the handshake either, so listing it offers a dead end,
    // and there is nothing to label it with but a word this module would have to invent.
    val described = engines.withIndex().mapNotNull { (index, engine) ->
        engine.getCapabilities().getOrNull()?.let { caps ->
            EngineInfo(id = idOf(index), name = caps.name, author = caps.author)
        }
    }
    if (described.isEmpty()) {
        ui.displayError(UIMessage.EngineCapabilitiesUnavailable)
        return null
    }
    described.singleOrNull()?.let { return engines[it.id.toInt()] }

    val selected = ui.selectEngine(described).getOrNull() ?: return null
    val chosen = described.firstOrNull { it.id == selected }
        ?: return null.also {
            ui.displayError(UIMessage.UnexpectedFailure("no engine was offered under id '$selected'"))
        }
    return engines[chosen.id.toInt()]
}

private suspend fun Orchestrator.selectRemote(): RemoteAdapterModule? {
    if (remotes.isEmpty()) return null
    if (remotes.size == 1) return remotes.single()

    val described = remotes.withIndex().mapNotNull { (index, remote) ->
        remote.getCapabilities().getOrNull()?.let { caps ->
            PlatformInfo(id = idOf(index), platform = caps.platform, features = caps.features)
        }
    }
    if (described.isEmpty()) {
        ui.displayError(UIMessage.RemoteCapabilitiesUnavailable)
        return null
    }
    described.singleOrNull()?.let { return remotes[it.id.toInt()] }

    val selected = ui.selectRemotePlatform(described).getOrNull() ?: return null
    val chosen = described.firstOrNull { it.id == selected }
        ?: return null.also {
            ui.displayError(UIMessage.UnexpectedFailure("no platform was offered under id '$selected'"))
        }
    return remotes[chosen.id.toInt()]
}
