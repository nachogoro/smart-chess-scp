package scp.remote.lichess

import kotlinx.serialization.Serializable

/**
 * Lichess user account information, as returned by `GET /api/account`.
 *
 * @property id Lichess user ID (lowercase).
 * @property username Display username.
 */
@Serializable
data class LichessAccount(
    val id: String,
    val username: String,
)

// -- Event stream models --
// These represent messages received from the account-wide event stream (`GET /api/stream/event`).
// The event stream notifies the client of high-level account events such as game starts and finishes.

/**
 * A single event from the account-wide event stream.
 *
 * The event stream is a long-lived NDJSON connection that Lichess keeps open indefinitely.
 * It emits events like `gameStart` and `gameFinish` whenever the account's game state changes.
 *
 * @property type Event type identifier (e.g. `"gameStart"`, `"gameFinish"`, `"challenge"`).
 * @property game Game information, present for `gameStart` and `gameFinish` events.
 */
@Serializable
data class LichessStreamEvent(
    val type: String,
    val game: LichessGameEventInfo? = null,
)

/**
 * Summary information about a game, as provided by the event stream.
 *
 * This is a lightweight snapshot; full game details are obtained by opening
 * a dedicated game stream via `GET /api/board/game/stream/{gameId}`.
 *
 * @property fullId Full game ID including player suffix (used internally by Lichess).
 * @property gameId Short game ID, used in all Board API calls.
 */
@Serializable
data class LichessGameEventInfo(
    val fullId: String,
    val gameId: String,
    val fen: String = "startpos",
    val color: String = "white",
    val speed: String = "",
    val perf: String = "",
    val rated: Boolean = false,
    val opponent: LichessOpponent = LichessOpponent(),
    val secondsLeft: Int? = null,
    val isMyTurn: Boolean = false,
)

/**
 * Opponent information as provided in event stream game summaries.
 */
@Serializable
data class LichessOpponent(
    val id: String? = null,
    val username: String = "Anonymous",
)

// -- Game stream models --
// These represent messages received from a per-game stream (`GET /api/board/game/stream/{gameId}`).
// The game stream provides the full game state and real-time move/status updates for a single game.

/**
 * Full game snapshot, sent as the first message of a game stream (type `"gameFull"`).
 *
 * Contains the complete game setup (players, variant, clock configuration, initial position)
 * and the current [state]. On reconnection, [state] may already contain moves played
 * while disconnected.
 *
 * @property id Short game ID.
 * @property white White player info.
 * @property black Black player info.
 * @property initialFen Starting FEN, or `"startpos"` for the standard starting position.
 * @property state Current game state (moves, clocks, status).
 * @property clock Clock configuration (initial time and increment), null for untimed games.
 */
@Serializable
data class LichessGameFull(
    val type: String = "gameFull",
    val id: String,
    val rated: Boolean = false,
    val variant: LichessVariant = LichessVariant(),
    val speed: String = "",
    val white: LichessPlayer = LichessPlayer(),
    val black: LichessPlayer = LichessPlayer(),
    val initialFen: String = "startpos",
    val state: LichessGameState,
    val clock: LichessClock? = null,
)

/** Chess variant information. */
@Serializable
data class LichessVariant(
    val key: String = "standard",
    val name: String = "Standard",
)

/**
 * Player information within a game stream.
 *
 * @property aiLevel Set only for Lichess's own engine, which has a strength instead of an account
 *   and so arrives with no [id] or [name].
 */
@Serializable
data class LichessPlayer(
    val id: String? = null,
    val name: String = "Anonymous",
    val aiLevel: Int? = null,
) {
    /**
     * What to call this player. The engine goes by the one Lichess runs and the level it plays at,
     * with no words around them: this is a name, and a name is shown as it stands (PROTOCOL §2.6).
     */
    val displayName: String get() = aiLevel?.let { "Stockfish: $it" } ?: name
}

/**
 * Incremental game state update, sent after each move or status change (type `"gameState"`).
 *
 * Also embedded inside [LichessGameFull.state] as the initial snapshot.
 *
 * @property moves Space-separated UCI move list representing the full move history (e.g. `"e2e4 e7e5 d2d4"`).
 * @property wtime White's remaining time in milliseconds.
 * @property btime Black's remaining time in milliseconds.
 * @property status Game status string (e.g. `"started"`, `"mate"`, `"resign"`, `"draw"`, `"outoftime"`).
 * @property winner `"white"` or `"black"` if the game has a winner, null otherwise.
 * @property wdraw True if white is offering a draw.
 * @property bdraw True if black is offering a draw.
 */
@Serializable
data class LichessGameState(
    val type: String = "gameState",
    val moves: String = "",
    val wtime: Long = 0,
    val btime: Long = 0,
    val winc: Long = 0,
    val binc: Long = 0,
    val status: String = "started",
    val winner: String? = null,
    val wdraw: Boolean = false,
    val bdraw: Boolean = false,
)

/**
 * Clock configuration for a game.
 *
 * @property initial Initial time per player in milliseconds.
 * @property increment Increment per move in milliseconds.
 */
@Serializable
data class LichessClock(
    val initial: Long,
    val increment: Long,
)
