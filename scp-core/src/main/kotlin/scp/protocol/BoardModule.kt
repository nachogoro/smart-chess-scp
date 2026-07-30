package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface BoardModule {
    suspend fun getCapabilities(): Result<BoardError, BoardCapabilities>
    suspend fun getState(): Result<BoardError, BoardState>

    /**
     * Hardware problems the board reports of its own accord.
     *
     * Carries the error itself, so an asynchronous failure says as much as a refused call does:
     * a sensor failure names the squares it could not trust, and a UI can tell the player which
     * pieces to re-seat rather than only that something went wrong.
     */
    val errors: SharedFlow<BoardError>
}

data class BoardCapabilities(val level: BoardCapabilityLevel)

data class BoardState(val squares: Map<String, SquareState>)

sealed class BoardError(
    override val code: String,
    override val diagnostic: String? = null,
) : ModuleError {
    class Disconnected(diagnostic: String? = null) : BoardError("DISCONNECTED", diagnostic)

    /**
     * The board's reading could not be trusted.
     *
     * [unreadableSquares] names the squares at fault, so a UI can tell the player where to look.
     * A board module that cannot narrow it down leaves the list empty.
     */
    class SensorFailure(
        val unreadableSquares: List<String> = emptyList(),
        diagnostic: String? = null,
    ) : BoardError("SENSOR_FAILURE", diagnostic)

    class NotReady(diagnostic: String? = null) : BoardError("NOT_READY", diagnostic)
}

