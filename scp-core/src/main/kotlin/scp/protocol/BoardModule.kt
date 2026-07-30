package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface BoardModule {
    suspend fun getCapabilities(): Result<BoardError, BoardCapabilities>
    suspend fun getState(): Result<BoardError, BoardState>

    val errors: SharedFlow<BoardErrorEvent>
}

data class BoardCapabilities(val level: BoardCapabilityLevel)

data class BoardState(val squares: Map<String, SquareState>)

sealed class BoardError(override val code: String, override val message: String) : ModuleError {
    class Disconnected(message: String = "Board disconnected") : BoardError("DISCONNECTED", message)
    class SensorFailure(message: String = "Sensor failure") : BoardError("SENSOR_FAILURE", message)
    class NotReady(message: String = "Board not ready") : BoardError("NOT_READY", message)
}

data class BoardErrorEvent(override val code: String, override val message: String) : ModuleError
