package com.uglyos.common.todo

/** Shared test data, loaded from `src/test/resources/todo.txt`. */
object TaskFixtures {
    val SAMPLE: String =
        javaClass.getResourceAsStream("/todo.txt")!!
            .bufferedReader().use { it.readText() }
}
