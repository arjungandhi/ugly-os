# common

Shared library module for ugly launcher (`com.uglyos.common`). Consumed by
`:app`. Two pieces today: the Nord theme and a todo.txt library.

- `theme/` — shared Nord theme, exposed transitively (Compose + Material3).
- `todo/` — todo.txt parsing and editing.

## todo.txt library

`com.uglyos.common.todo` reads and writes
[todo.txt](https://github.com/todotxt/todo.txt) files.

- **`Task`** — one parsed line: `completed`, `priority` (`A`–`Z`), `creationDate`,
  `completionDate`, and the free-text `description`. Inline `+project`, `@context`,
  and `key:value` tags are exposed as the `projects`, `contexts`, and `tags` views.
  `parse()` never throws (blank lines and malformed dates degrade gracefully);
  `format()` round-trips back to a todo.txt line.
- **`TodoList`** — an ordered, mutable collection of tasks. Add/update/remove,
  filter by project/context, list distinct projects/contexts, `sortedForDisplay()`,
  and `render()`/`parse()` whole-file text.
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

## Tests

Unit tests live in `src/test/`, with a sample file at
`src/test/resources/todo.txt`. Run `just test` from the repo root, or
`./gradlew :common:testDebugUnitTest`.
