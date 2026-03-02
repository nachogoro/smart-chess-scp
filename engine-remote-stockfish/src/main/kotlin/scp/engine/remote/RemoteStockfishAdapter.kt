package scp.engine.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import scp.protocol.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class RemoteStockfishAdapter(
    private val apiUrl: String = "https://chess-api.com/v1",
) : EngineAdapterModule {

    private val _errors = MutableSharedFlow<EngineErrorEvent>()
    override val errors: SharedFlow<EngineErrorEvent> = _errors

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private var configured = false
    private var depth: Int = DEFAULT_DEPTH
    private var maxThinkingTimeMs: Int = DEFAULT_THINKING_TIME_MS

    override suspend fun getCapabilities(): Result<EngineError, EngineCapabilities> {
        return Result.Success(
            EngineCapabilities(
                name = "Remote Stockfish (chess-api.com)",
                author = "chess-api.com",
                parameters = listOf(
                    ParameterDescriptor(
                        name = PARAM_DEPTH,
                        type = ParameterType.SPIN,
                        description = "Search depth (higher = stronger but slower)",
                        default = DEFAULT_DEPTH,
                        min = 1,
                        max = 18,
                    ),
                    ParameterDescriptor(
                        name = PARAM_THINKING_TIME,
                        type = ParameterType.SPIN,
                        description = "Maximum thinking time in milliseconds",
                        default = DEFAULT_THINKING_TIME_MS,
                        min = 1,
                        max = 100,
                    ),
                ),
            )
        )
    }

    override suspend fun configure(settings: Map<String, Any>): Result<EngineError, Unit> {
        for ((key, value) in settings) {
            when (key) {
                PARAM_DEPTH -> {
                    val v = (value as? Number)?.toInt()
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_DEPTH must be an integer"))
                    if (v !in 1..18) {
                        return Result.Failure(EngineError.InvalidParams("$PARAM_DEPTH must be between 1 and 18"))
                    }
                    depth = v
                }
                PARAM_THINKING_TIME -> {
                    val v = (value as? Number)?.toInt()
                        ?: return Result.Failure(EngineError.InvalidParams("$PARAM_THINKING_TIME must be an integer"))
                    if (v !in 1..100) {
                        return Result.Failure(EngineError.InvalidParams("$PARAM_THINKING_TIME must be between 1 and 100"))
                    }
                    maxThinkingTimeMs = v
                }
                else -> return Result.Failure(EngineError.InvalidParams("Unknown parameter: $key"))
            }
        }
        configured = true
        return Result.Success(Unit)
    }

    override suspend fun getBestMove(params: GetBestMoveParams): Result<EngineError, BestMoveResult> {
        if (!configured) {
            return Result.Failure(EngineError.NotConfigured())
        }

        val requestDepth = params.maxDepth?.coerceIn(1, 18) ?: depth
        val requestTime = params.timeMs?.coerceIn(1, 100)?.toInt() ?: maxThinkingTimeMs

        val body = JSONObject().apply {
            put("fen", params.fen)
            put("depth", requestDepth)
            put("maxThinkingTime", requestTime)
        }

        return try {
            val response = post(body)

            val move = response.optString("move", "")
            if (move.isEmpty()) {
                return Result.Failure(EngineError.InvalidPosition("API returned no move"))
            }

            val score = parseScore(response)
            val pv = response.optJSONArray("continuationArr")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }

            Result.Success(
                BestMoveResult(
                    move = move,
                    score = score,
                    depth = response.optInt("depth", 0).takeIf { it > 0 },
                    pv = pv?.takeIf { it.isNotEmpty() },
                )
            )
        } catch (e: Exception) {
            val message = "API request failed: ${e.message}"
            _errors.tryEmit(EngineErrorEvent("ENGINE_CRASHED", message))
            Result.Failure(EngineError.EngineCrashed(message))
        }
    }

    override suspend fun stop(): Result<EngineError, Unit> {
        // Remote API doesn't support stopping mid-search; requests are short-lived.
        return Result.Failure(EngineError.EngineCrashed("Cannot stop remote API request"))
    }

    override suspend fun newGame(): Result<EngineError, Unit> {
        // No state to reset — the remote API is stateless.
        return Result.Success(Unit)
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("HTTP ${response.statusCode()}: ${response.body()}")
        }

        JSONObject(response.body())
    }

    private fun parseScore(response: JSONObject): Score? {
        val mate = response.opt("mate")
        if (mate != null && mate != JSONObject.NULL) {
            return Score(ScoreType.MATE, (mate as Number).toInt())
        }

        val centipawns = response.optString("centipawns", "")
        if (centipawns.isNotEmpty()) {
            return centipawns.toIntOrNull()?.let { Score(ScoreType.CP, it) }
        }

        return null
    }

    companion object {
        const val PARAM_DEPTH = "Depth"
        const val PARAM_THINKING_TIME = "MaxThinkingTime"
        private const val DEFAULT_DEPTH = 12
        private const val DEFAULT_THINKING_TIME_MS = 50
    }
}
