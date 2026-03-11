package scp.orchestrator

import scp.protocol.*

data class GameSession(
    val gameId: String,
    val mode: GameMode,
    val boardLevel: BoardCapabilityLevel,
    val playerColor: Color? = null,
    val timeControl: TimeControl? = null,
    val clocks: Clocks? = null,
    val whiteName: String? = null,
    val blackName: String? = null,
    val engine: EngineAdapterModule? = null,
    val remote: RemoteAdapterModule? = null,
)
