package com.uglyos.common.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskTest {

    @Test fun parsesPlainDescription() {
        val t = Task.parse("Post signs around the neighborhood +GarageSale")!!
        assertFalse(t.completed)
        assertNull(t.priority)
        assertNull(t.creationDate)
        assertEquals(listOf("GarageSale"), t.projects)
    }

    @Test fun parsesPriorityCreationDateProjectsContextsAndTags() {
        val t = Task.parse("(A) 2026-06-01 Call plumber about leak @home due:2026-07-05")!!
        assertEquals('A', t.priority)
        assertEquals(LocalDate.of(2026, 6, 1), t.creationDate)
        assertEquals(listOf("home"), t.contexts)
        assertEquals("2026-07-05", t.tags["due"])
    }

    @Test fun parsesCompletedTaskWithCompletionAndCreationDates() {
        val t = Task.parse("x 2026-06-28 2026-06-20 Renew car registration +Errands @dmv")!!
        assertTrue(t.completed)
        assertEquals(LocalDate.of(2026, 6, 28), t.completionDate)
        assertEquals(LocalDate.of(2026, 6, 20), t.creationDate)
        assertEquals(listOf("Errands"), t.projects)
        assertEquals(listOf("dmv"), t.contexts)
    }

    @Test fun blankLineParsesToNull() {
        assertNull(Task.parse("   "))
        assertNull(Task.parse(""))
    }

    @Test fun leadingXWithoutSpaceIsNotCompletion() {
        val t = Task.parse("xylophone practice")!!
        assertFalse(t.completed)
        assertEquals("xylophone practice", t.description)
    }

    @Test fun malformedDateStaysInDescription() {
        val t = Task.parse("2026-13-40 not a real date")!!
        assertNull(t.creationDate)
        assertEquals("2026-13-40 not a real date", t.description)
    }

    @Test fun completeStampsDateAndReopenClearsIt() {
        val open = Task.parse("(A) Water the plants @home")!!
        val done = open.complete(LocalDate.of(2026, 6, 29))
        assertTrue(done.completed)
        assertEquals(LocalDate.of(2026, 6, 29), done.completionDate)

        val reopened = done.reopen()
        assertFalse(reopened.completed)
        assertNull(reopened.completionDate)
    }

    @Test fun invalidPriorityIsRejected() {
        try {
            Task(description = "x", priority = 'a')
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun formatRoundTripsRepresentativeLines() {
        val lines = listOf(
            "(A) Thank Mom for the meatballs @phone",
            "(A) 2026-06-01 Call plumber about leak @home due:2026-07-05",
            "x 2026-06-28 2026-06-20 Renew car registration +Errands @dmv",
        )
        for (line in lines) {
            assertEquals(line, Task.parse(line)!!.format())
        }
    }

    @Test fun lastValueWinsForRepeatedTag() {
        val t = Task.parse("do thing due:2026-01-01 due:2026-12-31")!!
        assertEquals("2026-12-31", t.tags["due"])
    }
}
