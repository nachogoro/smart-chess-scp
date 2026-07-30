package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface RemoteAdapterModule {
    suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities>
    suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult>
    suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit>
    suspend fun cancelSeek(): Result<RemoteError, Unit>
    suspend fun sendMove(move: String): Result<RemoteError, Unit>
    suspend fun resign(): Result<RemoteError, Unit>

    /**
     * Abort the current game on the remote platform.
     *
     * Distinct from [resign]: platforms allow it only before a game is properly under way, and it
     * ends in no result rather than a loss. Like every other way a remote game can end, the
     * outcome arrives on [gameEnded] rather than from this call (PROTOCOL.md §6.3).
     */
    suspend fun abort(): Result<RemoteError, Unit>
    suspend fun offerDraw(): Result<RemoteError, Unit>
    suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit>
    suspend fun disconnect(): Result<RemoteError, Unit>

    val gameStarted: SharedFlow<GameStartedEvent>
    val opponentMove: SharedFlow<OpponentMoveEvent>
    val gameEnded: SharedFlow<RemoteGameEndedEvent>
    val drawOffered: SharedFlow<Unit>
    /**
     * Failures the platform or the connection to it reports of its own accord.
     *
     * Carries the error itself: a PLATFORM_ERROR keeps the status code and the platform's own
     * words, which is the one text §2.6 lets a UI show as it stands, and INVALID_PARAMS keeps the
     * field it was about. Flattened to a code and a diagnostic, none of that survived, and the
     * diagnostic is the one thing a UI must not display.
     */
    val errors: SharedFlow<RemoteError>
}

data class RemoteCapabilities(
    val platform: String,
    val features: List<String>,
    val parameters: List<ParameterDescriptor>,
    val seekParameters: List<ParameterDescriptor>,
)

data class ConfigureResult(val user: RemoteUser?)

data class RemoteUser(val username: String, val rating: Int?)

data class GameStartedEvent(
    val gameId: String,
    val color: Color,
    val opponent: String,
    val fen: String,
    val timeControl: TimeControl?,
)

data class OpponentMoveEvent(
    val move: String,
    val clocks: Clocks?,
)

data class RemoteGameEndedEvent(
    val result: GameResult,
    /**
     * How the server ended the game.
     *
     * An adapter classifies rather than describes. A status with no counterpart in [Termination]
     * is reported as [Termination.Other] carrying the server's own identifier and wording, not as
     * the nearest code that happens to fit.
     */
    val termination: Termination,
)

sealed class RemoteError(
    override val code: String,
    override val diagnostic: String? = null,
) : ModuleError {
    class AuthFailed : RemoteError("AUTH_FAILED")

    /** [parameter] is the descriptor's name, so a UI can point at the field. */
    class InvalidParams(val parameter: String? = null) : RemoteError("INVALID_PARAMS")

    /**
     * An operational request arrived before the configuration handshake completed.
     *
     * Named for the handshake rather than the connection because that is what §5 requires be
     * reported, and what a caller has to do about it: configure, not reconnect.
     */
    class NotConfigured : RemoteError("NOT_CONFIGURED")

    /** No game is in progress on the platform, so there is nothing to act on. */
    class NoActiveGame : RemoteError("NO_ACTIVE_GAME")

    /**
     * The platform answered, and its answer was no.
     *
     * [serverText] is the platform's own words. It is the one kind of text §2.6 lets a UI show as
     * it stands, because we neither own that vocabulary nor can enumerate it - so it is a field of
     * its own rather than a [diagnostic], which a UI must not display.
     */
    class PlatformError(
        val statusCode: Int? = null,
        val serverText: String? = null,
    ) : RemoteError("PLATFORM_ERROR")

    /**
     * The platform could not be reached at all.
     *
     * Distinct from [PlatformError] because the difference is the player's: a server that refused
     * something is not a network that is down.
     */
    class Unreachable(diagnostic: String? = null) : RemoteError("UNREACHABLE", diagnostic)

    /**
     * The connection carrying the game was lost.
     *
     * Its own code because the game is still live on the platform and may be resumable, which is
     * not true of the others (PROTOCOL.md §7.5).
     */
    class ConnectionLost(diagnostic: String? = null) : RemoteError("CONNECTION_LOST", diagnostic)
}

