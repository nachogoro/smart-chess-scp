package scp.remote.lichess

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON configuration for parsing Lichess NDJSON lines. */
val lichessJson = Json { ignoreUnknownKeys = true }

/**
 * Parsed result from a game stream line.
 *
 * A game stream (`GET /api/board/game/stream/{gameId}`) emits two types of messages:
 * - [Full]: Sent once as the first message, containing the complete game setup and current state.
 * - [State]: Sent after each move or status change (draw offers, terminal statuses, etc.).
 */
sealed class GameStreamEvent {
    /** Complete game snapshot, received as the first message of the stream. */
    data class Full(val data: LichessGameFull) : GameStreamEvent()

    /** Incremental state update, received after each move or status change. */
    data class State(val data: LichessGameState) : GameStreamEvent()
}

/**
 * Parses a single NDJSON line from a game stream into a [GameStreamEvent].
 *
 * Dispatches on the `type` field: `"gameFull"` → [GameStreamEvent.Full],
 * `"gameState"` → [GameStreamEvent.State]. Returns null for unrecognized types
 * (e.g. `"chatLine"`).
 */
fun parseGameStreamLine(line: String): GameStreamEvent? {
    val obj = lichessJson.decodeFromString<JsonObject>(line)
    val type = obj["type"]?.jsonPrimitive?.content ?: return null

    return when (type) {
        "gameFull" -> GameStreamEvent.Full(lichessJson.decodeFromString<LichessGameFull>(line))
        "gameState" -> GameStreamEvent.State(lichessJson.decodeFromString<LichessGameState>(line))
        else -> null
    }
}

/**
 * Parses a single NDJSON line from the account-wide event stream into a [LichessStreamEvent].
 *
 * Returns null if the line is not valid JSON or cannot be deserialized.
 */
fun parseEventStreamLine(line: String): LichessStreamEvent? {
    return try {
        lichessJson.decodeFromString<LichessStreamEvent>(line)
    } catch (_: Exception) {
        null
    }
}
