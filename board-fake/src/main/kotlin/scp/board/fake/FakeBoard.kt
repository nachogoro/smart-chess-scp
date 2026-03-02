package scp.board.fake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

class FakeBoard(
    private val capabilityLevel: BoardCapabilityLevel = BoardCapabilityLevel.FULL,
) : BoardModule {

    private val _errors = MutableSharedFlow<BoardErrorEvent>()
    override val errors: SharedFlow<BoardErrorEvent> = _errors

    private val _squares = mutableMapOf<String, SquareState>()

    fun setSquare(square: String, state: SquareState) {
        _squares[square] = state
    }

    fun setFromFen(fen: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getCapabilities(): Result<BoardError, BoardCapabilities> {
        return Result.Success(BoardCapabilities(capabilityLevel))
    }

    override suspend fun getState(): Result<BoardError, BoardState> {
        return Result.Success(BoardState(_squares.toMap()))
    }
}
