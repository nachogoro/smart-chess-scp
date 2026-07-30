package scp.orchestrator

import scp.protocol.*

data class GameSession(
    val gameId: String,
    val mode: GameMode,
    val boardLevel: BoardCapabilityLevel,
    val playerColor: Color? = null,
    val timeControl: TimeControl? = null,
    val clocks: Clocks? = null,
    /**
     * The position after the last move the game controller accepted, whoever played it.
     *
     * Null only between the session being created and the first transition, which happens before
     * a game is playable. A UI that draws the position reads it from here: the orchestrator is the
     * only party that hears about every move, so anywhere else would miss some of them.
     */
    val fen: String? = null,
    val whiteName: String? = null,
    val blackName: String? = null,
    val engine: EngineAdapterModule? = null,
    val remote: RemoteAdapterModule? = null,
)
