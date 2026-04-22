# Memory and RAG

## Chat memory

Per-session conversational history, kept as a sliding window (20 messages). Backed by
`data/sessions/<uuid>/chat.json` — auto-saved after every `say` or `reset`, reloaded on
session resume.

Commands:

- `say "..."` — send a message.
- `reset` — clear the current session's chat memory.
- `history` — print the history.

Each session has an independent memory. `new-session` starts fresh; `resume <uuid>` loads
another session's memory.

## RAG (Retrieval-Augmented Generation)

A shared in-memory vector store (`SimpleVectorStore`) per session, persisted to
`data/sessions/<uuid>/rag/` as:

- `vectors.json` — full embeddings (Spring AI's `SimpleVectorStore.save()` format).
- `assistant-docs.json` — docs added by `import`/`generate` (tracked separately).
- `plan-docs.json` — docs added by `ingest`/plan-pipeline `storeInRag` steps.

### Population

- `import <path-to-pdf>` — PDF pages/paragraphs → chunks → embedded → stored (assistant side).
- `generate "<instruction>"` — LLM generates one statement per line, each becomes a chunk.
- `ingest "<text>"` — any text, chunked by token splitter (plan side).
- `ingestProjectFile(rel)` — read a `data/project/` file and ingest it (plan side).
- Plan pipeline steps with `storeInRag: true` — output of that step is ingested (plan side).

### Retrieval

On every `say`, a `QuestionAnswerAdvisor` automatically retrieves top-K chunks relevant to
the user's query and prepends them as context — you don't need to do anything.

For debugging or explicit retrieval:

- `ask "<query>"` — direct vector lookup, shows top chunks with similarity scores (no LLM call).
- `show` — dump all assistant-tracked chunks.
- `rag` — dump all plan-tracked chunks.
- `docs` — counts.

### Pruning

- `forget` — remove only the assistant-tracked chunks (keeps plan side).
- `rag-clear` — remove only plan-tracked chunks (keeps assistant side).

### Cross-session

RAG is session-scoped. When you switch sessions, the vector store is reloaded from disk for
that session. Project-scope knowledge does NOT auto-ingest — use `ingestProjectFile` if you
need persistent project docs in the current session's RAG.
