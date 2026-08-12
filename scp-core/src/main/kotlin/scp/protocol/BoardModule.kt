package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface BoardModule {
    suspend fun getCapabilities(): Result<BoardError, BoardCapabilities>
    suspend fun getState(): Result<BoardError, BoardState>

    /**
     * Carries out [transfers] in the order given, returning once the pieces have stopped moving.
     *
     * Only a board that reports [BoardCapabilities.actuation] moves anything. The default answers
     * the way the protocol has a module answer for a method it does not implement (PROTOCOL.md
     * §9.1), so a board written before actuation existed stays a conformant board without being
     * edited. Callers gate on the declared capability rather than on this failure; it is the
     * backstop for a board whose declaration and implementation disagree.
     */
    suspend fun movePieces(transfers: List<PieceTransfer>): Result<BoardError, Unit> =
        Result.Failure(BoardError.MethodNotFound(diagnostic = "this board does not move pieces"))

    /**
     * Hardware problems the board reports of its own accord.
     *
     * Carries the error itself, so an asynchronous failure says as much as a refused call does:
     * a sensor failure names the squares it could not trust, and a UI can tell the player which
     * pieces to re-seat rather than only that something went wrong.
     */
    val errors: SharedFlow<BoardError>
}

data class BoardCapabilities(
    val level: BoardCapabilityLevel,
    /** Null when the board only senses, which is what a board that says nothing is taken to mean. */
    val actuation: BoardActuation? = null,
)

data class BoardState(val squares: Map<String, SquareState>)

/**
 * What a board that moves pieces is able to do.
 *
 * An object rather than a bare flag because the family is open: a board that can slide a piece
 * between two squares but has nowhere to fetch a queen from can play every move in chess except a
 * promotion. It is independent of [BoardCapabilityLevel], which describes sensing only.
 */
data class BoardActuation(
    /** Whether the board can put down a piece that was not already on it. */
    val canPlacePieces: Boolean = false,
)

/**
 * One piece movement, with no chess in it.
 *
 * The orchestrator decomposes an accepted move into these. The board is never told that a transfer
 * is a capture, a castle or a promotion, because knowing would not help it and getting it wrong
 * would. Order matters: a transfer's destination is empty by the time it runs, which is what makes
 * a capture a [Remove] followed by a [Move] and not the other way round.
 */
sealed class PieceTransfer {
    /** Move whatever stands on [from] to [to]. */
    data class Move(val from: String, val to: String) : PieceTransfer()

    /** Take whatever stands on [from] off the board. */
    data class Remove(val from: String) : PieceTransfer()

    /** Put on [to] a piece that is not currently on the board. Only for a board that can. */
    data class Place(val piece: Piece, val to: String) : PieceTransfer()
}

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

    /**
     * A transfer could not be carried out.
     *
     * [completed] is how many were applied before it, which is the only thing that says where the
     * pieces are: somewhere between the two positions the caller knows about rather than at either.
     * It is also why the transfers must not simply be sent again.
     */
    class ActuationFailed(
        val completed: Int = 0,
        diagnostic: String? = null,
    ) : BoardError("ACTUATION_FAILED", diagnostic)

    /**
     * A transfer the board cannot take, such as a place on a board with nothing to place.
     *
     * The whole request is refused rather than the transfers around it applied, because a
     * half-applied list leaves the pieces in a position nobody can name.
     */
    class InvalidParams(diagnostic: String? = null) : BoardError("INVALID_PARAMS", diagnostic)

    /** The in-process shape of §9.1: what a module answers for a method it does not implement. */
    class MethodNotFound(diagnostic: String? = null) : BoardError("METHOD_NOT_FOUND", diagnostic)
}
