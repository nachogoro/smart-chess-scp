package scp.engine.carballo

import com.alonsoruibal.chess.Board
import com.alonsoruibal.chess.Config
import com.alonsoruibal.chess.Move
import com.alonsoruibal.chess.search.SearchEngine
import com.alonsoruibal.chess.search.SearchObserver
import com.alonsoruibal.chess.search.SearchParameters
import com.alonsoruibal.chess.search.SearchStatusInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import scp.protocol.*

private fun debugLog(msg: String) = scp.protocol.debugLog("[carballo] $msg")

/**
 * [EngineAdapterModule] implementation wrapping the Carballo chess engine.
 *
 * Carballo is a pure Java engine (~2100 Elo) that runs on any JVM platform
 * including Android, with no native dependencies.
 *
 * The engine's [SearchEngine.go] method blocks the calling thread until the
 * search completes. This adapter runs it on [Dispatchers.Default] via
 * [withContext] so that the calling coroutine is not blocked.
 *
 * Configurable parameters:
 * - **UCI_LimitStrength** — Enable strength limiting.
 * - **UCI_Elo** — Target Elo (500–2100) when strength is limited.
 * - **Hash** — Transposition table size in MB (1–256).
 * - **UseBook** — Whether to use the built-in opening book.
 */
class CarballoAdapter : EngineAdapterModule {

    private val _errors = MutableSharedFlow<EngineErrorEvent>()
    override val errors: SharedFlow<EngineErrorEvent> = _errors

    private var engine: SearchEngine? = null
    private var configured = false

    // Configurable settings with defaults matching Carballo's Config
    private var limitStrength = false
    private var elo = DEFAULT_ELO
    private var hashSizeMb = DEFAULT_HASH
    private var useBook = true

    // Last search info, captured via SearchObserver
    private var lastSearchInfo: SearchStatusInfo? = null

    override suspend fun getCapabilities(): Result<EngineError, EngineCapabilities> {
        return Result.Success(
            EngineCapabilities(
                name = ENGINE_NAME,
                author = ENGINE_AUTHOR,
                parameters = listOf(
                    ParameterDescriptor(
                        name = PARAM_LIMIT_STRENGTH,
                        type = ParameterType.CHECK,
                        description = "Limit playing strength",
                        default = false,
                    ),
                    ParameterDescriptor(
                        name = PARAM_ELO,
                        type = ParameterType.SPIN,
                        description = "Target Elo when strength is limited",
                        default = DEFAULT_ELO,
                        min = MIN_ELO,
                        max = MAX_ELO,
                        visibleWhen = VisibleWhen(field = PARAM_LIMIT_STRENGTH, equals = true),
                    ),
                    ParameterDescriptor(
                        name = PARAM_HASH,
                        type = ParameterType.SPIN,
                        description = "Transposition table size in MB",
                        default = DEFAULT_HASH,
                        min = 1,
                        max = 256,
                    ),
                    ParameterDescriptor(
                        name = PARAM_USE_BOOK,
                        type = ParameterType.CHECK,
                        description = "Use opening book",
                        default = true,
                    ),
                ),
            )
        )
    }

    override suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit> {
        for ((key, value) in settings) {
            when (key) {
                PARAM_LIMIT_STRENGTH -> {
                    limitStrength = value as? Boolean
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_LIMIT_STRENGTH must be a boolean"))
                }
                PARAM_ELO -> {
                    val v = (value as? Number)?.toInt()
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_ELO must be an integer"))
                    if (v !in MIN_ELO..MAX_ELO) {
                        return Result.Failure(EngineError.InvalidParams("$PARAM_ELO must be between $MIN_ELO and $MAX_ELO"))
                    }
                    elo = v
                }
                PARAM_HASH -> {
                    val v = (value as? Number)?.toInt()
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_HASH must be an integer"))
                    if (v !in 1..256) {
                        return Result.Failure(EngineError.InvalidParams("$PARAM_HASH must be between 1 and 256"))
                    }
                    hashSizeMb = v
                }
                PARAM_USE_BOOK -> {
                    useBook = value as? Boolean
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_USE_BOOK must be a boolean"))
                }
                else -> return Result.Failure(EngineError.InvalidParams("Unknown parameter: $key"))
            }
        }

        engine = createEngine()
        configured = true
        return Result.Success(Unit)
    }

    override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> {
        val eng = engine
        if (!configured || eng == null) {
            return Result.Failure(EngineError.NotConfigured())
        }

        return try {
            withContext(Dispatchers.Default) {
                // Set up position from FEN. The orchestrator provides fen as the
                // current position and moves as the game history — do NOT replay
                // moves on top of the FEN (they've already been applied).
                debugLog("getBestMove: fen=${params.fen} maxDepth=${params.maxDepth} timeMs=${params.timeMs}")
                eng.board.setFen(params.fen)
                debugLog("getBestMove: board FEN after setup: ${eng.board.fen}")

                // Configure search parameters.
                // When neither depth nor time is specified, use a default depth
                // to prevent Carballo's time management from computing 0ms
                // (it defaults wtime/btime to 0, resulting in no search).
                val searchParams = SearchParameters()
                val maxDepth = params.maxDepth
                val timeMs = params.timeMs
                when {
                    maxDepth != null -> searchParams.depth = maxDepth
                    timeMs != null -> searchParams.moveTime = timeMs.toInt()
                    else -> searchParams.depth = DEFAULT_SEARCH_DEPTH
                }

                // Run search (blocks this thread until complete)
                lastSearchInfo = null
                eng.go(searchParams)

                val bestMove = eng.bestMove
                if (bestMove == 0) {
                    debugLog("getBestMove: no legal moves found")
                    return@withContext Result.Failure(EngineError.InvalidPosition("No legal moves"))
                }

                val moveString = Move.toString(bestMove)
                val info = lastSearchInfo
                val score = info?.let { parseScore(it) }

                val pv = info?.pv
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(" ")

                debugLog("getBestMove: result=$moveString score=$score depth=${info?.depth} pv=$pv")

                Result.Success(
                    BestMoveResult(
                        move = moveString,
                        score = score,
                        depth = info?.depth,
                        pv = pv,
                    )
                )
            }
        } catch (e: Exception) {
            val message = "Engine error: ${e.message}"
            _errors.tryEmit(EngineErrorEvent("ENGINE_CRASHED", message))
            Result.Failure(EngineError.EngineCrashed(message))
        }
    }

    override suspend fun stop(): Result<EngineError, Unit> {
        engine?.stop()
        return Result.Success(Unit)
    }

    override suspend fun newGame(): Result<EngineError, Unit> {
        engine?.init()
        return Result.Success(Unit)
    }

    private fun createEngine(): SearchEngine {
        val config = Config()
        config.setTranspositionTableSize(hashSizeMb)
        config.setUseBook(useBook)
        config.setLimitStrength(limitStrength)
        config.setElo(elo)

        val eng = SearchEngine(config)
        eng.setObserver(object : SearchObserver {
            override fun info(info: SearchStatusInfo) {
                lastSearchInfo = info
            }

            override fun bestMove(bestMove: Int, ponder: Int) {
                // Result is retrieved via engine.getBestMove(), not this callback
            }
        })
        return eng
    }

    private fun parseScore(info: SearchStatusInfo): Score {
        return if (info.isMate) {
            Score(ScoreType.MATE, info.mateIn)
        } else {
            Score(ScoreType.CP, info.score)
        }
    }

    companion object {
        const val ENGINE_NAME = "Carballo 1.8"
        const val ENGINE_AUTHOR = "Alberto Alonso Ruibal"

        const val PARAM_LIMIT_STRENGTH = "UCI_LimitStrength"
        const val PARAM_ELO = "UCI_Elo"
        const val PARAM_HASH = "Hash"
        const val PARAM_USE_BOOK = "UseBook"

        const val DEFAULT_ELO = 2100
        const val MIN_ELO = 500
        const val MAX_ELO = 2100
        const val DEFAULT_HASH = 64
        const val DEFAULT_SEARCH_DEPTH = 10
    }
}
