# common

Shared library module for ugly launcher (`com.uglyos.common`). Consumed by
`:app`. Three pieces today: the Nord theme, a todo.txt library, and a notes library.

- `theme/` — shared Nord theme, exposed transitively (Compose + Material3).
- `todo/` — todo.txt parsing and editing.
- `notes/` — markdown notes storage (a directory of `.md` files).

## todo.txt library

`com.uglyos.common.todo` reads and writes
[todo.txt](https://github.com/todotxt/todo.txt) files.

- **`Task`** — one parsed line: `completed`, `priority` (`A`–`Z`), `creationDate`,
  `completionDate`, and the free-text `description`. Inline `+project`, `@context`,
  and `key:value` tags are exposed as the `projects`, `contexts`, and `tags` views;
  a `due:` tag is surfaced as the parsed `due` date. `parse()` never throws (blank
  lines and malformed dates degrade gracefully); `format()` round-trips back to a
  todo.txt line.
- **`TodoList`** — an ordered, mutable collection of tasks. Add/update/remove,
  filter by project/context, list distinct projects/contexts, and `render()`/
  `parse()` whole-file text. `sortedForDisplay()` orders open-before-done, then by
  priority, then by due date (soonest first); its `DISPLAY_ORDER` comparator is
  exposed for callers that track their own line indices.
- **`TodoFile`** — loads a `File` (empty list if absent) and saves atomically
  via a temp file + rename. `edit {}` loads, mutates, and saves in one call.

```kotlin
val todo = TodoFile(File(dir, "todo.txt"))
todo.edit { list ->
    list.add("(A) call mom @phone due:2026-07-01")
    list.update(0) { it.complete(LocalDate.now()) }
}
```

### Format reference

```
x (A) 2026-06-28 2026-06-20 Renew registration +Errands @dmv due:2026-07-01
│  │      │          │       │                                 │
│  │      │          │       └ description (holds +proj @ctx key:val inline)
│  │      │          └ creation date
│  │      └ completion date (done tasks only)
│  └ priority
└ completion marker
```

## notes library

`com.uglyos.common.notes` reads and writes a directory of markdown notes, one
`<title>.md` file per note. Kept dependency-free (`java.io.File` only) so it stays
testable without Android.

- **`Note`** — an immutable snapshot of one note: `title` (the filename without its
  `.md` extension), the full markdown `body`, and `lastModified` epoch millis (used
  to sort newest-first and to date a preview).
- **`NotesDir`** — a directory of notes. `list()` returns every `*.md` note,
  newest-modified first, skipping unreadable files. `save()` creates or edits a note,
  disambiguating a colliding title with " 2", " 3", ... falling back to "untitled" on
  a blank title, and sanitizing filename-illegal characters; a rename drops the old
  file. `delete()` removes a note's file. Writes go through a temp file + atomic
  rename, mirroring `TodoFile`, so a crash mid-write can't leave a half-written note.

```kotlin
val notes = NotesDir(dir)
val saved = notes.save(original = null, title = "groceries", body = "- eggs\n- milk")
notes.delete(saved)
```

## Tests

Unit tests live in `src/test/`, with a sample file at
`src/test/resources/todo.txt`. Run `just test` from the repo root, or
`./gradlew :common:testDebugUnitTest`.
