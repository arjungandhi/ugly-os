package com.uglyos.common.todo

/**
 * An ordered, mutable collection of [Task]s — the in-memory model of a
 * todo.txt file. Line order is preserved so a parse/[render] round-trip keeps
 * the file stable apart from edits.
 */
class TodoList(tasks: List<Task> = emptyList()) : Iterable<Task> {
    private val _tasks: MutableList<Task> = tasks.toMutableList()

    /** A read-only snapshot of the tasks in file order. */
    val tasks: List<Task> get() = _tasks.toList()

    val size: Int get() = _tasks.size
    fun isEmpty(): Boolean = _tasks.isEmpty()

    operator fun get(index: Int): Task = _tasks[index]
    override fun iterator(): Iterator<Task> = _tasks.iterator()

    /** Append [task] and return its index. */
    fun add(task: Task): Int {
        _tasks.add(task)
        return _tasks.lastIndex
    }

    /** Parse [line] and append it; returns the new task, or null if blank. */
    fun add(line: String): Task? =
        Task.parse(line)?.also { _tasks.add(it) }

    /** Replace the task at [index]. */
    fun update(index: Int, task: Task) {
        _tasks[index] = task
    }

    /** Replace the task at [index] with the result of [transform]. */
    fun update(index: Int, transform: (Task) -> Task) {
        _tasks[index] = transform(_tasks[index])
    }

    /** Remove and return the task at [index]. */
    fun removeAt(index: Int): Task = _tasks.removeAt(index)

    fun remove(task: Task): Boolean = _tasks.remove(task)

    fun clear() = _tasks.clear()

    /** All tasks tagged with `+[project]`. */
    fun withProject(project: String): List<Task> =
        _tasks.filter { project in it.projects }

    /** All tasks tagged with `@[context]`. */
    fun withContext(context: String): List<Task> =
        _tasks.filter { context in it.contexts }

    /** Every distinct project across the list, sorted. */
    fun projects(): List<String> =
        _tasks.flatMap { it.projects }.distinct().sorted()

    /** Every distinct context across the list, sorted. */
    fun contexts(): List<String> =
        _tasks.flatMap { it.contexts }.distinct().sorted()

    /**
     * A view sorted for display, using [DISPLAY_ORDER]. Does not mutate this list.
     */
    fun sortedForDisplay(): List<Task> = _tasks.sortedWith(DISPLAY_ORDER)

    /** Serialize to todo.txt text, one task per line, in file order. */
    fun render(): String = _tasks.joinToString("\n") { it.format() }

    companion object {
        /**
         * The order [sortedForDisplay] uses, exposed so callers that track their
         * own line indices can sort without losing them: open tasks before done,
         * then by priority (A first, unprioritized last), then by due date
         * (soonest first, undated last), then description.
         */
        val DISPLAY_ORDER: Comparator<Task> =
            compareBy(
                { it.completed },
                { it.priority ?: '{' },
                { it.due ?: java.time.LocalDate.MAX },
                { it.description.lowercase() },
            )

        /** Parse whole-file [text], skipping blank lines. */
        fun parse(text: String): TodoList =
            TodoList(text.lineSequence().mapNotNull { Task.parse(it) }.toList())
    }
}
