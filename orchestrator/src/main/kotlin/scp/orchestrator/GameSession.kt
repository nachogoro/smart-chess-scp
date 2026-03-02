package scp.orchestrator

import scp.protocol.*

data class GameSession(
    val gameId: String,
    val mode: GameMode,
    val boardLevel: BoardCapabilityLevel,
    val playerColor: Color? = null,
    val timeControl: TimeControl? = null,
)
