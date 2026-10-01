package scp.orchestrator

import scp.protocol.*

internal data class GameSession(
    val gameId: String,
    val mode: GameMode,
    val boardLevel: BoardCapabilityLevel,
    /**
     * What the board said it can do about moving pieces, read once at setup.
     *
     * Null when the board only senses, which is every board that does not say otherwise. Kept
     * beside [boardLevel] rather than replacing it with the whole [BoardCapabilities] because
     * inference reads the level on every move and the level is all it wants.
     */
    val boardActuation: BoardActuation? = null,
    val playerColor: Color? = null,
    val timeControl: TimeControl? = null,
    /**
     * The position the game began from.
     *
     * Kept because an engine is given the game rather than a position:
     * `engine.getBestMove` takes a starting FEN and the moves played from it, which is what lets
     * it see repetitions. Null only while a remote game is still being sought,
     * before there is a game to have started anywhere.
     */
    val initialFen: String? = null,
    val whiteName: String? = null,
    val blackName: String? = null,
    /**
     * Who the remote platform says we signed in as, or null where it does not say.
     *
     * Only a remote game has one, and it is known before the colours are: a seek is made under a
     * name, but which side that name plays is the platform's to decide when the game starts.
     */
    val remoteUsername: String? = null,
    val engine: EngineAdapterModule? = null,
    val remote: RemoteAdapterModule? = null,
)
