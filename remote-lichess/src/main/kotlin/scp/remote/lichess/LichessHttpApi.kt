package scp.remote.lichess

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real HTTP implementation of [LichessApi] using OkHttp.
 *
 * OkHttp is used instead of the JDK `java.net.http.HttpClient` so the same module runs on both
 * the JVM desktop and Android (the JDK HTTP client is not available on Android below API 34).
 *
 * One-shot calls ([getAccount], [makeMove], etc.) use [withContext] on [Dispatchers.IO] to
 * perform blocking I/O without occupying the caller's dispatcher.
 *
 * Streaming calls ([streamEvents], [streamGame], [createSeek], [createChallenge]) return a
 * cold [Flow] that, when collected, opens an HTTP connection and reads NDJSON lines until the
 * server closes the stream. The blocking read loop runs on [Dispatchers.IO] via [flowOn]. The
 * underlying OkHttp call is cancelled when the collecting coroutine is cancelled, so streams do
 * not leak connections.
 *
 * @param baseUrl Lichess server base URL. Defaults to `https://lichess.org`.
 */
class LichessHttpApi(
    private val baseUrl: String = "https://lichess.org",
) : LichessApi {

    // Client for short one-shot requests: bounded read timeout.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Client for long-lived NDJSON streams: no read timeout (Lichess holds the connection open,
    // sending periodic keep-alive newlines). Cancellation is handled by cancelling the call.
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

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
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LichessApiException(response.code, responseBody)
            }
            responseBody
        }
    }

    /** Performs a POST request with an optional form body and returns the response body. */
    private suspend fun post(
        token: String,
        path: String,
        body: String = "",
    ): String = withContext(Dispatchers.IO) {
        val requestBody: RequestBody = if (body.isNotEmpty()) {
            body.toRequestBody(formMediaType)
        } else {
            ByteArray(0).toRequestBody(null)
        }

        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LichessApiException(response.code, responseBody)
            }
            responseBody
        }
    }

    /**
     * Opens a streaming NDJSON connection.
     *
     * Returns a cold [Flow] that, when collected, sends the HTTP request and reads lines from
     * the response body. Each non-blank line is emitted as a flow element. The flow completes
     * when the server closes the connection (i.e. `readUtf8Line()` returns null).
     *
     * If [body] is provided, a POST request is sent with the given form-encoded body.
     * Otherwise, a GET request is sent. All I/O runs on [Dispatchers.IO]. When the collecting
     * coroutine is cancelled, the underlying call is cancelled so the blocking read unblocks.
     *
     * @param body Form-encoded request body for POST requests, or null for GET.
     */
    private fun streamNdjson(token: String, path: String, body: String? = null): Flow<String> = flow {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/x-ndjson")

        if (body != null) {
            builder.post(body.toRequestBody(formMediaType))
        } else {
            builder.get()
        }

        val call = streamingClient.newCall(builder.build())
        // Cancel the OkHttp call when the collecting coroutine is cancelled so the blocking
        // read below unblocks promptly (via an IOException we treat as end-of-stream).
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw LichessApiException(code, "Stream error: $code")
            }
            response.body?.source()?.use { source ->
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (_: IOException) {
                        // Call cancelled or connection dropped — end the stream.
                        break
                    } ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }.flowOn(Dispatchers.IO)
}

/** Exception thrown when the Lichess API returns a non-2xx response. */
class LichessApiException(val statusCode: Int, message: String) : Exception(message)
