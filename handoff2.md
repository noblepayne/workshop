Quick handoff notes for your agent:

**What to read:** audit-v2.md for context on *why* things changed, workshop.bb for the actual implementation. The audit maps 1:1 to the code.

**What got fixed (all in one shot):**
All Phase 1 and Phase 2 items. All Phase 3 items except the `?for=` filter semantics question — that one's left with a comment in the code explaining the OR behavior is intentional.

**API changes that will break existing tests:**
- `broadcast!` signature changed — now takes `msg-id` as third arg. Any test that calls it directly needs updating.
- `handle-task-done!` and `handle-task-abandon!` now return 403 if caller isn't the claiming agent, and 409 if task isn't in `claimed` status. Tests that called done on open tasks will now fail.
- `handle-task-claim!` now returns 409 (not nil/crash) when losing a race — tests that expected the old behavior need updating.
- `parse-body` now throws 400 on malformed JSON instead of returning `{}` — any test sending bad JSON intentionally needs to expect 400.

**New endpoints to add tests for:**
- `GET /history` — cross-channel, returns ndjson
- `GET /channels` — returns JSON array of channel name strings
- `GET /tasks/:id` — returns single task or 404

**One thing to double-check:** the go-sqlite3 pod behavior on `PRAGMA synchronous=NORMAL` — it's set in `init-db!` but the pod may open a fresh connection per query. If writes feel slow in testing, that's why. It's documented in the audit but not fixed (acceptable for this scale).

**Nothing removed from the API** — all existing endpoints work the same way for well-behaved callers. The only breaking changes are error responses that were previously wrong (nil body, no 409).
