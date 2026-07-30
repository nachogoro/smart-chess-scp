package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface RemoteAdapterModule {
    suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities>
    suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult>
    suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit>
    suspend fun cancelSeek(): Result<RemoteError, Unit>
    suspend fun sendMove(move: String): Result<RemoteError, Unit>
    suspend fun resign(): Result<RemoteError, Unit>
    suspend fun offerDraw(): Result<RemoteError, Unit>
    suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit>
    suspend fun disconnect(): Result<RemoteError, Unit>

    val gameStarted: SharedFlow<GameStartedEvent>
    val opponentMove: SharedFlow<OpponentMoveEvent>
    val gameEnded: SharedFlow<RemoteGameEndedEvent>
    val drawOffered: SharedFlow<Unit>
    val errors: SharedFlow<RemoteErrorEvent>
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

    class NotConnected : RemoteError("NOT_CONNECTED")

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
}

data class RemoteErrorEvent(
    override val code: String,
    override val diagnostic: String? = null,
) : ModuleError
