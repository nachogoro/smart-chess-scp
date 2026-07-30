package scp.remote.lichess

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/**
 * Contract tests for [LichessHttpApi].
 *
 * Uses MockWebServer to verify that the HTTP client sends correct requests
 * (paths, headers, form bodies) and correctly parses responses (JSON, NDJSON,
 * error status codes).
 */
class LichessHttpApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LichessHttpApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = LichessHttpApi(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // -- getAccount --

    @Test
    fun `getAccount sends GET with auth header and parses response`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/json")
            // Lichess still sends perfs; the model no longer asks for them, and
            // ignoreUnknownKeys means the extra field is simply not read.
            .setBody("""{"id":"testuser","username":"TestUser","perfs":{"rapid":{"games":42,"rating":1500}}}"""))

        val account = api.getAccount("my-token")

        assertEquals("testuser", account.id)
        assertEquals("TestUser", account.username)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/account", request.path)
        assertEquals("Bearer my-token", request.getHeader("Authorization"))
    }

    @Test
    fun `getAccount throws on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val ex = assertFailsWith<LichessApiException> { api.getAccount("bad-token") }
        assertEquals(401, ex.statusCode)
    }

    // -- streamEvents --

    @Test
    fun `streamEvents sends GET with ndjson accept header`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody("""{"type":"gameStart","game":{"fullId":"abc123full","gameId":"abc123"}}""" + "\n"))

        val lines = api.streamEvents("my-token").toList()

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("gameStart"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/stream/event", request.path)
        assertEquals("Bearer my-token", request.getHeader("Authorization"))
        assertEquals("application/x-ndjson", request.getHeader("Accept"))
    }

    @Test
    fun `streamEvents skips blank lines`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody("\n\n" + """{"type":"gameStart","game":{"fullId":"x","gameId":"x"}}""" + "\n\n"))

        val lines = api.streamEvents("my-token").toList()

        assertEquals(1, lines.size)
    }

    @Test
    fun `streamEvents throws on error status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertFailsWith<LichessApiException> {
            api.streamEvents("bad-token").toList()
        }
    }

    // -- streamGame --

    @Test
    fun `streamGame sends correct path with gameId`() = runTest {
        val gameFull = """{"type":"gameFull","id":"abc123","white":{"id":"w","name":"W"},"black":{"id":"b","name":"B"},"initialFen":"startpos","state":{"type":"gameState","moves":"","wtime":900000,"btime":900000,"status":"started"}}"""
        val gameState = """{"type":"gameState","moves":"e2e4","wtime":895000,"btime":900000,"status":"started"}"""

        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody(gameFull + "\n" + gameState + "\n"))

        val lines = api.streamGame("my-token", "abc123").toList()

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("gameFull"))
        assertTrue(lines[1].contains("gameState"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/board/game/stream/abc123", request.path)
    }

    // -- createSeek --

    @Test
    fun `createSeek sends POST with form body`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody("\n"))

        api.createSeek("my-token", time = 15, increment = 10, rated = true, color = "white").toList()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/board/seek", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("time=15"))
        assertTrue(body.contains("increment=10"))
        assertTrue(body.contains("rated=true"))
        assertTrue(body.contains("color=white"))
    }

    @Test
    fun `createSeek omits color when random`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody("\n"))

        api.createSeek("my-token", time = 15, increment = 10, rated = false, color = "random").toList()

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("color="))
    }

    // -- createChallenge --

    @Test
    fun `createChallenge sends POST to correct path with form body`() = runTest {
        server.enqueue(MockResponse()
            .addHeader("Content-Type", "application/x-ndjson")
            .setBody("\n"))

        api.createChallenge("my-token", username = "friend1", time = 10, increment = 5, rated = false, color = "black").toList()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/challenge/friend1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("rated=false"))
        assertTrue(body.contains("clock.limit=600"), "Expected clock.limit=600 (10 min * 60), got: $body")
        assertTrue(body.contains("clock.increment=5"))
        assertTrue(body.contains("color=black"))
    }

    // -- makeMove --

    @Test
    fun `makeMove sends POST to correct path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.makeMove("my-token", "game1", "e2e4")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/board/game/game1/move/e2e4", request.path)
        assertEquals("Bearer my-token", request.getHeader("Authorization"))
    }

    @Test
    fun `makeMove throws on 400`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("illegal move"))

        val ex = assertFailsWith<LichessApiException> { api.makeMove("my-token", "game1", "e2e5") }
        assertEquals(400, ex.statusCode)
    }

    // -- resign --

    @Test
    fun `resign sends POST to correct path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.resign("my-token", "game1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/board/game/game1/resign", request.path)
    }

    // -- offerDraw --

    @Test
    fun `offerDraw sends POST to draw yes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.offerDraw("my-token", "game1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/board/game/game1/draw/yes", request.path)
    }

    // -- respondDraw --

    @Test
    fun `respondDraw accept sends POST to draw yes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.respondDraw("my-token", "game1", accept = true)

        assertEquals("/api/board/game/game1/draw/yes", server.takeRequest().path)
    }

    @Test
    fun `respondDraw decline sends POST to draw no`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.respondDraw("my-token", "game1", accept = false)

        assertEquals("/api/board/game/game1/draw/no", server.takeRequest().path)
    }

    // -- abort --

    @Test
    fun `abort sends POST to correct path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.abort("my-token", "game1")

        assertEquals("/api/board/game/game1/abort", server.takeRequest().path)
    }
}
