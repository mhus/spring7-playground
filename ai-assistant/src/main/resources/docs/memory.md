# Memory Management

The assistant has several distinct memory layers. Use `memory` for a unified dashboard of
all of them at once.

## Layers overview

| Layer | Scope | Where | Populated by | Cleared by |
|---|---|---|---|---|
| Chat | session | `chat.json` | `say` | `reset`, `memory-drop` |
| Pins | session | `pins.json` | `pin` | `unpin` |
| Remembered facts | **global** | `data/project/memory.json` | `remember`, tool `remember()` | `unremember` |
| Base settings | session | `base-settings.json` | `set` | `unset` |
| AGENT.md | global | `./AGENT.md` | user or `writeFile`/`editFile` | manual |
| RAG (assistant) | session | `rag/` | `import`, `generate` | `forget` |
| RAG (plan) | session | `rag/` | `ingest`, pipeline `storeInRag`, `ingestProjectFile` | `rag-clear` |
| Plan state | session | `plans/*.json` | `plan`, `refine`, `answer`, `force` | `delete-plan` |
| Workspace | session | `workspace/` | `writeSessionFile` | `deleteSessionFile` |
| Project | global | `data/project/` | `writeProjectFile` | `deleteProjectFile` |
| Exec jobs | session | `exec/` | `executeCommand`, `exec` | — |
| Tokens | process | in-memory | LLM calls | `tokens-reset` |

## `memory` command (dashboard)

Quick check-in of everything:
```
shell:>memory
session     c3f2…
chat        6 messages (window 20)
pins        2 pinned fact(s)
rag         42 chunks (27 assistant, 15 plan)
plan        current: roman-ueber-leuchttuerme-20260423-101245
settings    2 base setting(s)
workspace   3 file(s)
project     12 file(s)
exec        8 task(s) (0 running)
ext-tools   1 tool(s) in registry
tokens      41200 tokens across 18 call(s), 5 source(s)
```

## Chat memory (sliding window)

- 20 messages max, auto-rotate oldest out.
- Persisted on every `say` / `reset`.
- Survives process restart and session resume.

**Commands:**
- `say "..."` — adds a user + assistant turn.
- `history` — show full current chat with roles and content.
- `reset` — wipe everything for the current session.
- `memory-drop <index>` or `memory-drop <from-to>` — surgical removal of specific messages.
  Useful when a bad tool-call chain or misleading turn is polluting context. Warning:
  deleting only one side of a tool-call/tool-response pair may confuse the LLM — if unsure,
  drop both.

## Pins (session-scoped facts)

Pins bypass the sliding-window limitation for **the current session**. They're injected
into the system prompt on every `say` call, so the agent cannot forget them regardless of
how old the chat gets — but only in this session.

Use for: short-lived facts relevant only to this conversation, temporary constraints,
current working goals.

**Commands:**
- `pin "<fact>"` — add a pin.
- `pins` — list with 1-based indices.
- `unpin <n>` — remove one.
- `unpin all` — remove all.

Persisted in `pins.json` per session. Reloaded on session switch.

## Remembered facts (global across all sessions)

Same mechanism as pins but scoped **globally** — visible to every session, past and
future. The agent is instructed to self-populate this store whenever it learns something
worth keeping beyond the current conversation.

Use for: user preferences, project invariants, key decisions, long-lived reminders —
things that are too small/dynamic for AGENT.md but too important to lose on session end.

**Commands (shell):**
- `remember "<fact>"` — add a global fact.
- `remembered` — list.
- `unremember <n>` / `unremember all` — remove.

**Tools (agent-callable):**
- `remember(fact)` — permanent save.
- `listRemembered()` — see all.
- `forgetRemembered(index)` — remove one.

Persisted in `data/project/memory.json`. No session reload needed — loaded once on
startup, updated in-place.

### Memory hierarchy (what to use when)

| Stay for | Use |
|---|---|
| This turn only | chat memory (automatic) |
| This session | `pin` |
| All future sessions | `remember` (dynamic) OR `AGENT.md` (curated/versioned) |
| Large documents, searchable | RAG (`import` / `ingest`) |

`remember` vs `AGENT.md`: use `remember` for fluid facts you add/remove often. Use
AGENT.md for stable team-wide project rules that you'd commit to git.

## Base settings

Runtime preferences that apply to **every** LLM invocation — main chat, planner, sub-task.
Typically language, tone, output format.

**Commands:**
- `set "sprache: deutsch"` — add a setting.
- `settings` — list.
- `unset` — clear all.

Persisted in `base-settings.json`. Reloaded on session switch.

## RAG (vector store)

See also: retrieval-augmented generation is tightly coupled with chat — the
`QuestionAnswerAdvisor` automatically prepends the top-K matching chunks to every `say`.

**Assistant side:** `import <pdf>`, `generate "<instruction>"`, `show`, `docs`, `forget`.
**Plan side:** `ingest "<text>"`, `rag`, `rag-clear`, plus any pipeline step with
`storeInRag: true` writes its output here.

Both sides share the underlying vector store but track their own IDs so clearing one
doesn't touch the other.

## Pro tips

- When the chat starts behaving "weirdly", run `memory` — often the chat memory has
  grown noisy and a targeted `memory-drop` or full `reset` helps.
- Use `pin` for ground-truth facts; prefer that over repeating them in every prompt.
- Base settings should be stylistic/behavioral. Factual content belongs in pins.
- RAG is for larger bodies of text you want queryable, not for 2-3 sentence facts.
