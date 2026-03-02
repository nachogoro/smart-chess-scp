package scp.engine.stockfish

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import scp.protocol.*

class StockfishAdapter : EngineAdapterModule {

    private val _errors = MutableSharedFlow<EngineErrorEvent>()
    override val errors: SharedFlow<EngineErrorEvent> = _errors

    override suspend fun getCapabilities(): Result<EngineError, EngineCapabilities> {
        TODO("Not yet implemented")
    }

    override suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> {
        TODO("Not yet implemented")
    }

    override suspend fun stop(): Result<EngineError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun newGame(): Result<EngineError, Unit> {
        TODO("Not yet implemented")
    }
}
