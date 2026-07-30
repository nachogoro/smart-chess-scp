package scp.protocol

/**
 * Something the user needs to be told, identified rather than worded.
 *
 * An open vocabulary (PROTOCOL.md §2.7). The Orchestrator says which of these happened and hands
 * over the values that go with it; the UI writes the sentence, because it is the only module that
 * can know who is reading (§2.6).
 *
 * Cases carrying a [ModuleError] pass the error itself rather than a rendering of it, so a UI can
 * word the wrapper - "the move was refused" - and decide separately what to do with the module's
 * own code and text.
 */
sealed interface UIMessage {

    val code: String

    // -- The board --

    /**
     * The board could not be read.
     *
     * Carries the board's error, so a UI can be specific where the board was: a sensor failure
     * names the squares it could not trust, and telling the player which ones to re-seat is worth
     * more than telling them the read failed.
     */
    data class BoardReadFailed(val error: ModuleError) : UIMessage {
        override val code = "BOARD_READ_FAILED"
    }

    /**
     * The board still shows the position being played.
     *
     * Not a desync: the board and the game agree, there is simply no move on it yet.
     */
    data object BoardUnchanged : UIMessage {
        override val code = "BOARD_UNCHANGED"
    }

    /** The board is a position, but not one legal move away from the game's. */
    data object NoLegalMoveMatches : UIMessage {
        override val code = "NO_LEGAL_MOVE_MATCHES"
    }

    data class BoardFailed(val error: ModuleError) : UIMessage {
        override val code = "BOARD_FAILED"
    }

    /** The board could not say what it is capable of, so a game cannot be set up on it. */
    data class BoardCapabilitiesUnavailable(val error: ModuleError) : UIMessage {
        override val code = "BOARD_CAPABILITIES_UNAVAILABLE"
    }

    // -- Moves --

    /** The game controller refused the move read off the board. */
    data class MoveRejected(val error: ModuleError) : UIMessage {
        override val code = "MOVE_REJECTED"
    }

    /** The game controller refused a move the opponent sent. */
    data class OpponentMoveRejected(val error: ModuleError) : UIMessage {
        override val code = "OPPONENT_MOVE_REJECTED"
    }

    /** The game controller refused the engine's move. */
    data class EngineMoveRejected(val error: ModuleError) : UIMessage {
        override val code = "ENGINE_MOVE_REJECTED"
    }

    /** The move was accepted locally but could not be sent to the server. */
    data class MoveNotSent(val error: ModuleError) : UIMessage {
        override val code = "MOVE_NOT_SENT"
    }

    // -- Engine and remote --

    data class EngineFailed(val error: ModuleError) : UIMessage {
        override val code = "ENGINE_FAILED"
    }

    data class RemoteFailed(val error: ModuleError) : UIMessage {
        override val code = "REMOTE_FAILED"
    }

    data object EngineCapabilitiesUnavailable : UIMessage {
        override val code = "ENGINE_CAPABILITIES_UNAVAILABLE"
    }

    data object RemoteCapabilitiesUnavailable : UIMessage {
        override val code = "REMOTE_CAPABILITIES_UNAVAILABLE"
    }

    /** The engine would not accept the settings it was given. */
    data class EngineConfigurationFailed(val error: ModuleError) : UIMessage {
        override val code = "ENGINE_CONFIGURATION_FAILED"
    }

    /**
     * The platform would not accept the settings it was given.
     *
     * Carries the error because the difference matters to the player: a rejected token is
     * something they can fix, and a platform that is down is not.
     */
    data class RemoteConfigurationFailed(val error: ModuleError) : UIMessage {
        override val code = "REMOTE_CONFIGURATION_FAILED"
    }

    /** The platform would not start looking for an opponent. */
    data class SeekFailed(val error: ModuleError) : UIMessage {
        override val code = "SEEK_FAILED"
    }

    data object DrawNotSupportedByEngine : UIMessage {
        override val code = "DRAW_NOT_SUPPORTED_BY_ENGINE"
    }

    // -- Lifecycle --

    data class GameCreationFailed(val error: ModuleError) : UIMessage {
        override val code = "GAME_CREATION_FAILED"
    }

    /**
     * A failure with nothing better to say about it than what an exception said.
     *
     * [diagnostic] is developer-facing (§2.6) and is not part of the message: a UI words this one
     * itself and may log the detail, but must not present it as the explanation. It is the last
     * resort, not a substitute for cataloguing a failure that turns out to be common.
     */
    data class UnexpectedFailure(val diagnostic: String?) : UIMessage {
        override val code = "UNEXPECTED_FAILURE"
    }

    /**
     * A message outside this catalogue.
     *
     * For an Orchestrator with something to say that the vocabulary does not name. Carries its own
     * identifier and, where it has one, wording that came from outside the system - the only text
     * §2.6 permits a UI to present as-is.
     */
    data class Other(
        val authorityCode: String? = null,
        val text: String? = null,
    ) : UIMessage {
        override val code = "OTHER"
    }
}
