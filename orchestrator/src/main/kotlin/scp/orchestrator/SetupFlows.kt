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

internal suspend fun Orchestrator.setupEngine() {
    val eng = selectEngine() ?: return

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
        engine = eng,
    )
    startModuleCollectors(session!!)
    transitionToAwaitingMove()
}

internal suspend fun Orchestrator.setupRemote() {
    val rem = selectRemote() ?: return

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
        remote = rem,
    )
    startModuleCollectors(session!!)
    state = OrchestratorState.SeekingGame
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
