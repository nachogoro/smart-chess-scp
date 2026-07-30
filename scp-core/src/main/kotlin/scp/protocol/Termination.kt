package scp.protocol

/**
 * How a game ended.
 *
 * An open vocabulary (PROTOCOL.md §2.7): the cases below, plus [Other] for an ending this version
 * of the protocol does not name. A producer that cannot classify an ending reports [Other] rather
 * than picking the nearest code, so a consumer is never quietly told the wrong thing.
 *
 * Distinct from [GameResult], which says who won and needs no translating. This says why, and a UI
 * puts it into words - which is the reason it is a code and not a sentence.
 */
sealed interface Termination {

    /** This protocol's identifier for the ending. [Other] reports `OTHER`. */
    val code: String

    data object Checkmate : Termination {
        override val code = "CHECKMATE"
    }

    data object Stalemate : Termination {
        override val code = "STALEMATE"
    }

    data object Resignation : Termination {
        override val code = "RESIGNATION"
    }

    data object Timeout : Termination {
        override val code = "TIMEOUT"
    }

    data object Aborted : Termination {
        override val code = "ABORTED"
    }

    data class Drawn(val kind: DrawKind) : Termination {
        override val code = "DRAWN"
    }

    /**
     * An ending outside this vocabulary.
     *
     * [authorityCode] is the identifier used by whoever reported it - a remote server's own status
     * string - and [text] its own words, where it supplied any. Both may be absent when the ending
     * is simply unknown.
     *
     * A UI must handle this case, and may recognise particular [authorityCode] values and word
     * them itself without any change here. When one turns out to be common enough to deserve a
     * name, promoting it to a case above is backward compatible: consumers already handle what
     * they do not recognise.
     */
    data class Other(
        val authorityCode: String? = null,
        val text: String? = null,
    ) : Termination {
        override val code = "OTHER"
    }
}

/**
 * Which rule drew a game.
 *
 * Open for the same reason as [Termination]: a server may draw a game under a rule this protocol
 * has never heard of, and losing "it was a draw" as well as "why" would be worse than losing only
 * the second.
 */
sealed interface DrawKind {

    val code: String

    data object Agreement : DrawKind {
        override val code = "AGREEMENT"
    }

    data object InsufficientMaterial : DrawKind {
        override val code = "INSUFFICIENT_MATERIAL"
    }

    data object FiftyMoveRule : DrawKind {
        override val code = "FIFTY_MOVE_RULE"
    }

    data object SeventyFiveMoveRule : DrawKind {
        override val code = "SEVENTY_FIVE_MOVE_RULE"
    }

    data object ThreefoldRepetition : DrawKind {
        override val code = "THREEFOLD_REPETITION"
    }

    data object FivefoldRepetition : DrawKind {
        override val code = "FIVEFOLD_REPETITION"
    }

    /** A draw whose rule this vocabulary does not name, or which nobody reported. */
    data class Other(
        val authorityCode: String? = null,
        val text: String? = null,
    ) : DrawKind {
        override val code = "OTHER"
    }
}
