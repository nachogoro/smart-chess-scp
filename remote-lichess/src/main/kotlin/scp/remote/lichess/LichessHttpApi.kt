package scp.remote.lichess

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.BufferedReader
import java.time.Duration

/**
 * Real HTTP implementation of [LichessApi] using the JDK `HttpClient`.
 *
 * One-shot calls ([getAccount], [makeMove], etc.) use [withContext] on [Dispatchers.IO] to
 * perform blocking I/O without occupying the caller's dispatcher.
 *
 * Streaming calls ([streamEvents], [streamGame], [createSeek], [createChallenge]) return a
 * cold [Flow] that, when collected, opens an HTTP connection and reads NDJSON lines until the
 * server closes the stream. The blocking read loop runs on [Dispatchers.IO] via [flowOn].
 *
 * @param baseUrl Lichess server base URL. Defaults to `https://lichess.org`.
 */
class LichessHttpApi(
    private val baseUrl: String = "https://lichess.org",
) : LichessApi {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAccount(token: String): LichessAccount {
        val response = get(token, "/api/account")
        return json.decodeFromString<LichessAccount>(response)
    }

    override fun streamEvents(token: String): Flow<String> =
        streamNdjson(token, "/api/stream/event")

    override fun streamGame(token: String, gameId: String): Flow<String> =
        streamNdjson(token, "/api/board/game/stream/$gameId")

    override suspend fun createSeek(
        token: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String> {
        val body = buildString {
            append("time=$time")
            append("&increment=$increment")
            append("&rated=$rated")
            if (color != "random") append("&color=$color")
        }
        return streamNdjson(token, "/api/board/seek", body)
    }

    override suspend fun createChallenge(
        token: String,
        username: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String> {
        val body = buildString {
            append("rated=$rated")
            append("&clock.limit=${time * 60}")
            append("&clock.increment=$increment")
            if (color != "random") append("&color=$color")
        }
        return streamNdjson(token, "/api/challenge/$username", body)
    }

    override suspend fun challengeAi(
        token: String,
        level: Int,
        time: Int,
        increment: Int,
        color: String,
    ) {
        val body = buildString {
            append("level=$level")
            append("&clock.limit=${time * 60}")
            append("&clock.increment=$increment")
            if (color != "random") append("&color=$color")
        }
        post(token, "/api/challenge/ai", body)
    }

    override suspend fun makeMove(token: String, gameId: String, move: String) {
        post(token, "/api/board/game/$gameId/move/$move")
    }

    override suspend fun resign(token: String, gameId: String) {
        post(token, "/api/board/game/$gameId/resign")
    }

    override suspend fun offerDraw(token: String, gameId: String) {
        post(token, "/api/board/game/$gameId/draw/yes")
    }

    override suspend fun respondDraw(token: String, gameId: String, accept: Boolean) {
        val value = if (accept) "yes" else "no"
        post(token, "/api/board/game/$gameId/draw/$value")
    }

    override suspend fun abort(token: String, gameId: String) {
        post(token, "/api/board/game/$gameId/abort")
    }

    /** Performs a GET request and returns the response body. */
    private suspend fun get(token: String, path: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw LichessApiException(response.statusCode(), response.body())
        }
        response.body()
    }

    /** Performs a POST request with an optional form body and returns the response body. */
    private suspend fun post(
        token: String,
        path: String,
        body: String = "",
    ): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")

        if (body.isNotEmpty()) {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody())
        }

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw LichessApiException(response.statusCode(), response.body())
        }
        response.body()
    }

    /**
     * Opens a streaming NDJSON connection.
     *
     * Returns a cold [Flow] that, when collected, sends the HTTP request and reads lines from
     * the response body. Each non-blank line is emitted as a flow element. The flow completes
     * when the server closes the connection (i.e. `readLine()` returns null).
     *
     * If [body] is provided, a POST request is sent with the given form-encoded body.
     * Otherwise, a GET request is sent. All I/O runs on [Dispatchers.IO].
     *
     * @param body Form-encoded request body for POST requests, or null for GET.
     */
    private fun streamNdjson(token: String, path: String, body: String? = null): Flow<String> = flow {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/x-ndjson")

        if (body != null) {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.GET()
        }

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw LichessApiException(response.statusCode(), "Stream error: ${response.statusCode()}")
        }

        val reader = BufferedReader(response.body().reader())
        reader.use {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Exception thrown when the Lichess API returns a non-2xx response. */
class LichessApiException(val statusCode: Int, message: String) : Exception(message)
