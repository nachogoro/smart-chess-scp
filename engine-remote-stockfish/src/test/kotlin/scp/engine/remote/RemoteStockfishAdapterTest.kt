package scp.engine.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import scp.protocol.*
import java.net.InetSocketAddress
import kotlin.test.*

class RemoteStockfishAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var adapter: RemoteStockfishAdapter

    private var responseBody: String = "{}"
    private var responseStatus: Int = 200
    private var lastRequestBody: String? = null

    private fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            lastRequestBody = exchange.requestBody.bufferedReader().readText()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val port = server.address.port
        adapter = RemoteStockfishAdapter(apiUrl = "http://localhost:$port")
    }

    private fun tearDown() {
        server.stop(0)
    }

    private inline fun withServer(block: () -> Unit) {
        setUp()
        try {
            block()
        } finally {
            tearDown()
        }
    }

    // --- getCapabilities ---

    @Test
    fun `getCapabilities returns engine info and parameters`() = runTest {
        withServer {
            val result = adapter.getCapabilities()
            val caps = assertIs<Result.Success<EngineCapabilities>>(result).value

            assertEquals("Remote Stockfish (chess-api.com)", caps.name)
            assertEquals("chess-api.com", caps.author)
            assertEquals(2, caps.parameters.size)

            val depth = caps.parameters.first { it.name == "Depth" }
            assertEquals(ParameterType.SPIN, depth.type)
            assertEquals(1, depth.min)
            assertEquals(18, depth.max)
            assertEquals(12, depth.default)

            val time = caps.parameters.first { it.name == "MaxThinkingTime" }
            assertEquals(ParameterType.SPIN, time.type)
            assertEquals(1, time.min)
            assertEquals(100, time.max)
            assertEquals(50, time.default)
        }
    }

    // --- configure ---

    @Test
    fun `configure with valid settings succeeds`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("Depth" to 10, "MaxThinkingTime" to 80))
            assertIs<Result.Success<Unit>>(result)
        }
    }

    @Test
    fun `configure with empty settings succeeds and marks as configured`() = runTest {
        withServer {
            val result = adapter.configure(emptyMap())
            assertIs<Result.Success<Unit>>(result)

            // Verify configured by making a successful getBestMove call
            responseBody = """{"move":"e2e4","depth":12}"""
            val moveResult = adapter.getBestMove(GetBestMoveParams(fen = "startpos"))
            assertIs<Result.Success<BestMoveResult>>(moveResult)
        }
    }

    @Test
    fun `configure with depth 0 fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("Depth" to 0))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidParams>(error)
        }
    }

    @Test
    fun `configure with depth 19 fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("Depth" to 19))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidParams>(error)
        }
    }

    @Test
    fun `configure with MaxThinkingTime 0 fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("MaxThinkingTime" to 0))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidParams>(error)
        }
    }

    @Test
    fun `configure with MaxThinkingTime 101 fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("MaxThinkingTime" to 101))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidParams>(error)
        }
    }

    @Test
    fun `configure with non-numeric value fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("Depth" to "fast"))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidParams>(error)
        }
    }

    @Test
    fun `configure with unknown parameter fails`() = runTest {
        withServer {
            val result = adapter.configure(mapOf("Threads" to 4))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            // The parameter is named rather than described, so a UI can point at the field.
            assertEquals("Threads", assertIs<EngineError.InvalidParams>(error).parameter)
        }
    }

    // --- getBestMove ---

    @Test
    fun `getBestMove before configure fails with NotConfigured`() = runTest {
        withServer {
            val result = adapter.getBestMove(GetBestMoveParams(fen = "startpos"))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.NotConfigured>(error)
        }
    }

    @Test
    fun `getBestMove parses full response`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{
                "move": "e2e4",
                "centipawns": "35",
                "depth": 14,
                "continuationArr": ["e7e5", "g1f3", "b8c6"]
            }"""

            val result = adapter.getBestMove(
                GetBestMoveParams(fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            )
            val best = assertIs<Result.Success<BestMoveResult>>(result).value

            assertEquals("e2e4", best.move)
            assertEquals(Score(ScoreType.CP, 35), best.score)
            assertEquals(14, best.depth)
            assertEquals(listOf("e7e5", "g1f3", "b8c6"), best.pv)
        }
    }

    @Test
    fun `getBestMove parses mate score`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"move":"d1h5","mate":3,"depth":10}"""

            val result = adapter.getBestMove(GetBestMoveParams(fen = "some-fen"))
            val best = assertIs<Result.Success<BestMoveResult>>(result).value

            assertEquals("d1h5", best.move)
            assertEquals(Score(ScoreType.MATE, 3), best.score)
        }
    }

    @Test
    fun `getBestMove with minimal response returns nulls for optional fields`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"move":"a2a3"}"""

            val result = adapter.getBestMove(GetBestMoveParams(fen = "some-fen"))
            val best = assertIs<Result.Success<BestMoveResult>>(result).value

            assertEquals("a2a3", best.move)
            assertNull(best.score)
            assertNull(best.depth)
            assertNull(best.pv)
        }
    }

    @Test
    fun `getBestMove with empty move returns InvalidPosition`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"move":""}"""

            val result = adapter.getBestMove(GetBestMoveParams(fen = "bad-fen"))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidPosition>(error)
        }
    }

    @Test
    fun `getBestMove with missing move returns InvalidPosition`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"text":"no move field"}"""

            val result = adapter.getBestMove(GetBestMoveParams(fen = "bad-fen"))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.InvalidPosition>(error)
        }
    }

    @Test
    fun `getBestMove on HTTP 500 returns EngineCrashed`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseStatus = 500
            responseBody = "Internal Server Error"

            val result = adapter.getBestMove(GetBestMoveParams(fen = "some-fen"))
            val error = assertIs<Result.Failure<EngineError>>(result).error
            // The status is developer-facing detail, not an explanation for the player.
            assertTrue(assertIs<EngineError.EngineCrashed>(error).diagnostic!!.contains("500"))
        }
    }

    @Test
    fun `getBestMove coerces maxDepth to API limits`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"move":"e2e4","depth":18}"""

            // maxDepth 25 should be clamped to 18
            adapter.getBestMove(GetBestMoveParams(fen = "startpos", maxDepth = 25))

            val request = org.json.JSONObject(lastRequestBody!!)
            assertEquals(18, request.getInt("depth"))
        }
    }

    @Test
    fun `getBestMove coerces timeMs to API limits`() = runTest {
        withServer {
            adapter.configure(emptyMap())
            responseBody = """{"move":"e2e4"}"""

            // timeMs 500 should be clamped to 100
            adapter.getBestMove(GetBestMoveParams(fen = "startpos", timeMs = 500))

            val request = org.json.JSONObject(lastRequestBody!!)
            assertEquals(100, request.getInt("maxThinkingTime"))
        }
    }

    @Test
    fun `getBestMove uses configured defaults when params not specified`() = runTest {
        withServer {
            adapter.configure(mapOf("Depth" to 5, "MaxThinkingTime" to 20))
            responseBody = """{"move":"e2e4"}"""

            adapter.getBestMove(GetBestMoveParams(fen = "startpos"))

            val request = org.json.JSONObject(lastRequestBody!!)
            assertEquals(5, request.getInt("depth"))
            assertEquals(20, request.getInt("maxThinkingTime"))
        }
    }

    @Test
    fun `getBestMove overrides configured defaults with per-request params`() = runTest {
        withServer {
            adapter.configure(mapOf("Depth" to 5, "MaxThinkingTime" to 20))
            responseBody = """{"move":"e2e4"}"""

            adapter.getBestMove(GetBestMoveParams(fen = "startpos", maxDepth = 15, timeMs = 80))

            val request = org.json.JSONObject(lastRequestBody!!)
            assertEquals(15, request.getInt("depth"))
            assertEquals(80, request.getInt("maxThinkingTime"))
        }
    }

    // --- stop ---

    @Test
    fun `stop always returns EngineCrashed failure`() = runTest {
        withServer {
            val result = adapter.stop()
            val error = assertIs<Result.Failure<EngineError>>(result).error
            assertIs<EngineError.EngineCrashed>(error)
        }
    }

    // --- newGame ---

    @Test
    fun `newGame always returns Success`() = runTest {
        withServer {
            val result = adapter.newGame()
            assertIs<Result.Success<Unit>>(result)
        }
    }

    // --- error events ---

    @Test
    fun `a failed request reaches a subscribed collector`() = runTest {
        // The flow used to be a rendezvous one emitted into with tryEmit, which reports failure
        // rather than suspending - so the event was dropped unless the collector happened to be
        // parked in collect at that instant, and engine.error never arrived at all.
        val unreachable = RemoteStockfishAdapter(apiUrl = "http://127.0.0.1:1/v1")
        val seen = mutableListOf<EngineError>()
        val collector = launch { unreachable.errors.collect { seen += it } }
        yield()

        unreachable.configure(emptyMap())
        assertIs<Result.Failure<EngineError>>(unreachable.getBestMove(GetBestMoveParams(fen = START_FEN)))
        yield()

        assertEquals(1, seen.size, "the failure should have been announced on engine.error")
        assertEquals("ENGINE_CRASHED", seen.single().code)
        collector.cancel()
    }

    private companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
