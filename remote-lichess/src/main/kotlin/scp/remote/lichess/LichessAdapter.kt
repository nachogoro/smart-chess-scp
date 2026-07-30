package scp.remote.lichess

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import scp.protocol.*

private const val STANDARD_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
private const val MIN_SEEK_TIME_MINUTES = 10


// The names the seek parameters are advertised under. Referenced from the descriptors, from the
// settings map they come back in, and from the error naming one of them, so they are declared once.
private const val PARAM_API_TOKEN = "api_token"
private const val PARAM_OPPONENT_TYPE = "opponent_type"
private const val PARAM_OPPONENT_NAME = "opponent_name"
private const val PARAM_AI_LEVEL = "ai_level"
private const val PARAM_TIME = "time"
private const val PARAM_INCREMENT = "increment"
private const val PARAM_RATED = "rated"
private const val PARAM_COLOR = "color"

/** Room for a burst of stream events so emitting never blocks on a collector. */
private const val EVENT_BUFFER = 16

private const val RETRY_BASE_MS = 1_000L
private const val RETRY_MAX_MS = 30_000L

/**
 * How long to wait before reopening a stream that has dropped [failures] times running.
 *
 * Backs off so that a platform that is down is not hammered, and caps so that a connection that
 * comes back is picked up within half a minute rather than eventually.
 */
internal fun retryDelayMs(failures: Int): Long =
    (RETRY_BASE_MS shl (failures - 1).coerceIn(0, 16)).coerceAtMost(RETRY_MAX_MS)

private fun debugLog(msg: String) = scp.protocol.debugLog("[lichess] $msg")

/**
 * [RemoteAdapterModule] implementation for Lichess, using the Lichess Board API.
 *
 * This adapter manages two concurrent NDJSON streams to communicate with Lichess:
 *
 * 1. **Event stream** — Opened once after [configure] and kept alive for the session.
 *    Receives account-wide notifications such as `gameStart` and `gameFinish`.
 *    When a `gameStart` arrives for a game the adapter is actively seeking (see [seekGame]),
 *    it opens a game stream. Stale `gameStart` events from pre-existing games are ignored.
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

    /** True after [seekGame] is called, cleared when a game starts. Prevents processing stale games. */
    private var awaitingGame: Boolean = false

    /** Game IDs seen on the event stream before [seekGame] was called. Used to ignore stale replays. */
    private val staleGameIds = mutableSetOf<String>()

    /** Number of moves the adapter knows about, used to detect new opponent moves via diffing. */
    private var lastKnownMoveCount: Int = 0

    /**
     * Which colour played the game's first move, read from the starting position.
     *
     * Move `n` in the list belongs to this colour when `n` is even. Assuming white here instead
     * would invert every attribution in a game that did not start from the standard position,
     * which [handleGameFull] otherwise takes care to support.
     */
    private var firstMover: Color = Color.WHITE

    /** The game whose start has already been announced, so a reconnect is not mistaken for one. */
    private var announcedGameId: String? = null

    private var eventStreamJob: Job? = null
    private var seekJob: Job? = null
    private var gameStreamJob: Job? = null

    // These are notifications, not state, so none of them replays. The adapter outlives a single
    // game while the orchestrator subscribes per game, and a replay buffer handed each new set of
    // collectors the *previous* game's last event the moment they subscribed: a replayed
    // gameStarted built the next game from the old game's colour and position, and a replayed
    // gameEnded finished it before it began. Buffering without replay keeps the stream coroutines
    // from stalling on a slow collector while guaranteeing a subscriber only ever hears what
    // happens after it subscribes.
    private val _gameStarted = MutableSharedFlow<GameStartedEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val gameStarted: SharedFlow<GameStartedEvent> = _gameStarted

    private val _opponentMove = MutableSharedFlow<OpponentMoveEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val opponentMove: SharedFlow<OpponentMoveEvent> = _opponentMove

    private val _gameEnded = MutableSharedFlow<RemoteGameEndedEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val gameEnded: SharedFlow<RemoteGameEndedEvent> = _gameEnded

    private val _drawOffered = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val drawOffered: SharedFlow<Unit> = _drawOffered

    private val _errors = MutableSharedFlow<RemoteError>(extraBufferCapacity = EVENT_BUFFER)
    override val errors: SharedFlow<RemoteError> = _errors

    /**
     * Returns the adapter's capabilities, including connection parameters
     * (`api_token`) and seek parameters (`opponent_type`, `opponent_name`,
     * `ai_level`, `time`, `increment`, `rated`, `color`), with conditional
     * visibility based on `opponent_type`.
     */
    override suspend fun getCapabilities(): Result<RemoteError, RemoteCapabilities> {
        return Result.Success(
            RemoteCapabilities(
                platform = "Lichess",
                features = listOf("rated", "casual"),
                parameters = listOf(
                    ParameterDescriptor(
                        name = PARAM_API_TOKEN,
                        type = ParameterType.STRING,
                        description = "Lichess API token (scopes: board:play, challenge:write for AI)",
                        required = true,
                        sensitive = true,
                    ),
                ),
                seekParameters = listOf(
                    ParameterDescriptor(
                        name = PARAM_OPPONENT_TYPE,
                        type = ParameterType.COMBO,
                        description = "Type of opponent",
                        default = "Random opponent",
                        options = listOf("Random opponent", "Friend", "Engine"),
                    ),
                    ParameterDescriptor(
                        name = PARAM_OPPONENT_NAME,
                        type = ParameterType.STRING,
                        description = "Lichess username to challenge",
                        required = true,
                        visibleWhen = VisibleWhen(field = PARAM_OPPONENT_TYPE, equals = "Friend"),
                    ),
                    ParameterDescriptor(
                        name = PARAM_AI_LEVEL,
                        type = ParameterType.SPIN,
                        description = "Stockfish level (1-8)",
                        min = 1,
                        max = 8,
                        default = 4,
                        visibleWhen = VisibleWhen(field = PARAM_OPPONENT_TYPE, equals = "Engine"),
                    ),
                    ParameterDescriptor(
                        name = PARAM_TIME,
                        type = ParameterType.SPIN,
                        description = "Initial time in minutes (min 10)",
                        default = 15,
                        min = MIN_SEEK_TIME_MINUTES,
                        max = 180,
                    ),
                    ParameterDescriptor(
                        name = PARAM_INCREMENT,
                        type = ParameterType.SPIN,
                        description = "Increment per move in seconds",
                        default = 10,
                        min = 0,
                        max = 180,
                    ),
                    ParameterDescriptor(
                        name = PARAM_RATED,
                        type = ParameterType.CHECK,
                        description = "Play a rated game",
                        default = false,
                        visibleWhen = VisibleWhen(field = PARAM_OPPONENT_TYPE, oneOf = listOf("Random opponent", "Friend")),
                    ),
                    ParameterDescriptor(
                        name = PARAM_COLOR,
                        type = ParameterType.COMBO,
                        description = "Your color",
                        default = "Random",
                        options = listOf("Random", "White", "Black"),
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
     * @param settings Must contain `"api_token"` (a Lichess personal token with `board:play` scope;
     *   `challenge:write` is also needed for AI challenges).
     */
    override suspend fun configure(settings: Map<String, Any>): Result<RemoteError, ConfigureResult> {
        val apiToken = settings[PARAM_API_TOKEN] as? String
            ?: return Result.Failure(RemoteError.InvalidParams("api_token"))

        val account = try {
            api.getAccount(apiToken)
        } catch (e: Exception) {
            return Result.Failure(e.toRemoteError())
        }

        token = apiToken
        val rating = account.perfs.values.maxByOrNull { it.games }?.rating
        user = RemoteUser(username = account.username, rating = rating)

        startEventStream()

        return Result.Success(ConfigureResult(user))
    }

    /**
     * Starts looking for a game based on the selected opponent type.
     *
     * - `"Random opponent"` (default): Creates an open seek via `POST /api/board/seek`.
     * - `"Friend"`: Challenges a specific user via `POST /api/challenge/{username}`.
     * - `"Engine"`: Challenges the Lichess Stockfish AI via `POST /api/challenge/ai`.
     *
     * For `"Random opponent"` and `"Friend"`, a streaming connection stays alive until
     * matched/accepted. For `"Engine"`, the game starts immediately. In all cases, the actual
     * game start notification arrives via the event stream.
     *
     * Color values are lowercased before being sent to the Lichess API (e.g. `"White"` → `"white"`).
     *
     * @param settings Seek parameters: `opponent_type`, `time`, `increment`, `color`,
     *   and conditionally `opponent_name`, `ai_level`, `rated`.
     */
    override suspend fun seekGame(settings: Map<String, Any>): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())

        val opponentType = settings[PARAM_OPPONENT_TYPE] as? String ?: "random"
        val time = (settings[PARAM_TIME] as? Number)?.toInt() ?: 15
        val increment = (settings[PARAM_INCREMENT] as? Number)?.toInt() ?: 10
        val rated = settings[PARAM_RATED] as? Boolean ?: false
        val color = (settings[PARAM_COLOR] as? String)?.lowercase() ?: "random"

        // Names the field, and only the field. What is wrong with the value is not carried: the
        // UI rendered it from a descriptor that already declares the minimum, and a sentence
        // written here would be one this module has no business choosing a language for (§2.6).
        if (time < MIN_SEEK_TIME_MINUTES) {
            return Result.Failure(RemoteError.InvalidParams(PARAM_TIME))
        }

        awaitingGame = true

        when (opponentType) {
            "Engine" -> {
                val level = (settings[PARAM_AI_LEVEL] as? Number)?.toInt() ?: 4
                try {
                    api.challengeAi(t, level, time, increment, color)
                } catch (e: Exception) {
                    return Result.Failure(e.toRemoteError())
                }
            }
            else -> {
                seekJob?.cancel()
                seekJob = scope.launch {
                    try {
                        val stream = when (opponentType) {
                            "Friend" -> {
                                val opponent = settings[PARAM_OPPONENT_NAME] as? String
                                    ?: return@launch _errors.emit(
                                        RemoteError.InvalidParams(PARAM_OPPONENT_NAME)
                                    )
                                api.createChallenge(t, opponent, time, increment, rated, color)
                            }
                            else -> api.createSeek(t, time, increment, rated, color)
                        }

                        // Collecting keeps the HTTP connection alive. Lichess cancels the
                        // seek/challenge if the connection drops. The body is empty because
                        // game start notifications arrive via the event stream, not this stream.
                        stream.collect {}
                    } catch (e: Exception) {
                        _errors.emit(e.toRemoteError())
                    }
                }
            }
        }

        return Result.Success(Unit)
    }

    /** Cancels any active seek or pending challenge. */
    override suspend fun cancelSeek(): Result<RemoteError, Unit> {
        awaitingGame = false
        staleGameIds.clear()
        seekJob?.cancel()
        seekJob = null
        return Result.Success(Unit)
    }

    /**
     * Submits a move in the current game.
     *
     * @param move UCI move string (e.g. `"e2e4"`, `"e7e8q"`).
     */
    override suspend fun sendMove(move: String): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.NoActiveGame())

        return try {
            debugLog("sendMove: $move (gameId=$gameId, lastKnownMoveCount=$lastKnownMoveCount)")
            api.makeMove(t, gameId, move)
            debugLog("sendMove OK, lastKnownMoveCount=$lastKnownMoveCount")
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e.toRemoteError())
        }
    }

    /** Resigns the current game. */
    override suspend fun resign(): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.NoActiveGame())

        return try {
            api.resign(t, gameId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e.toRemoteError())
        }
    }

    /** Aborts the current game, which Lichess allows only within its first moves. */
    override suspend fun abort(): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.NoActiveGame())

        return try {
            api.abort(t, gameId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e.toRemoteError())
        }
    }

    /** Offers a draw to the opponent in the current game. */
    override suspend fun offerDraw(): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.NoActiveGame())

        return try {
            api.offerDraw(t, gameId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e.toRemoteError())
        }
    }

    /**
     * Responds to a draw offer from the opponent.
     *
     * @param accept True to accept the draw, false to decline.
     */
    override suspend fun respondDraw(accept: Boolean): Result<RemoteError, Unit> {
        val t = token ?: return Result.Failure(RemoteError.NotConnected())
        val gameId = currentGameId ?: return Result.Failure(RemoteError.NoActiveGame())

        return try {
            api.respondDraw(t, gameId, accept)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(e.toRemoteError())
        }
    }

    /** Disconnects from Lichess, cancelling all streams and clearing session state. */
    override suspend fun disconnect(): Result<RemoteError, Unit> {
        awaitingGame = false
        staleGameIds.clear()
        seekJob?.cancel()
        gameStreamJob?.cancel()
        eventStreamJob?.cancel()
        seekJob = null
        gameStreamJob = null
        eventStreamJob = null
        currentGameId = null
        announcedGameId = null
        myColor = null
        token = null
        user = null
        return Result.Success(Unit)
    }

    // -- Event stream handling --

    /**
     * Launches a coroutine that collects the account-wide event stream, reopening it when it drops.
     *
     * Lichess holds this connection open for the session, so losing it means losing every game
     * start until something opens it again - and nothing did. It is retried until [disconnect]
     * cancels the job.
     */
    private fun startEventStream() {
        val t = token ?: return
        eventStreamJob?.cancel()
        debugLog("Starting event stream")
        eventStreamJob = scope.launch {
            var failures = 0
            while (isActive && token != null) {
                try {
                    api.streamEvents(t).collect { line ->
                        debugLog("Event stream line: $line")
                        failures = 0
                        handleEventStreamLine(line)
                    }
                    debugLog("Event stream ended normally")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugLog("Event stream error: ${e::class.simpleName}: ${e.message}")
                    _errors.emit(RemoteError.ConnectionLost("event stream lost: ${e.message}"))
                }
                if (!isActive || token == null) return@launch
                delay(retryDelayMs(++failures))
                debugLog("Reopening event stream (attempt $failures)")
            }
        }
    }

    /**
     * Processes a single line from the event stream.
     *
     * On `gameStart`, records the game ID and opens a per-game stream — but only
     * if the adapter is actively awaiting a game (i.e. [seekGame] was called).
     * This prevents stale `gameStart` events from previous sessions from being
     * processed.
     *
     * `gameFinish` is ignored here — game termination is detected from the game stream's
     * terminal status instead, which provides richer information (winner, reason).
     */
    private fun handleEventStreamLine(line: String) {
        val event = parseEventStreamLine(line)
        if (event == null) {
            debugLog("Event stream: failed to parse line")
            return
        }

        when (event.type) {
            "gameStart" -> {
                val game = event.game
                debugLog("Event: gameStart gameId=${game?.gameId} awaitingGame=$awaitingGame staleIds=$staleGameIds")
                if (game == null) return
                if (!awaitingGame) {
                    // Not seeking — remember this ID so we skip it later
                    staleGameIds.add(game.gameId)
                    return
                }
                if (game.gameId in staleGameIds) {
                    // This is a stale game replayed after seekGame was called — skip it
                    debugLog("Skipping stale game ${game.gameId}")
                    return
                }
                awaitingGame = false
                staleGameIds.clear()
                currentGameId = game.gameId
                startGameStream(game.gameId)
            }
            "gameFinish" -> {
                debugLog("Event: gameFinish")
            }
            else -> {
                debugLog("Event: type=${event.type} (ignored)")
            }
        }
    }

    // -- Game stream handling --

    /**
     * Launches a coroutine that collects the game stream, reopening it until the game is over.
     *
     * Reconnecting is also how the game is reconciled. Lichess replays the whole move list in the
     * `gameFull` that opens every stream, and [lastKnownMoveCount] deliberately survives the drop,
     * so the diff in [handleGameState] emits exactly the moves played while the connection was
     * down - which is what §7.5 asks for, and what a UI needs to catch the board up.
     */
    private fun startGameStream(gameId: String) {
        val t = token ?: return
        gameStreamJob?.cancel()
        lastKnownMoveCount = 0
        announcedGameId = null
        debugLog("Starting game stream for $gameId")

        gameStreamJob = scope.launch {
            var failures = 0
            while (isActive && currentGameId == gameId) {
                try {
                    api.streamGame(t, gameId).collect { line ->
                        debugLog("Game stream line: $line")
                        failures = 0
                        handleGameStreamLine(line)
                    }
                    debugLog("Game stream ended normally for $gameId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugLog("Game stream error for $gameId: ${e::class.simpleName}: ${e.message}")
                    if (currentGameId == gameId) {
                        _errors.emit(RemoteError.ConnectionLost("game stream lost: ${e.message}"))
                    }
                }
                // The game ending clears currentGameId, which is what ends this loop.
                if (!isActive || currentGameId != gameId) return@launch
                delay(retryDelayMs(++failures))
                debugLog("Reopening game stream for $gameId (attempt $failures)")
            }
        }
    }

    /** Dispatches a game stream line to the appropriate handler. */
    private suspend fun handleGameStreamLine(line: String) {
        val event = parseGameStreamLine(line)
        if (event == null) {
            debugLog("Game stream: failed to parse line")
            return
        }

        when (event) {
            is GameStreamEvent.Full -> {
                debugLog("Game stream: gameFull id=${event.data.id}")
                handleGameFull(event.data)
            }
            is GameStreamEvent.State -> {
                debugLog("Game stream: gameState moves=\"${event.data.moves}\" status=${event.data.status}")
                handleGameState(event.data)
            }
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
        debugLog("handleGameFull: white=${game.white.name}(id=${game.white.id}) black=${game.black.name}(id=${game.black.id}) me=$username")

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
        firstMover = activeColorOf(fen)

        // Every stream opens with a gameFull, including the ones opened to replace a dropped
        // connection. Announcing that again would look like a second game starting, and the
        // orchestrator would set one up over the top of the game still being played.
        if (announcedGameId == game.id) {
            debugLog("handleGameFull: reconnected to ${game.id}, catching up from move $lastKnownMoveCount")
            handleGameState(game.state)
            return
        }
        announcedGameId = game.id

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
     * 1. **New moves** — Compares the incoming move count against [lastKnownMoveCount]. If there
     *    are new moves and the latest one was made by the opponent, emits an [OpponentMoveEvent].
     *    Move detection runs first so that a terminal state (e.g. checkmate) emits the final
     *    opponent move before the game-over notification.
     * 2. **Terminal status** — If the game has ended (checkmate, resignation, draw, timeout, etc.),
     *    emits a [RemoteGameEndedEvent] and cleans up.
     * 3. **Draw offers** — If the opponent has a pending draw offer (`bdraw`/`wdraw`), emits
     *    on [drawOffered].
     */
    private suspend fun handleGameState(state: LichessGameState) {
        val color = myColor
        if (color == null) {
            debugLog("handleGameState: myColor is null, ignoring")
            return
        }

        // Detect new opponent moves FIRST — a terminal gameState (e.g. checkmate)
        // includes the final move, and we must emit it before emitting gameEnded.
        val moves = if (state.moves.isBlank()) emptyList() else state.moves.split(" ")
        val newMoveCount = moves.size

        debugLog("Move detection: newMoveCount=$newMoveCount lastKnownMoveCount=$lastKnownMoveCount myColor=$color")

        if (newMoveCount > lastKnownMoveCount) {
            // Every move the update brought, in order, not just the last of them. One gameState
            // can carry several - that is what a reconnection looks like, and it is the case
            // handleGameFull replays this method for. Emitting only the final move left the game
            // controller a move list with holes in it, which it then refused as illegal.
            for (index in lastKnownMoveCount until newMoveCount) {
                val move = moves[index]
                val movingColor = if (index % 2 == 0) firstMover else firstMover.opposite()
                debugLog("New move: $move by $movingColor (I am $color)")
                if (movingColor == color) {
                    debugLog("Move is mine, not emitting opponentMove")
                    continue
                }
                // Only the last move gets the clocks: wtime/btime describe the state this update
                // arrived in, and attaching them to an earlier move would misreport it.
                val clocks = if (index == newMoveCount - 1) {
                    Clocks(whiteMs = state.wtime, blackMs = state.btime)
                } else {
                    null
                }
                debugLog("Emitting opponentMove: $move")
                _opponentMove.emit(OpponentMoveEvent(move = move, clocks = clocks))
            }
            lastKnownMoveCount = newMoveCount
        } else {
            debugLog("No new moves (count unchanged)")
        }

        // Check for terminal status
        if (isTerminalStatus(state.status)) {
            val result = mapLichessResult(state.status, state.winner, color)
            val termination = mapLichessTermination(state.status)
            debugLog("Game ended: status=${state.status} winner=${state.winner} result=$result termination=${termination.code}")
            // Clearing this is what stops the reconnect loop. Cancelling the job from inside the
            // coroutine running it would have left the emission below in a cancelled coroutine.
            currentGameId = null
            _gameEnded.emit(RemoteGameEndedEvent(result = result, termination = termination))
            return
        }

        // Check for draw offers from opponent
        val opponentDrawOffer = when (color) {
            Color.WHITE -> state.bdraw
            Color.BLACK -> state.wdraw
        }
        if (opponentDrawOffer) {
            debugLog("Opponent draw offer detected")
            _drawOffered.emit(Unit)
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
    private fun mapLichessResult(status: String, winner: String?, myColor: Color): GameResult {
        if (status == "draw" || status == "stalemate") return GameResult.DRAW
        if (status == "aborted" || status == "noStart") return GameResult.IN_PROGRESS

        return when (winner) {
            "white" -> GameResult.WHITE_WINS
            "black" -> GameResult.BLACK_WINS
            null -> {
                // outoftime with no winner = draw (insufficient material)
                if (status == "outoftime") GameResult.DRAW else GameResult.IN_PROGRESS
            }
            else -> GameResult.IN_PROGRESS
        }
    }

    /** Maps a Lichess status string to a human-readable termination reason. */
}

/**
 * Lichess's game status, as one of this protocol's terminations.
 *
 * Statuses with no counterpart here are reported as Other rather than being pushed into the
 * nearest code: a UI can then say honestly that Lichess ended the game for a reason it does
 * not recognise, and can special-case the authority code itself if it wants to.
 *
 * `draw` covers every rule Lichess draws a game under and it does not say which, so the kind
 * is unnamed rather than guessed at.
 */
/**
 * The failure this exception represents, as the protocol names it.
 *
 * The distinction the protocol draws is the player's, not the transport's (PROTOCOL.md 7.2): a
 * platform that answered and refused is worth a different sentence, and a different decision about
 * retrying, from one that could not be reached. Mapping everything but one status onto UNREACHABLE
 * threw that away and told a player whose token had expired that their network was down.
 */
internal fun Exception.toRemoteError(): RemoteError {
    if (this !is LichessApiException) return RemoteError.Unreachable(message)
    return when (statusCode) {
        401, 403 -> RemoteError.AuthFailed()
        404 -> RemoteError.NoActiveGame()
        else -> RemoteError.PlatformError(statusCode, serverText())
    }
}

private fun Color.opposite(): Color = when (this) {
    Color.WHITE -> Color.BLACK
    Color.BLACK -> Color.WHITE
}

/**
 * The side to move in a FEN — its second field.
 *
 * Falls back to white for a FEN that cannot be read, which is what the standard position says and
 * what every game the adapter can seek starts from anyway.
 */
internal fun activeColorOf(fen: String): Color =
    if (fen.split(" ").getOrNull(1)?.lowercase() == "b") Color.BLACK else Color.WHITE

internal fun mapLichessTermination(status: String): Termination = when (status) {
    "mate" -> Termination.Checkmate
    "resign" -> Termination.Resignation
    "stalemate" -> Termination.Stalemate
    "timeout", "outoftime" -> Termination.Timeout
    "aborted", "noStart" -> Termination.Aborted
    "draw" -> Termination.Drawn(DrawKind.Other(authorityCode = status))
    "cheat" -> Termination.Other(authorityCode = status, text = "fair play violation")
    "variantEnd" -> Termination.Other(authorityCode = status, text = "variant end")
    else -> Termination.Other(authorityCode = status)
}
