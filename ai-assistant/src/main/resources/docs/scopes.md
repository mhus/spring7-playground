# File Scopes

There are three distinct filesystem scopes. Pick the right one based on lifetime and sharing.

## 1. Global (FileTools)

Tools: `listDirectory`, `readFile`, `writeFile`, `editFile`, `appendFile`.

Paths are absolute or relative to the JVM working directory. No sandboxing — you can read
and modify anything the process has permission for. Use for:

- Reading and editing user project source code (e.g. a `.java` file the user asks you to change).
- Creating output files the user explicitly asked about ("schreib mir ein README.md").

`editFile` replaces a single unique occurrence — it fails if `oldText` is not found or
appears more than once. Include enough context to make the match unique.

## 2. Project scope (ProjectTools)

Tools: `writeProjectFile`, `readProjectFile`, `editProjectFile`, `deleteProjectFile`,
`listProjectFiles`, `getProjectPath`, `ingestProjectFile`.

Lives at `data/project/` (absolute via `getProjectPath()`). Session-independent — stays
across restarts and session switches. Use for:

- Long-lived notes you want to remember across conversations.
- Reference documents that every session should be able to load into RAG.
- Design decisions, glossaries, project-specific knowledge.

All tools take **relative** paths. Path escape (`..` or absolute) is blocked.

`ingestProjectFile(rel)` chunks a project file and puts it into the CURRENT SESSION's RAG.
Because RAG is session-scoped, re-ingest after switching sessions if you need the content
queryable.

## 3. Session workspace (WorkspaceTools)

Tools: `writeSessionFile`, `readSessionFile`, `editSessionFile`, `deleteSessionFile`,
`listSessionFiles`, `getSessionWorkspacePath`, `executeSessionJavaScript`.

Lives at `data/sessions/<uuid>/workspace/`. Tied to one session — disappears conceptually
when you start a new session (files remain on disk but belong to that session's history).
Use for:

- Scratch work: JS scripts you're iteratively developing, intermediate calculations.
- Per-task artifacts that don't need to outlive the session.
- Anything you want to test (`executeSessionJavaScript` runs `.js` files via Mozilla Rhino).

Relative paths only, path escape blocked.

## Decision guide

- "User wants me to edit their code" → global (FileTools).
- "I learned something I want future sessions to benefit from" → project scope.
- "I'm building a little script to solve this task" → session workspace.

## Paths for shell work

Both `getProjectPath()` and `getSessionWorkspacePath()` return absolute paths suitable for
shell commands, e.g. `executeCommand("grep -rn TODO <projectPath>")`.
