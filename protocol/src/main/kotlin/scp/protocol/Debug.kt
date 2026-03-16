package scp.protocol

/**
 * Global debug logging flag, controlled by the `SCP_DEBUG` environment variable.
 * When true, [debugLog] prints messages to stdout.
 */
var SCP_DEBUG = System.getenv("SCP_DEBUG")?.toBooleanStrictOrNull() ?: false

/** Prints a debug message to stdout if [SCP_DEBUG] is enabled. */
fun debugLog(msg: String) {
    if (SCP_DEBUG) println(msg)
}
