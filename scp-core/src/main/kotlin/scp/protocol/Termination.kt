package scp.protocol

/**
 * Why a game stopped.
 *
 * One axis, and only one: the cause. Who won is [GameResult]'s to say and needs no translating;
 * where the game is in its life is [GameStatus]'s. Keeping the three apart is what lets all three
 * be true at once - a player whose flag falls against an opponent who cannot mate has *timed out*
 * and *drawn*, and those are answers to different questions rather than a contradiction.
 *
 * An open vocabulary (PROTOCOL.md §2.7): the cases below, plus [Other] for a cause this version
 * does not name. A producer that cannot classify one reports [Other] rather than picking the
 * nearest code, so a consumer is never quietly told the wrong thing.
 *
 * There is deliberately no `DRAWN` case. A draw is an outcome, not a cause, and putting it here
 * forced the real cause - agreement, a repetition, a rule - to be nested underneath it, which in
 * turn made an ending that was both a timeout and a draw impossible to express. The drawn-ness
 * lives in [GameResult.DRAW], where it is carried whether or not the cause is one we recognise.
 */
sealed interface Termination {

    /** This protocol's identifier for the cause. [Other] reports `OTHER`. */
    val code: String

    // -- Decided on the board --

    data object Checkmate : Termination {
        override val code = "CHECKMATE"
    }

    data object Stalemate : Termination {
        override val code = "STALEMATE"
    }

    // -- Decided by a player --

    data object Resignation : Termination {
        override val code = "RESIGNATION"
    }

    /** Both players agreed to a draw. */
    data object Agreement : Termination {
        override val code = "AGREEMENT"
    }

    // -- Decided by the clock --

    /**
     * A player's clock ran out.
     *
     * Says nothing about who won: usually the player who flagged loses, but under FIDE 6.9 the
     * game is drawn where their opponent could not have mated with the material left. Both are a
     * `TIMEOUT`; [GameResult] is where they differ.
     */
    data object Timeout : Termination {
        override val code = "TIMEOUT"
    }

    // -- Decided by a rule --

    data object InsufficientMaterial : Termination {
        override val code = "INSUFFICIENT_MATERIAL"
    }

    data object FiftyMoveRule : Termination {
        override val code = "FIFTY_MOVE_RULE"
    }

    data object SeventyFiveMoveRule : Termination {
        override val code = "SEVENTY_FIVE_MOVE_RULE"
    }

    data object ThreefoldRepetition : Termination {
        override val code = "THREEFOLD_REPETITION"
    }

    data object FivefoldRepetition : Termination {
        override val code = "FIVEFOLD_REPETITION"
    }

    // -- Neither --

    /** The game was abandoned before it could finish. */
    data object Aborted : Termination {
        override val code = "ABORTED"
    }

    /**
     * A cause outside this vocabulary.
     *
     * [authorityCode] is the identifier used by whoever reported it - a remote server's own status
     * string - and [text] its own words, where it supplied any. Both may be absent when the cause
     * is simply unknown; a UI must handle that, because the result still says how the game turned
     * out even when nothing says why.
     *
     * A UI may recognise particular [authorityCode] values and word them itself without any change
     * here. When one turns out to be common enough to deserve a name, promoting it to a case above
     * is backward compatible: consumers already handle what they do not recognise.
     */
    data class Other(
        val authorityCode: String? = null,
        val text: String? = null,
    ) : Termination {
        override val code = "OTHER"
    }
}
