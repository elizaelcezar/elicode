package com.elicode.app.core

/**
 * Structured error used across every engine (runtime, git, build, agent...).
 *
 * No technical failure may surface as "unknown error": every [EliError]
 * carries the operation, the command that was executed, the exit code,
 * the raw message, a probable cause and a suggested fix.
 */
data class EliError(
    val operation: String,
    val command: String = "",
    val exitCode: Int = -1,
    val message: String = "",
    val probableCause: String = "",
    val suggestedFix: String = ""
) {
    fun format(): String {
        val sb = StringBuilder()
        sb.append(operation)
        if (exitCode >= 0) sb.append(" (exit $exitCode)")
        if (command.isNotBlank()) sb.append("\nCommand: $command")
        if (message.isNotBlank()) sb.append("\nDetails: ${message.trim().take(2000)}")
        if (probableCause.isNotBlank()) sb.append("\nProbable cause: $probableCause")
        if (suggestedFix.isNotBlank()) sb.append("\nSuggested fix: $suggestedFix")
        return sb.toString()
    }

    companion object {
        fun unknown(operation: String, t: Throwable) = EliError(
            operation = operation,
            message = "${t.javaClass.simpleName}: ${t.message}",
            probableCause = "Unexpected exception while running $operation.",
            suggestedFix = "Check Diagnostics for details and retry. If it persists, repair the runtime in Settings."
        )
    }
}

/** Result wrapper used by engines: success value or structured [EliError]. */
sealed class EliResult<out T> {
    data class Ok<out T>(val value: T) : EliResult<T>()
    data class Err(val error: EliError) : EliResult<Nothing>()

    fun isOk(): Boolean = this is Ok
    fun errorOrNull(): EliError? = (this as? Err)?.error
    fun getOrNull(): T? = (this as? Ok)?.value
}
