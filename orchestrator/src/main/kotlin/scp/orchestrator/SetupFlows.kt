package scp.orchestrator

import scp.protocol.*

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
    var errorMessage: String? = null

    while (true) {
        val config = ui.configureLocalGame(errorMessage).getOrNull() ?: return

        val boardCaps = board.getCapabilities().getOrNull()
        if (boardCaps == null) {
            errorMessage = "Failed to read board capabilities"
            continue
        }

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
        ))
        if (createResult is Result.Failure) {
            errorMessage = "Failed to create game: ${createResult.error.message}"
            continue
        }
        val gameId = (createResult as Result.Success).value.gameId

        session = GameSession(
            gameId = gameId,
            mode = GameMode.LOCAL,
            boardLevel = boardCaps.level,
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
        )
        notifyGameStarted(GameStartedInfo(
            gameId = gameId,
            mode = GameMode.LOCAL,
            playerColor = null,
            timeControl = config.timeControl,
            whiteName = config.whiteName,
            blackName = config.blackName,
            initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        ))
        transitionToAwaitingMove()
        return
    }
}

internal suspend fun Orchestrator.setupEngine() {
    val eng = selectEngine() ?: return

    val caps = eng.getCapabilities().getOrNull() ?: run {
        ui.displayError("Failed to read engine capabilities")
        return
    }

    val baseParams = ConfigureEngineGameParams(
        engineName = caps.name,
        parameters = caps.parameters,
    )
    var errorMessage: String? = null

    while (true) {
        val config = ui.configureEngineGame(
            baseParams.copy(errorMessage = errorMessage)
        ).getOrNull() ?: return

        if (eng.configure(config.engineSettings) is Result.Failure) {
            errorMessage = "Failed to configure engine"
            continue
        }

        val boardCaps = board.getCapabilities().getOrNull()
        if (boardCaps == null) {
            errorMessage = "Failed to read board capabilities"
            continue
        }

        val createResult = gameController.create(CreateGameParams(
            timeControl = config.timeControl,
        ))
        if (createResult is Result.Failure) {
            errorMessage = "Failed to create game: ${createResult.error.message}"
            continue
        }
        val gameId = (createResult as Result.Success).value.gameId

        eng.newGame()

        session = GameSession(
            gameId = gameId,
            mode = GameMode.ENGINE,
            boardLevel = boardCaps.level,
            playerColor = config.playerColor,
            timeControl = config.timeControl,
            engine = eng,
        )
        notifyGameStarted(GameStartedInfo(
            gameId = gameId,
            mode = GameMode.ENGINE,
            playerColor = config.playerColor,
            timeControl = config.timeControl,
            whiteName = null,
            blackName = null,
            initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        ))
        startModuleCollectors(session!!)
        transitionToAwaitingMove()
        return
    }
}

internal suspend fun Orchestrator.setupRemote() {
    val rem = selectRemote() ?: return

    val caps = rem.getCapabilities().getOrNull() ?: run {
        ui.displayError("Failed to read remote capabilities")
        return
    }

    val baseParams = ConfigureRemoteGameParams(
        platform = caps.platform,
        parameters = caps.parameters,
        seekParameters = caps.seekParameters,
    )
    var errorMessage: String? = null

    while (true) {
        val config = ui.configureRemoteGame(
            baseParams.copy(errorMessage = errorMessage)
        ).getOrNull() ?: return

        if (rem.configure(config.platformSettings) is Result.Failure) {
            errorMessage = "Failed to configure remote platform"
            continue
        }

        val boardCaps = board.getCapabilities().getOrNull()
        if (boardCaps == null) {
            errorMessage = "Failed to read board capabilities"
            continue
        }

        if (rem.seekGame(config.seekSettings) is Result.Failure) {
            errorMessage = "Failed to seek game"
            continue
        }

        session = GameSession(
            gameId = "", // Will be set when game starts
            mode = GameMode.REMOTE,
            boardLevel = boardCaps.level,
            remote = rem,
        )
        startModuleCollectors(session!!)
        state = OrchestratorState.SeekingGame
        ui.onSeekingGame()
        return
    }
}

private suspend fun Orchestrator.selectEngine(): EngineAdapterModule? {
    if (engines.isEmpty()) return null
    if (engines.size == 1) return engines.single()
    val infos = engines.map { eng ->
        val caps = eng.getCapabilities().getOrNull()
        EngineInfo(name = caps?.name ?: "Unknown", author = caps?.author ?: "Unknown")
    }
    val selected = ui.selectEngine(infos).getOrNull() ?: return null
    return engines.getOrNull(infos.indexOfFirst { it.name == selected })
}

private suspend fun Orchestrator.selectRemote(): RemoteAdapterModule? {
    if (remotes.isEmpty()) return null
    if (remotes.size == 1) return remotes.single()
    val infos = remotes.map { rem ->
        val caps = rem.getCapabilities().getOrNull()
        PlatformInfo(platform = caps?.platform ?: "Unknown", features = caps?.features ?: emptyList())
    }
    val selected = ui.selectRemotePlatform(infos).getOrNull() ?: return null
    return remotes.getOrNull(infos.indexOfFirst { it.platform == selected })
}
