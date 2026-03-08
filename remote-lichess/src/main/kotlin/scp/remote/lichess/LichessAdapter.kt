package scp.remote.lichess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import scp.protocol.*

private const val STANDARD_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
private const val MIN_SEEK_TIME_MINUTES = 10

/**
 * [RemoteAdapterModule] implementation for Lichess, using the Lichess Board API.
 *
 * This adapter manages two concurrent NDJSON streams to communicate with Lichess:
 *
 * 1. **Event stream** — Opened once after [configure] and kept alive for the session.
 *    Receives account-wide notifications such as `gameStart` and `gameFinish`.
 *    When a `gameStart` arrives, the adapter automatically opens a game stream.
 *
 * 2. **Game stream** — Opened per game. The first message is a `gameFull` containing the
 *    complete game setup (players, clocks, initial position). Subsequent `gameState` messages
 *    carry the full move list and clock times after each move, as well as draw offers and
 *    terminal statuses. The adapter diffs incoming move lists against [lastKnownMoveCount]
 *    to detect new opponent moves.
 *
 * Game actions (moves, resignation, draw offers) are one-shot POST calls that do not return
 * game state — the response comes asynchronously via the game stream.
 *
 * Only Rapid and Classical time controls are supported (minimum 10 minutes initial time),
 * as required by the Lichess Board API.
 *
 * @param scope Coroutine scope for launching stream collection coroutines.
 * @param api Lichess API implementation (injectable for testing).
 */
class LichessAdapter(
    private val scope: CoroutineScope,
    private val api: LichessApi = LichessHttpApi(),
) : RemoteAdapterModule {

    private var token: String? = null
    private var user: RemoteUser? = null
    private var currentGameId: String? = null
    private var myColor: Color? = null

    /** Number of moves the adapter knows about, used to detect new opponent moves via diffing. */
    private var lastKnownMoveCount: Int = 0

    private var eventStreamJob: Job? = null
    private var seekJob: Job? = null
    private var gameStreamJob: Job? = null

    private val _gameStarted = MutableSharedFlow<GameStartedEvent>(replay = 1)
    override val gameStarted: SharedFlow<GameStartedEvent> = _gameStarted

    private val _opponentMove = MutableSharedFlow<OpponentMoveEvent>(replay = 1)
    override val opponentMove: SharedFlow<OpponentMoveEvent> = _opponentMove

    private val _gameEnded = MutableSharedFlow<RemoteGameEndedEvent>(replay = 1)
    override val gameEnded: SharedFlow<RemoteGameEndedEvent> = _gameEnded

    private val _drawOffered = MutableSharedFlow<Unit>(replay = 1)
    override val drawOffered: SharedFlow<Unit> = _drawOffered

    private val _errors = MutableSharedFlow<RemoteErrorEvent>(replay = 1)
    override val errors: SharedFlow<RemoteErrorEvent> = _errors

    /**
     * Returns the adapter's capabilities, including connection parameters
     * (`api_token`) and seek/challenge parameters (`opponent`, `time`, `increment`,
     * `rated`, `color`).
     */
    override suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities> {
        return Result.Success(
            RemoteCapabilities(
                platform = "Lichess",
                features = listOf("rated", "casual"),
                parameters = listOf(
                    ParameterDescriptor(
                        name = "api_token",
                        type = ParameterType.STRING,
                        description = "Lichess personal API token with board:play scope",
                        required = true,
                        sensitive = true,
                    ),
                ),
                seekParameters = listOf(
                    ParameterDescriptor(
                        name = "opponent",
                        type = ParameterType.STRING,
                        description = "Lichess username to challenge. If omitted, creates an open seek.",
                        required = false,
                    ),
                    ParameterDescriptor(
                        name = "time",
                        type = ParameterType.SPIN,
                        description = "Initial time in minutes (Rapid and Classical only, min 10)",
                        default = 15,
                        min = MIN_SEEK_TIME_MINUTES,
                        max = 180,
                    ),
                    ParameterDescriptor(
                        name = "increment",
                        type = ParameterType.SPIN,
                        description = "Increment per move in seconds",
                        default = 10,
                        min = 0,
                        max = 180,
                    ),
                    ParameterDescriptor(
                        name = "rated",
                        type = ParameterType.CHECK,
                        description = "Play a rated game",
                        default = false,
                    ),
                    ParameterDescriptor(
                        name = "color",
                        type = ParameterType.COMBO,
                        description = "Preferred color",
                        default = "random",
                        options = listOf("random", "white", "black"),
                    ),
                ),
            )
        )
    }

    /**
     * Authenticates with Lichess using the provided API token and starts the event stream.
     *
     * Validates the token by calling `GET /api/account`. On success, opens the account-wide
     * event stream to listen for game starts.
     *
     * @param settings Must contain `"api_token"` (a Lichess personal token with `board:play` scope).
     */
    override suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult> {
        val apiToken = settings["api_token"] as? String
            ?: return Result.Failure(RemoteError.InvalidParams("api_token is required"))

        val account = try {
            api.getAccount(apiToken)
        } catch (e: LichessApiException) {
            return if (e.statusCode == 401) {
                Result.Failure(RemoteError.AuthFailed("Invalid API token"))
            } else {
                Result.Failure(RemoteError.PlatformError("Lichess error: ${e.message}"))
            }
        } catch (e: Exception) {
            return Result.Failure(RemoteError.PlatformError("Connection error: ${e.message}"))
        }

        token = apiToken
        val rating = account.perfs.values.maxByOrNull { it.games }?.rating
        user = RemoteUser(username = account.username, rating = rating)

        startEventStream()

        return Result.Success(ConfigureResult(user))
    }

    /**
     * Starts looking for a game, either by creating an open seek or challenging a specific user.
     *
     * If `"opponent"` is provided in [settings], sends a challenge to that Lichess user via
     * `POST /api/challenge/{username}`. Otherwise, creates an open seek via `POST /api/board/seek`.
     *
     * Both operations open a streaming connection that stays alive until matched/accepted.
     * The actual game start notification arrives via the event stream, not the seek/challenge stream.
     *
     * @param settings Seek parameters: `time`, `increment`, `rated`, `color`, and optionally `opponent`.
     */
    override suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())

        val time = (settings["time"] as? Number)?.toInt() ?: 15
        val increment = (settings["increment"] as? Number)?.toInt() ?: 10
        val rated = settings["rated"] as? Boolean ?: false
        val color = settings["color"] as? String ?: "random"
        val opponent = settings["opponent"] as? String

        if (time < MIN_SEEK_TIME_MINUTES) {
            return Result.Failure(
                RemoteError.InvalidParams(
                    "Minimum time is $MIN_SEEK_TIME_MINUTES minutes (Lichess Board API only supports Rapid and Classical)"
                )
            )
        }

        seekJob?.cancel()
        seekJob = scope.launch {
            try {
                val stream = when(opponent) {
                    null -> api.createSeek(t, time, increment, rated, color)
                    else -> api.createChallenge(t, opponent, time, increment, rated, color)
                }

                // Collecting keeps the HTTP connection alive. Lichess cancels the
                // seek/challenge if the connection drops. The body is empty because
                // game start notifications arrive via the event stream, not this stream.
                stream.collect {}
            } catch (e: Exception) {
                val action = if (opponent != null) "Challenge" else "Seek"
                _errors.emit(RemoteErrorEvent("PLATFORM_ERROR", "$action failed: ${e.message}"))
            }
        }

        return Result.Success(Unit)
    }

    /** Cancels any active seek or pending challenge. */
    override suspend fun cancelSeek(): Result<RemoteError, Unit> {
        seekJob?.cancel()
        seekJob = null
        return Result.Success(Unit)
    }

    /**
     * Submits a move in the current game.
     *
     * Increments [lastKnownMoveCount] on success so that the echo of our own move
     * from the game stream is not mistakenly emitted as an opponent move.
     *
     * @param move UCI move string (e.g. `"e2e4"`, `"e7e8q"`).
     */
    override suspend fun sendMove(move: String): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.PlatformError("No active game"))

        return try {
            api.makeMove(t, gameId, move)
            lastKnownMoveCount++
            Result.Success(Unit)
        } catch (e: LichessApiException) {
            if (e.statusCode == 400) {
                Result.Failure(RemoteError.PlatformError("Move rejected: ${e.message}"))
            } else {
                Result.Failure(RemoteError.PlatformError("Error sending move: ${e.message}"))
            }
        } catch (e: Exception) {
            Result.Failure(RemoteError.PlatformError("Error sending move: ${e.message}"))
        }
    }

    /** Resigns the current game. */
    override suspend fun resign(): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.PlatformError("No active game"))

        return try {
            api.resign(t, gameId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(RemoteError.PlatformError("Error resigning: ${e.message}"))
        }
    }

    /** Offers a draw to the opponent in the current game. */
    override suspend fun offerDraw(): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.PlatformError("No active game"))

        return try {
            api.offerDraw(t, gameId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(RemoteError.PlatformError("Error offering draw: ${e.message}"))
        }
    }

    /**
     * Responds to a draw offer from the opponent.
     *
     * @param accept True to accept the draw, false to decline.
     */
    override suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.PlatformError("No active game"))

        return try {
            api.respondDraw(t, gameId, accept)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(RemoteError.PlatformError("Error responding to draw: ${e.message}"))
        }
    }

    /** Disconnects from Lichess, cancelling all streams and clearing session state. */
    override suspend fun disconnect(): Result<RemoteError, Unit> {
        seekJob?.cancel()
        gameStreamJob?.cancel()
        eventStreamJob?.cancel()
        seekJob = null
        gameStreamJob = null
        eventStreamJob = null
        currentGameId = null
        myColor = null
        token = null
        user = null
        return Result.Success(Unit)
    }

    // -- Event stream handling --

    /** Launches a coroutine that collects the account-wide event stream. */
    private fun startEventStream() {
        val t = token ?: return
        eventStreamJob?.cancel()
        eventStreamJob = scope.launch {
            try {
                api.streamEvents(t).collect { line ->
                    handleEventStreamLine(line)
                }
            } catch (e: Exception) {
                _errors.emit(RemoteErrorEvent("CONNECTION_LOST", "Event stream lost: ${e.message}"))
            }
        }
    }

    /**
     * Processes a single line from the event stream.
     *
     * On `gameStart`, records the game ID and opens a per-game stream.
     * `gameFinish` is ignored here — game termination is detected from the game stream's
     * terminal status instead, which provides richer information (winner, reason).
     */
    private fun handleEventStreamLine(line: String) {
        val event = parseEventStreamLine(line) ?: return

        when (event.type) {
            "gameStart" -> {
                val game = event.game ?: return
                currentGameId = game.gameId
                startGameStream(game.gameId)
            }
            "gameFinish" -> {
                // Game finish is handled by the game stream's terminal status
            }
        }
    }

    // -- Game stream handling --

    /** Launches a coroutine that collects the game stream for the given game. */
    private fun startGameStream(gameId: String) {
        val t = token ?: return
        gameStreamJob?.cancel()
        lastKnownMoveCount = 0

        gameStreamJob = scope.launch {
            try {
                api.streamGame(t, gameId).collect { line ->
                    handleGameStreamLine(line)
                }
            } catch (e: Exception) {
                _errors.emit(RemoteErrorEvent("CONNECTION_LOST", "Game stream lost: ${e.message}"))
            }
        }
    }

    /** Dispatches a game stream line to the appropriate handler. */
    private suspend fun handleGameStreamLine(line: String) {
        val event = parseGameStreamLine(line) ?: return

        when (event) {
            is GameStreamEvent.Full -> handleGameFull(event.data)
            is GameStreamEvent.State -> handleGameState(event.data)
        }
    }

    /**
     * Handles the initial `gameFull` message from the game stream.
     *
     * Determines which color we are playing by matching the authenticated username against
     * the player names, then emits a [GameStartedEvent]. Also processes the embedded
     * [LichessGameFull.state] in case the game already has moves (e.g. on reconnection).
     */
    private suspend fun handleGameFull(game: LichessGameFull) {
        val username = user?.username ?: return

        myColor = when {
            game.white.name.equals(username, ignoreCase = true) ||
                game.white.id.equals(username, ignoreCase = true) -> Color.WHITE
            else -> Color.BLACK
        }

        val opponent = when (myColor) {
            Color.WHITE -> game.black.name
            Color.BLACK -> game.white.name
            else -> "Anonymous"
        }

        val fen = if (game.initialFen == "startpos") STANDARD_FEN else game.initialFen

        val timeControl = game.clock?.let {
            TimeControl(
                type = TimeControlType.FISCHER,
                initialMs = it.initial,
                incrementMs = it.increment,
            )
        }

        _gameStarted.emit(
            GameStartedEvent(
                gameId = game.id,
                color = myColor!!,
                opponent = opponent,
                fen = fen,
                timeControl = timeControl,
            )
        )

        // Process initial state (game may already have moves if reconnecting)
        handleGameState(game.state)
    }

    /**
     * Handles an incremental `gameState` message from the game stream.
     *
     * Checks for three things in order:
     * 1. **Terminal status** — If the game has ended (checkmate, resignation, draw, timeout, etc.),
     *    emits a [RemoteGameEndedEvent] and cleans up.
     * 2. **Draw offers** — If the opponent has a pending draw offer (`bdraw`/`wdraw`), emits
     *    on [drawOffered].
     * 3. **New moves** — Compares the incoming move count against [lastKnownMoveCount]. If there
     *    are new moves and the latest one was made by the opponent, emits an [OpponentMoveEvent].
     *    Our own moves are filtered out because [sendMove] pre-increments [lastKnownMoveCount].
     */
    private suspend fun handleGameState(state: LichessGameState) {
        val color = myColor ?: return

        // Check for terminal status
        if (isTerminalStatus(state.status)) {
            val result = mapLichessResult(state.status, state.winner, color)
            val reason = mapLichessReason(state.status)
            currentGameId = null
            gameStreamJob?.cancel()
            _gameEnded.emit(RemoteGameEndedEvent(result = result, reason = reason))
            return
        }

        // Check for draw offers from opponent
        val opponentDrawOffer = when (color) {
            Color.WHITE -> state.bdraw
            Color.BLACK -> state.wdraw
        }
        if (opponentDrawOffer) {
            _drawOffered.emit(Unit)
        }

        // Detect new opponent moves
        val moves = if (state.moves.isBlank()) emptyList() else state.moves.split(" ")
        val newMoveCount = moves.size

        if (newMoveCount > lastKnownMoveCount) {
            val latestMove = moves.last()
            // Check if this is an opponent move
            val movingColor = if (newMoveCount % 2 == 1) Color.WHITE else Color.BLACK
            if (movingColor != color) {
                _opponentMove.emit(
                    OpponentMoveEvent(
                        move = latestMove,
                        clocks = Clocks(whiteMs = state.wtime, blackMs = state.btime),
                    )
                )
            }
            lastKnownMoveCount = newMoveCount
        }
    }

    // -- Lichess status mapping --

    /** Returns true if the given Lichess status string represents a finished game. */
    private fun isTerminalStatus(status: String): Boolean = status in setOf(
        "mate", "resign", "stalemate", "timeout", "draw", "outoftime", "aborted",
        "noStart", "cheat", "variantEnd",
    )

    /**
     * Maps a Lichess status and winner to a PGN-style result string.
     *
     * - `"1-0"` — white wins
     * - `"0-1"` — black wins
     * - `"1/2-1/2"` — draw (including `outoftime` with no winner, i.e. insufficient material)
     * - `"*"` — game aborted or indeterminate
     */
    private fun mapLichessResult(status: String, winner: String?, myColor: Color): String {
        if (status == "draw" || status == "stalemate") return "1/2-1/2"
        if (status == "aborted" || status == "noStart") return "*"

        return when (winner) {
            "white" -> "1-0"
            "black" -> "0-1"
            null -> {
                // outoftime with no winner = draw (insufficient material)
                if (status == "outoftime") "1/2-1/2" else "*"
            }
            else -> "*"
        }
    }

    /** Maps a Lichess status string to a human-readable termination reason. */
    private fun mapLichessReason(status: String): String = when (status) {
        "mate" -> "checkmate"
        "resign" -> "resignation"
        "stalemate" -> "stalemate"
        "timeout", "outoftime" -> "timeout"
        "draw" -> "draw"
        "aborted", "noStart" -> "aborted"
        "cheat" -> "fair play violation"
        "variantEnd" -> "variant end"
        else -> status
    }
}
