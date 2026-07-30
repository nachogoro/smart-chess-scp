package scp.protocol

import kotlinx.coroutines.flow.SharedFlow

interface EngineAdapterModule {
    suspend fun getCapabilities(): Result<EngineError, EngineCapabilities>
    suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit>
    suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult>
    suspend fun stop(): Result<EngineError, Unit>
    suspend fun newGame(): Result<EngineError, Unit>

    /** Failures the engine reports of its own accord. Carries the error itself; see [EngineError]. */
    val errors: SharedFlow<EngineError>
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

sealed class EngineError(
    override val code: String,
    override val diagnostic: String? = null,
) : ModuleError {
    class EngineCrashed(diagnostic: String? = null) : EngineError("ENGINE_CRASHED", diagnostic)
    class InvalidPosition(diagnostic: String? = null) : EngineError("INVALID_POSITION", diagnostic)
    class NotConfigured : EngineError("NOT_CONFIGURED")

    /**
     * A setting the engine would not take.
     *
     * [parameter] is the descriptor's name, so a UI can point at the field the player filled in.
     * What was wrong with it is not carried: the UI rendered the field from a ParameterDescriptor
     * and already knows its type and bounds.
     */
    class InvalidParams(val parameter: String? = null) : EngineError("INVALID_PARAMS")
}

