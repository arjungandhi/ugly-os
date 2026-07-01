package com.uglyos.common.todo

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A single line of a todo.txt file, parsed into its parts.
 *
 * Follows the todo.txt format (https://github.com/todotxt/todo.txt):
 * an optional `x ` completion marker, an optional `(A)` priority, up to two
 * ISO dates (completion then creation for done tasks, creation for open ones),
 * and the free-text [description]. Projects (`+tag`), contexts (`@tag`) and
 * `key:value` tags live inline in the description and are exposed as views.
 *
 * Instances are immutable; use [copy] or the helpers below to derive new tasks.
 */
data class Task(
    val description: String = "",
    val completed: Boolean = false,
    val priority: Char? = null,
    val creationDate: LocalDate? = null,
    val completionDate: LocalDate? = null,
) {
    init {
        require(priority == null || priority in 'A'..'Z') {
            "priority must be an uppercase letter A-Z, was '$priority'"
        }
    }

    /** `+project` tags in the description, in order of appearance, without the `+`. */
    val projects: List<String> get() = PROJECT_REGEX.findValues(description)

    /** `@context` tags in the description, in order of appearance, without the `@`. */
    val contexts: List<String> get() = CONTEXT_REGEX.findValues(description)

    /**
     * `key:value` tags in the description (e.g. `due:2026-06-30`). A key that
     * repeats keeps its last value.
     */
    val tags: Map<String, String>
        get() = TAG_REGEX.findAll(description).associate {
            it.groupValues[1] to it.groupValues[2]
        }

    /** This task marked done, stamping [on] as the completion date. */
    fun complete(on: LocalDate): Task =
        copy(completed = true, completionDate = on)

    /** This task reopened, clearing the completion marker and date. */
    fun reopen(): Task =
        copy(completed = false, completionDate = null)

    /** Serialize back to a single todo.txt line. Round-trips with [parse]. */
    fun format(): String = buildString {
        if (completed) append("x ")
        priority?.let { append("($it) ") }
        if (completed) completionDate?.let { append(it).append(' ') }
        creationDate?.let { append(it).append(' ') }
        append(description)
    }.trim()

    override fun toString(): String = format()

    companion object {
        private val PRIORITY_REGEX = Regex("""^\(([A-Z])\)\s+""")
        private val DATE_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})(\s+|$)""")
        private val PROJECT_REGEX = Regex("""(?:^|\s)\+(\S+)""")
        private val CONTEXT_REGEX = Regex("""(?:^|\s)@(\S+)""")
        private val TAG_REGEX = Regex("""(?:^|\s)([^\s:]+):([^\s:]+)""")

        private fun Regex.findValues(input: String): List<String> =
            findAll(input).map { it.groupValues[1] }.toList()

        /**
         * Parse a single todo.txt line. Blank lines return null. Malformed
         * dates are treated as ordinary words, so parsing never throws.
         */
        fun parse(line: String): Task? {
            var rest = line.trim()
            if (rest.isEmpty()) return null

            var completed = false
            if (rest.startsWith("x ")) {
                completed = true
                rest = rest.substring(2).trimStart()
            }

            var priority: Char? = null
            PRIORITY_REGEX.find(rest)?.let {
                priority = it.groupValues[1][0]
                rest = rest.substring(it.value.length)
            }

            // Done tasks carry completion then creation date; open tasks carry
            // only a creation date.
            var completionDate: LocalDate? = null
            var creationDate: LocalDate? = null
            if (completed) {
                completionDate = takeLeadingDate(rest)?.also { rest = dropLeadingDate(rest) }
            }
            creationDate = takeLeadingDate(rest)?.also { rest = dropLeadingDate(rest) }

            return Task(
                description = rest,
                completed = completed,
                priority = priority,
                creationDate = creationDate,
                completionDate = completionDate,
            )
        }

        private fun takeLeadingDate(s: String): LocalDate? {
            val match = DATE_REGEX.find(s) ?: return null
            return try {
                LocalDate.parse(match.groupValues[1])
            } catch (e: DateTimeParseException) {
                null
            }
        }

        private fun dropLeadingDate(s: String): String {
            val match = DATE_REGEX.find(s) ?: return s
            // Only advance if the date actually parsed; callers pair this with
            // takeLeadingDate, which returns null on unparseable dates.
            return try {
                LocalDate.parse(match.groupValues[1])
                s.substring(match.value.length)
            } catch (e: DateTimeParseException) {
                s
            }
        }
    }
}
