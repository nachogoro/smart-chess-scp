package scp.protocol

/**
 * What every module's failures have in common: an identifying code, and text about it.
 *
 * The point of the shared type is that a failure can be handed on without being flattened into a
 * sentence first. Anything that wants to report one - the Orchestrator telling the UI a move was
 * refused, say - passes the error itself, and the UI decides what to make of the code.
 *
 * [code] is per-module and open: the codes this protocol names are in each module's section, and a
 * module MAY use one of its own. A consumer therefore always needs a branch for a code it does not
 * recognise.
 */
interface ModuleError {

    val code: String

    /**
     * Text about the failure, from a module that cannot know who is reading.
     *
     * Developer-facing by the rule in PROTOCOL.md §2.6 - logs, traces, a bug report - and not
     * something a UI should present as its explanation. It is still English prose today, and
     * turning it into arguments the UI can word itself is a later step; until then a UI showing it
     * is choosing detail it cannot translate over none at all.
     */
    val message: String
}
