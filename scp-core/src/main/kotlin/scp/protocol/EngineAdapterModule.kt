package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface EngineAdapterModule {
    suspend fun getCapabilities(): Result<EngineError, EngineCapabilities>
    suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit>
    suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult>
    suspend fun stop(): Result<EngineError, Unit>
    suspend fun newGame(): Result<EngineError, Unit>

    val errors: SharedFlow<EngineErrorEvent>
}

data class EngineCapabilities(
    val name: String,
    val author: String,
    val parameters: List<ParameterDescriptor>,
)

data class GetBestMoveParams(
    val fen: String,
    val moves: List<String>? = null,
    val timeMs: Long? = null,
    val maxDepth: Int? = null,
)

data class BestMoveResult(
    val move: String,
    val score: Score? = null,
    val depth: Int? = null,
    val pv: List<String>? = null,
)

sealed class EngineError(val code: String, val message: String) {
    class EngineCrashed(message: String = "Engine crashed") : EngineError("ENGINE_CRASHED", message)
    class InvalidPosition(message: String = "Invalid position") : EngineError("INVALID_POSITION", message)
    class NotConfigured(message: String = "Engine not configured") : EngineError("NOT_CONFIGURED", message)
    class InvalidParams(message: String = "Invalid parameters") : EngineError("INVALID_PARAMS", message)
}

data class EngineErrorEvent(val code: String, val message: String)
