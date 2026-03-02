package scp.remote.lichess

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

class LichessAdapter : RemoteAdapterModule {

    private val _gameStarted = MutableSharedFlow<GameStartedEvent>()
    override val gameStarted: SharedFlow<GameStartedEvent> = _gameStarted

    private val _opponentMove = MutableSharedFlow<OpponentMoveEvent>()
    override val opponentMove: SharedFlow<OpponentMoveEvent> = _opponentMove

    private val _gameEnded = MutableSharedFlow<RemoteGameEndedEvent>()
    override val gameEnded: SharedFlow<RemoteGameEndedEvent> = _gameEnded

    private val _drawOffered = MutableSharedFlow<Unit>()
    override val drawOffered: SharedFlow<Unit> = _drawOffered

    private val _errors = MutableSharedFlow<RemoteErrorEvent>()
    override val errors: SharedFlow<RemoteErrorEvent> = _errors

    override suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities> {
        TODO("Not yet implemented")
    }

    override suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult> {
        TODO("Not yet implemented")
    }

    override suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun cancelSeek(): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun sendMove(move: String): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun resign(): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun offerDraw(): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun disconnect(): Result<RemoteError, Unit> {
        TODO("Not yet implemented")
    }
}
