package com.uglyos.launcher

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.uglyos.common.theme.UglyTheme
import com.uglyos.common.todo.Task
import com.uglyos.common.todo.TodoList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The todo.txt file lives at this path inside the monkey dir. */
private val TODO_PATH = listOf("atp", "todo", "todo.txt")

/** What the todo page has to show once it's tried to load the file. */
private sealed interface TodoState {
    /** No monkey dir configured yet. */
    object NoDir : TodoState
    /** Monkey dir set, but the todo.txt file wasn't found. */
    object NotFound : TodoState
    /** Parsed tasks, already filtered and sorted for display. */
    data class Loaded(val tasks: List<Task>) : TodoState
}

/** Read the raw todo.txt text from the monkey dir, or null if unavailable. */
private fun readTodoText(context: Context, monkeyDir: Uri): String? {
    var dir: DocumentFile? = DocumentFile.fromTreeUri(context, monkeyDir)
    for (segment in TODO_PATH.dropLast(1)) {
        dir = dir?.findFile(segment)
    }
    val file = dir?.findFile(TODO_PATH.last()) ?: return null
    return context.contentResolver.openInputStream(file.uri)
        ?.bufferedReader()?.use { it.readText() }
}

/**
 * A read-only todo.txt list page. [filter] narrows which tasks appear so the
 * same component can back several pages (e.g. one excluding a context, one
 * showing only it). Tasks come out sorted for display (open before done).
 */
@Composable
fun TodoPage(title: String, filter: (Task) -> Boolean) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val monkeyDir = Settings.monkeyDir(context)

    val state by produceState<TodoState>(TodoState.NoDir, monkeyDir) {
        value = if (monkeyDir == null) {
            TodoState.NoDir
        } else {
            withContext(Dispatchers.IO) {
                val text = readTodoText(context, monkeyDir)
                if (text == null) {
                    TodoState.NotFound
                } else {
                    TodoState.Loaded(TodoList.parse(text).sortedForDisplay().filter(filter))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
    ) {
        Text(
            text = title,
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        when (val s = state) {
            TodoState.NoDir -> Hint("set the monkey dir in settings")
            TodoState.NotFound -> Hint("no todo.txt found in monkey dir")
            is TodoState.Loaded ->
                if (s.tasks.isEmpty()) {
                    Hint("nothing here")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(s.tasks) { TaskRow(it) }
                    }
                }
        }
    }
}

/** A dimmed, centered message for empty/unconfigured states. */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = UglyTheme.colors.mutedForeground,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** One task line: optional priority badge, then the description. */
@Composable
private fun TaskRow(task: Task) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        task.priority?.let {
            Text(
                text = "($it)",
                color = colors.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = task.description,
            color = if (task.completed) colors.mutedForeground else colors.foreground,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
        )
    }
}
