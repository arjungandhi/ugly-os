package com.uglyos.common.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class TodoFileTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun loadMissingFileReturnsEmptyList() {
        val todo = TodoFile(File(tmp.root, "nope.txt"))
        assertTrue(todo.load().isEmpty())
    }

    @Test fun saveThenLoadRoundTrips() {
        val file = tmp.newFile("todo.txt")
        val todo = TodoFile(file)

        val list = TodoList()
        list.add("(A) call mom @phone due:2026-07-01")
        list.add("water plants @home")
        todo.save(list)

        val loaded = todo.load()
        assertEquals(2, loaded.size)
        assertEquals('A', loaded[0].priority)
        assertEquals("2026-07-01", loaded[0].tags["due"])
    }

    @Test fun editLoadsMutatesAndSaves() {
        val file = tmp.newFile("todo.txt")
        file.writeText("(A) do a thing @home\n")
        val todo = TodoFile(file)

        todo.edit { it.update(0) { task -> task.complete(LocalDate.of(2026, 6, 30)) } }

        val reloaded = todo.load()
        assertTrue(reloaded[0].completed)
        assertEquals(LocalDate.of(2026, 6, 30), reloaded[0].completionDate)
    }

    @Test fun savedFileEndsWithNewline() {
        val file = tmp.newFile("todo.txt")
        val todo = TodoFile(file)
        todo.save(TodoList().apply { add("single task") })
        assertTrue(file.readText().endsWith("\n"))
    }
}
