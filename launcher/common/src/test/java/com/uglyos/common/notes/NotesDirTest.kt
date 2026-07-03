package com.uglyos.common.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotesDirTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun notes() = NotesDir(tmp.root)

    /** Write a note file directly with a fixed mtime for deterministic sorting. */
    private fun seed(title: String, body: String, mtime: Long): File {
        val file = File(tmp.root, "$title.md")
        file.writeText(body)
        file.setLastModified(mtime)
        return file
    }

    @Test fun listSortsNewestModifiedFirst() {
        seed("old", "old body", 1_000L)
        seed("new", "new body", 3_000L)
        seed("mid", "mid body", 2_000L)

        val listed = notes().list()

        assertEquals(listOf("new", "mid", "old"), listed.map { it.title })
        assertEquals("new body", listed[0].body)
    }

    @Test fun listIgnoresNonMarkdownFiles() {
        seed("keep", "yes", 1_000L)
        File(tmp.root, "notes.txt").writeText("no")
        File(tmp.root, "image.png").writeText("no")
        File(tmp.root, "README").writeText("no")

        val listed = notes().list()

        assertEquals(listOf("keep"), listed.map { it.title })
    }

    @Test fun listOnMissingDirIsEmpty() {
        val missing = NotesDir(File(tmp.root, "does-not-exist"))
        assertTrue(missing.list().isEmpty())
    }

    @Test fun saveCreatesNewNote() {
        val saved = notes().save(null, "grocery list", "milk\neggs")

        assertEquals("grocery list", saved.title)
        assertEquals("milk\neggs", saved.body)
        assertTrue(File(tmp.root, "grocery list.md").exists())
        assertEquals("milk\neggs", File(tmp.root, "grocery list.md").readText())
    }

    @Test fun saveUpdatesInPlaceWithoutRename() {
        val first = notes().save(null, "todo", "a")
        val second = notes().save(first, "todo", "a\nb")

        assertEquals("todo", second.title)
        assertEquals("a\nb", File(tmp.root, "todo.md").readText())
        assertEquals(1, tmp.root.listFiles { f -> f.extension == "md" }!!.size)
    }

    @Test fun saveRenamesAndRemovesOldFile() {
        val first = notes().save(null, "draft", "content")
        val renamed = notes().save(first, "final", "content")

        assertEquals("final", renamed.title)
        assertTrue(File(tmp.root, "final.md").exists())
        assertFalse(File(tmp.root, "draft.md").exists())
    }

    @Test fun saveUniqueNamesOnCollisionWithDifferentNote() {
        notes().save(null, "meeting", "first")
        val second = notes().save(null, "meeting", "second")

        assertEquals("meeting 2", second.title)
        assertEquals("first", File(tmp.root, "meeting.md").readText())
        assertEquals("second", File(tmp.root, "meeting 2.md").readText())
    }

    @Test fun saveKeepsClimbingWhenSuffixAlsoTaken() {
        notes().save(null, "note", "1")
        notes().save(null, "note", "2")
        val third = notes().save(null, "note", "3")

        assertEquals("note 3", third.title)
    }

    @Test fun editingNoteToItsOwnNameDoesNotAppendSuffix() {
        val first = notes().save(null, "kept", "v1")
        val edited = notes().save(first, "kept", "v2")

        assertEquals("kept", edited.title)
        assertEquals("v2", File(tmp.root, "kept.md").readText())
    }

    @Test fun renameOntoExistingDifferentNoteUniqueNames() {
        notes().save(null, "alpha", "a")
        val beta = notes().save(null, "beta", "b")

        val renamed = notes().save(beta, "alpha", "b")

        assertEquals("alpha 2", renamed.title)
        assertEquals("a", File(tmp.root, "alpha.md").readText())
        assertEquals("b", File(tmp.root, "alpha 2.md").readText())
        assertFalse(File(tmp.root, "beta.md").exists())
    }

    @Test fun blankTitleFallsBackToUntitled() {
        val saved = notes().save(null, "   ", "body")

        assertEquals("untitled", saved.title)
        assertTrue(File(tmp.root, "untitled.md").exists())
    }

    @Test fun blankTitleSecondTimeUniqueNames() {
        notes().save(null, "", "one")
        val second = notes().save(null, "", "two")

        assertEquals("untitled", File(tmp.root, "untitled.md").nameWithoutExtension)
        assertEquals("untitled 2", second.title)
    }

    @Test fun saveSanitizesPathSeparators() {
        val saved = notes().save(null, "foo/bar\\baz", "body")

        assertEquals("foo bar baz", saved.title)
        assertTrue(File(tmp.root, "foo bar baz.md").exists())
    }

    @Test fun saveSanitizesOtherIllegalChars() {
        val saved = notes().save(null, "a:b*c?d\"e<f>g|h", "body")

        assertEquals("a b c d e f g h", saved.title)
        assertTrue(File(tmp.root, "a b c d e f g h.md").exists())
    }

    @Test fun saveKeepsHyphens() {
        val saved = notes().save(null, "well-known-note", "body")

        assertEquals("well-known-note", saved.title)
    }

    @Test fun deleteRemovesFile() {
        val saved = notes().save(null, "trash", "junk")
        assertTrue(File(tmp.root, "trash.md").exists())

        notes().delete(saved)

        assertFalse(File(tmp.root, "trash.md").exists())
    }

    @Test fun deleteMissingFileIsNoOp() {
        val ghost = Note("ghost", "", 0L)
        notes().delete(ghost)
        assertNull(tmp.root.listFiles { f -> f.name == "ghost.md" }?.firstOrNull())
    }
}
