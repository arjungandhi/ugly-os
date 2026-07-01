package com.uglyos.common.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoListTest {

    private fun sample(): TodoList =
        TodoList.parse(TaskFixtures.SAMPLE)

    @Test fun parseSkipsBlankLines() {
        val list = TodoList.parse("a\n\n  \nb\n")
        assertEquals(2, list.size)
    }

    @Test fun filtersByProjectAndContext() {
        val list = sample()
        assertEquals(2, list.withProject("GarageSale").size)
        assertEquals(2, list.withContext("phone").size)
    }

    @Test fun collectsDistinctSortedProjectsAndContexts() {
        val list = sample()
        assertEquals(listOf("Errands", "GarageSale"), list.projects())
        assertEquals(listOf("GroceryStore", "dmv", "home", "phone"), list.contexts())
    }

    @Test fun addAndRemovePreserveOrder() {
        val list = TodoList()
        list.add("first task")
        val idx = list.add(Task(description = "second task"))
        assertEquals(1, idx)
        assertEquals("first task", list[0].description)

        list.removeAt(0)
        assertEquals(1, list.size)
        assertEquals("second task", list[0].description)
    }

    @Test fun updateWithTransform() {
        val list = TodoList.parse("(A) do a thing @home")
        list.update(0) { it.copy(priority = 'B') }
        assertEquals('B', list[0].priority)
    }

    @Test fun sortedForDisplayPutsOpenHighPriorityFirstAndDoneLast() {
        val list = sample()
        val sorted = list.sortedForDisplay()
        assertEquals('A', sorted.first().priority)
        assertTrue(sorted.last().completed)
    }

    @Test fun renderRoundTripsThroughParse() {
        val list = sample()
        val reparsed = TodoList.parse(list.render())
        assertEquals(list.render(), reparsed.render())
    }
}
