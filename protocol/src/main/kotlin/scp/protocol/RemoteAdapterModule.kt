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
    val fen: String,
    val clocks: Clocks?,
)

data class RemoteGameEndedEvent(
    val result: String,
    val reason: String,
)

sealed class RemoteError(val code: String, val message: String) {
    class AuthFailed(message: String = "Authentication failed") : RemoteError("AUTH_FAILED", message)
    class InvalidParams(message: String = "Invalid parameters") : RemoteError("INVALID_PARAMS", message)
    class NotConnected(message: String = "Not connected") : RemoteError("NOT_CONNECTED", message)
    class PlatformError(message: String = "Platform error") : RemoteError("PLATFORM_ERROR", message)
}

data class RemoteErrorEvent(val code: String, val message: String)
