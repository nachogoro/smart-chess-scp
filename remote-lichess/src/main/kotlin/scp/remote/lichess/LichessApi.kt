package scp.remote.lichess

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the Lichess Board API HTTP endpoints.
 *
 * Lichess communication revolves around two long-lived NDJSON streams and several one-shot REST calls:
 *
 * - **Event stream** ([streamEvents]): An account-wide stream that notifies the client when games
 *   start or finish. Opened once after authentication and kept alive for the session.
 *
 * - **Game stream** ([streamGame]): A per-game stream opened when a game begins. It first sends a
 *   `gameFull` message with the complete game state, then sends `gameState` messages after each
 *   move or status change (draw offers, resignation, checkmate, etc.).
 *
 * - **Seek / Challenge** ([createSeek], [createChallenge], [challengeAi]): Endpoints that initiate
 *   a game. [createSeek] and [createChallenge] are streaming POST endpoints that stay open until
 *   the seek is matched or the challenge is accepted/declined. [challengeAi] is a one-shot POST
 *   that starts the game immediately. In all cases, the actual game start notification arrives
 *   via the event stream.
 *
 * - **Game actions** ([makeMove], [resign], [offerDraw], [respondDraw], [abort]): One-shot POST
 *   calls that perform an action on an active game.
 *
 * All endpoints require a personal API token with the `board:play` scope. [challengeAi] also
 * requires the `challenge:write` scope. All streaming endpoints return `Flow<String>` where
 * each element is a single NDJSON line.
 *
 * This interface exists to allow test fakes to replace the real HTTP implementation.
 */
interface LichessApi {

    /**
     * Fetches the authenticated user's account information.
     *
     * Calls `GET /api/account`.
     *
     * @throws LichessApiException with status 401 if the token is invalid.
     */
    suspend fun getAccount(token: String): LichessAccount

    /**
     * Opens the account-wide event stream.
     *
     * Calls `GET /api/stream/event`. The returned flow emits one NDJSON line per event.
     * Events include `gameStart` (a game has begun) and `gameFinish` (a game has ended).
     *
     * The flow completes when the server closes the connection.
     */
    fun streamEvents(token: String): Flow<String>

    /**
     * Opens a per-game stream for the given game.
     *
     * Calls `GET /api/board/game/stream/{gameId}`. The first emission is always a `gameFull`
     * message containing the complete game setup and current state. Subsequent emissions are
     * `gameState` messages sent after each move or status change.
     *
     * The flow completes when the game ends or the server closes the connection.
     */
    fun streamGame(token: String, gameId: String): Flow<String>

    /**
     * Creates an open seek to be matched with a random opponent.
     *
     * Calls `POST /api/board/seek`. The returned flow stays open until the seek is matched
     * or cancelled. The actual game start is signalled via the event stream, not this flow.
     *
     * @param time Initial time in minutes.
     * @param increment Increment per move in seconds.
     * @param rated Whether to play a rated game.
     * @param color Preferred color: `"random"`, `"white"`, or `"black"`.
     */
    suspend fun createSeek(
        token: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String>

    /**
     * Sends a challenge to a specific user.
     *
     * Calls `POST /api/challenge/{username}`. The returned flow stays open until the
     * challenge is accepted, declined, or times out. The actual game start is signalled
     * via the event stream.
     *
     * @param username Lichess username of the opponent to challenge.
     * @param time Initial time in minutes.
     * @param increment Increment per move in seconds.
     * @param rated Whether to play a rated game.
     * @param color Preferred color: `"random"`, `"white"`, or `"black"`.
     */
    suspend fun createChallenge(
        token: String,
        username: String,
        time: Int,
        increment: Int,
        rated: Boolean,
        color: String,
    ): Flow<String>

    /**
     * Challenges the Lichess Stockfish AI.
     *
     * Calls `POST /api/challenge/ai`. Unlike [createSeek] and [createChallenge],
     * this is a one-shot call — the game starts immediately, and the game start
     * notification arrives via the event stream.
     *
     * @param level Stockfish level (1–8).
     * @param time Initial time in minutes.
     * @param increment Increment per move in seconds.
     * @param color Preferred color: `"random"`, `"white"`, or `"black"`.
     */
    suspend fun challengeAi(
        token: String,
        level: Int,
        time: Int,
        increment: Int,
        color: String,
    )

    /**
     * Submits a move in the given game.
     *
     * Calls `POST /api/board/game/{gameId}/move/{move}`.
     *
     * @param move UCI move string (e.g. `"e2e4"`, `"e7e8q"`).
     * @throws LichessApiException with status 400 if the move is illegal.
     */
    suspend fun makeMove(token: String, gameId: String, move: String)

    /**
     * Resigns the given game.
     *
     * Calls `POST /api/board/game/{gameId}/resign`.
     */
    suspend fun resign(token: String, gameId: String)

    /**
     * Offers a draw in the given game (or accepts a pending draw offer from the opponent).
     *
     * Calls `POST /api/board/game/{gameId}/draw/yes`.
     */
    suspend fun offerDraw(token: String, gameId: String)

    /**
     * Responds to a draw offer from the opponent.
     *
     * Calls `POST /api/board/game/{gameId}/draw/yes` or `.../draw/no`.
     *
     * @param accept True to accept the draw, false to decline.
     */
    suspend fun respondDraw(token: String, gameId: String, accept: Boolean)

    /**
     * Aborts the given game (only possible within the first few moves).
     *
     * Calls `POST /api/board/game/{gameId}/abort`.
     */
    suspend fun abort(token: String, gameId: String)
}
