package scp.game

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

class GameController : GameControllerModule {

    private val _gameEnded = MutableSharedFlow<GameEndedEvent>()
    override val gameEnded: SharedFlow<GameEndedEvent> = _gameEnded

    private val _flagFall = MutableSharedFlow<FlagFallEvent>()
    override val flagFall: SharedFlow<FlagFallEvent> = _flagFall

    override suspend fun create(params: CreateGameParams): Result<GameError, CreateGameResult> {
        TODO("Not yet implemented")
    }

    override suspend fun getState(gameId: String): Result<GameError, GameState> {
        TODO("Not yet implemented")
    }

    override suspend fun submitMove(params: SubmitMoveParams): Result<GameError, SubmitMoveResult> {
        TODO("Not yet implemented")
    }

    override suspend fun resign(gameId: String, color: Color): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun offerDraw(gameId: String, color: Color): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun respondDraw(gameId: String, accept: Boolean): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun claimDraw(gameId: String, reason: DrawClaimReason): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun abort(gameId: String): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun getHistory(gameId: String): Result<GameError, GameHistory> {
        TODO("Not yet implemented")
    }

    override suspend fun setResult(params: SetResultParams): Result<GameError, Unit> {
        TODO("Not yet implemented")
    }
}
