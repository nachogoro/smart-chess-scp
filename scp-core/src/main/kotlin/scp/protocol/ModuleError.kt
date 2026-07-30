package scp.protocol

/**
 * What every module's failures have in common: an identifying code, and whatever data goes with it.
 *
 * A failure crosses a module boundary as a code and typed values, never as a sentence. The module
 * raising it cannot know who will read the result (PROTOCOL.md §2.6), so it says what happened and
 * leaves the wording to the UI. Values a UI needs in order to word it - which move was refused,
 * which squares could not be read - are fields on the error itself.
 *
 * [code] is per-module and open: a module MAY report one of its own, so a consumer always needs a
 * branch for a code it does not recognise.
 */
interface ModuleError {

    val code: String

    /**
     * Free text about the failure, for developers.
     *
     * Logs, traces, a bug report - an exception message, typically. Named so it cannot be mistaken
     * for something displayable: it is written by a module that has no idea what language the user
     * reads, and a UI MUST NOT present it as the explanation.
     *
     * Text that came from *outside* the system is different and is not this: a remote server's own
     * words are carried in a field of their own, because showing those is the one thing §2.6
     * permits.
     */
    val diagnostic: String? get() = null
}
