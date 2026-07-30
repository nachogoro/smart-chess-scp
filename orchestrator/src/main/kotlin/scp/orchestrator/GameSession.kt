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
    /**
     * The position the game began from.
     *
     * Kept alongside the current one because an engine is given the game rather than a position:
     * `engine.getBestMove` takes a starting FEN and the moves played from it, which is what lets
     * it see repetitions (PROTOCOL.md §4.3). Null only while a remote game is still being sought,
     * before there is a game to have started anywhere.
     */
    val initialFen: String? = null,
    val whiteName: String? = null,
    val blackName: String? = null,
    val engine: EngineAdapterModule? = null,
    val remote: RemoteAdapterModule? = null,
)
