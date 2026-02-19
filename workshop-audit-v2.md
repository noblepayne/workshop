# workshop.bb — Revised Audit & Execution Plan

**Scope:** get this to a reliable, productive MVP for a small trusted mesh of agents + humans. Not enterprise. The design is right. We're fixing bugs, not adding architecture.

Issues that were in v1 of this audit but are **dropped** here because they're out of scope:
- CORS max-age (agents on the mesh don't need CORS)
- WAL pragma per-connection (fine for this scale)
- TOCTOU on task claim (SQLite serialises writes, this doesn't happen in practice)
- `composeEnter` global event (only affects ancient Firefox, not worth the noise)
- Task interrupt NPE path (theoretical, not reachable)
- Blob cleanup on message delete (README explicitly calls this out of scope)
- Cleanup-on-restart behavior (intentional, should be documented not changed)

What remains: **15 real issues**, triaged honestly.

---

## The Bugs

### B1 — SSE headers sent as data frame `[BREAKS EVERYTHING]`

`http/send!` inside `:on-open` writes bytes after the HTTP response is committed. The `{:status 200 :headers ...}` map gets serialised as a garbage SSE data frame. `EventSource` in every browser rejects it and retries every 3 seconds — the infinite loop you see.

```clojure
;; ❌ current
(defn handle-stream [req ch]
  (http/as-channel req
    {:on-open (fn [client]
                (http/send! client {:status 200 :headers {"Content-Type" "text/event-stream" ...}})
                (sub! ch client))
     :on-close (fn [client _] (unsub! ch client))}))

;; ✅ fix — :init sets the response headers before the channel opens
(defn handle-stream [req ch]
  (http/as-channel req
    {:init     {:status  200
                :headers {"Content-Type"         "text/event-stream"
                          "Cache-Control"         "no-cache"
                          "X-Accel-Buffering"     "no"
                          "Connection"            "keep-alive"
                          "Access-Control-Allow-Origin" "*"}}
     :on-open  (fn [client] (sub! ch client))
     :on-close (fn [client _] (unsub! ch client))}))
```

`X-Accel-Buffering: no` is critical if you put nginx in front — without it nginx buffers the entire SSE stream and the client sees nothing until the buffer flushes (64KB by default), which looks identical to "not connected".

**Applies to both `handle-stream` and the `:all` handler at `GET /`.**

---

### B2 — Dead clients accumulate forever `[MEMORY LEAK]`

Both `broadcast!` and `sse-keepalive!` catch send exceptions on dead clients but never remove them from `@subscribers`. The set grows monotonically. Every tab close, every agent restart adds a permanently broken client that gets a failed send attempt on every broadcast and every 20-second keepalive.

There's also a subtler problem: `doseq` over `(get @subscribers ch #{})` gets a snapshot of the outer map but the inner set is a live reference. If another thread calls `unsub!` concurrently, you're iterating a set that's being modified.

```clojure
;; ✅ fix broadcast! — snapshot the set, unsub on failure
(defn broadcast! [ch msg-str]
  (let [payload (str "data: " msg-str "\n\n")]
    (doseq [client (into [] (get @subscribers ch #{}))]
      (try (http/send! client payload)
           (catch Exception _ (unsub! ch client))))
    (when (not= ch :all)
      (doseq [client (into [] (get @subscribers :all #{}))]
        (try (http/send! client payload)
             (catch Exception _ (unsub! ch :all)))))))

;; ✅ fix sse-keepalive! — same pattern
(defn sse-keepalive! []
  (future
    (loop []
      (Thread/sleep 20000)
      (let [ping ": keepalive\n\n"]
        (doseq [[ch clients] @subscribers
                client (into [] clients)]
          (try (http/send! client ping)
               (catch Exception _ (unsub! ch client)))))
      (recur))))
```

---

### B3 — Task claim loser gets nil HTTP response `[AGENT BREAKAGE]`

When two agents race to claim a task, the SQLite `UPDATE ... WHERE status='open'` is atomic and the right agent wins. But the losing agent gets a `nil` HTTP response body — not a 409, not anything. It has no idea if its request errored, timed out, or succeeded. Most HTTP clients will throw or hang on a nil body.

```clojure
;; ❌ current — (when ...) returns nil when agent loses
(when (= (:claimed_by updated) agent)
  (ok {:id id :status "claimed" :claimed-by agent}))

;; ✅ fix
(if (= (:claimed_by updated) agent)
  (ok {:id id :status "claimed" :claimed-by agent})
  (conflict "lost claim race — already claimed"))
```

---

### B4 — Any agent can complete or abandon any other agent's task `[CORRECTNESS]`

`handle-task-done!` and `handle-task-abandon!` accept any `:from` value and apply unconditionally. Agent A can mark Agent B's in-progress task as done with a fake result, or abandon it, without any check. In a trusted mesh this might be acceptable social policy, but it's not intentional — there's no comment saying it's by design, and it will cause confusing bugs when two agents accidentally stomp each other.

The fix is a single guard clause:

```clojure
;; ✅ add to handle-task-done! and handle-task-abandon!
(when (and (:claimed_by task)
           (not= (:from body) (:claimed_by task)))
  (throw (ex-info "only the claiming agent can modify this task" {:status 403})))
```

`handle-task-update!` (progress notes) can reasonably remain open to any agent — posting an update doesn't change task ownership or status.

---

### B5 — Invalid task state transitions allowed `[CORRECTNESS]`

`handle-task-done!` will mark an open (never-claimed) task as done. `handle-task-abandon!` will reset a done task back to open. The task state machine has no guards.

```clojure
;; ✅ add to handle-task-done!
(when (not= (:status task) "claimed")
  (throw (ex-info (str "task must be claimed to complete, currently: " (:status task))
                  {:status 409})))

;; ✅ add to handle-task-abandon!
(when (not= (:status task) "claimed")
  (throw (ex-info (str "task must be claimed to abandon, currently: " (:status task))
                  {:status 409})))
```

---

### B6 — Path traversal in file fetch `[SECURITY]`

The `hash` from the URL is used directly to construct a file path. A request to `/files/sha256:../../etc/passwd` would traverse out of `blobs-dir` on systems where the blob dir is not the filesystem root.

The `sha256:` prefix helps but isn't validated. One line of validation fixes it:

```clojure
;; ✅ add to handle-file-fetch before constructing path
(when-not (re-matches #"sha256:[0-9a-f]{64}" hash)
  (throw (ex-info "invalid hash" {:status 400})))
```

A canonical path check is belt-and-suspenders on top:

```clojure
(let [f (.getCanonicalFile (io/file blobs-dir hash))]
  (when-not (str/starts-with? (str f) (.getCanonicalPath (io/file blobs-dir)))
    (throw (ex-info "invalid path" {:status 400}))))
```

---

### B7 — `io/make-parents` on db-path is broken `[STARTUP CRASH]`

```clojure
(io/make-parents (str db-path ".parent"))
```

Creates the parent of a file named `workshop.db.parent`, not of `workshop.db`. If you set `DB_PATH=/opt/workshop/data/workshop.db` and that directory doesn't exist yet, the server crashes on first run with a confusing SQLite error rather than helpfully creating the directory.

```clojure
;; ✅ fix
(io/make-parents (io/file db-path))
```

---

### B8 — Malformed JSON gives misleading error `[DX / AGENT BREAKAGE]`

`parse-body` catches JSON parse exceptions and returns `{}`. The handler then throws "missing :from" — which is technically true but completely wrong about the actual problem. When an agent has a serialisation bug, this sends them on a wild goose chase.

```clojure
;; ✅ fix
(defn parse-body [req]
  (let [raw (try (slurp (:body req)) (catch Exception _ ""))]
    (when-not (str/blank? raw)
      (try (json/parse-string raw true)
           (catch Exception _
             (throw (ex-info "invalid JSON body" {:status 400})))))))
```

Handlers already check for missing `:from` — this just surfaces the right error when JSON is the problem.

---

### B9 — No index on `messages.type` `[SLOW QUERIES]`

`handle-history` runs `type LIKE 'task%'` with no index. For an active mesh with thousands of messages this becomes a full table scan on every filtered history fetch.

```sql
-- ✅ add to init-db!
CREATE INDEX IF NOT EXISTS idx_messages_ch_type ON messages(ch, type);
```

A composite `(ch, type)` index covers both the channel filter and the type prefix filter in a single index scan.

---

### B10 — Presence table grows forever `[SLOW OVER TIME]`

Every agent that ever heartbeated leaves a row permanently. The query filters by `last_seen` so the live view is correct, but after months of operation with rotating agents, the table becomes slow to query and the `SELECT COUNT(*)` in `/status` becomes meaningfully slower.

```clojure
;; ✅ add to cleanup-old-messages! (rename to cleanup! or call from it)
(db-exec! "DELETE FROM presence WHERE last_seen < ?"
          (- (now) (* 7 24 60 60)))  ;; gone for 7 days = remove
```

---

### B11 — History on load only fetches `general` channel `[UX BUG]`

```javascript
// ❌ current — only fetches general
const r = await fetch(`${BASE}/ch/general/history?n=50`);
```

If the team only uses `tasks` and custom channels, the UI loads blank. The sidebar channel list stays empty until a live SSE message arrives. First-time users think the server is down.

The right fix is a server-side cross-channel history endpoint:

```clojure
;; ✅ server: add GET /history
;; In router:
(= [:get "/history"] [method path])
(handle-global-history req)

;; Handler:
(defn handle-global-history [req]
  (let [params (:query-params req)
        n (min (try (Integer/parseInt (get params "n" "100")) (catch Exception _ 100))
               history-limit)
        rows (db-query "SELECT * FROM messages ORDER BY id DESC LIMIT ?" n)
        msgs (->> rows (map row->msg) reverse)]
    {:status 200
     :headers {"Content-Type" "application/x-ndjson"
               "Access-Control-Allow-Origin" "*"}
     :body (str/join "\n" (map json/encode msgs))}))
```

```javascript
// ✅ client: fetch from global history endpoint
async function loadHistory() {
  try {
    const r = await fetch(`${BASE}/history?n=100`);
    const text = await r.text();
    text.trim().split('\n').filter(Boolean).forEach(line => {
      try {
        const m = JSON.parse(line);
        if (!seenIds.has(m.id)) {
          seenIds.add(m.id);
          allMessages.push(m);
          if (m.ch) channels.add(m.ch);
        }
      } catch(e) {}
    });
    updateSidebar();
    allMessages.slice(-100).forEach(m => addMsg(m));
  } catch(e) { console.warn('history load failed', e); }
}
```

---

### B12 — SSE reconnect shows duplicate messages, `allMessages` unbounded `[UX CORRUPTION]`

When SSE reconnects, `connectSSE()` is called fresh. The new `EventSource` replays messages from the beginning (since there's no `Last-Event-ID` support). Those messages get pushed into `allMessages` and rendered again. After a few reconnects the feed has 3–4 copies of every message.

`allMessages` also has no upper bound — after hours of uptime it can hold thousands of entries and the replay-on-channel-switch becomes noticeable.

```javascript
// ✅ fix — deduplicate globally by id
const seenIds = new Set();

function addMsg(m) {
  if (seenIds.has(m.id)) return;  // already rendered, skip
  seenIds.add(m.id);
  allMessages.push(m);
  if (allMessages.length > 2000) {
    const removed = allMessages.splice(0, 500);
    removed.forEach(m => seenIds.delete(m.id));
  }
  // ... rest of render logic
}
```

The longer fix is `Last-Event-ID` support (see B13), which makes reconnects actually resume instead of replay.

---

### B13 — No `Last-Event-ID` means reconnects lose messages in the gap `[MESSAGE LOSS]`

The browser's `EventSource` automatically sends a `Last-Event-ID` header on every reconnect if the server sets `id:` fields in SSE frames. This server never does. So when SSE drops and reconnects:

- Messages delivered during the gap are **silently lost**
- The UI has no way to know it missed anything
- Agents polling via SSE can miss task events

This is the difference between "SSE with gap recovery" (what you want) and "SSE that occasionally loses messages" (what you have).

**Server fix:**

```clojure
;; ✅ include id: field in every SSE frame
(defn broadcast! [ch msg-str msg-id]
  (let [payload (str "id: " msg-id "\ndata: " msg-str "\n\n")]
    ...))

;; call as:
(broadcast! ch encoded (:id msg))

;; ✅ on connect, replay missed messages from Last-Event-ID
(defn handle-stream [req ch]
  (let [last-id (get-in req [:headers "last-event-id"])
        missed  (when last-id
                  (db-query
                    "SELECT * FROM messages WHERE ch=? AND id>? ORDER BY id ASC"
                    ch last-id))]
    (http/as-channel req
      {:init    {:status 200 :headers {... "X-Accel-Buffering" "no"}}
       :on-open (fn [client]
                  ;; replay gap before subscribing (avoids race with new broadcasts)
                  (doseq [row missed]
                    (let [msg (row->msg row)
                          frame (str "id: " (:id msg) "\ndata: " (json/encode msg) "\n\n")]
                      (http/send! client frame)))
                  (sub! ch client))
       :on-close (fn [client _] (unsub! ch client))})))
```

Note: there's a small race between replaying the gap and subscribing — a message published between the gap query and `(sub! ch client)` could arrive twice. The deduplication in B12 handles this client-side.

---

### B14 — Shutdown hook calls stop fn with args it doesn't accept `[OPERATIONAL]`

```clojure
(@server :timeout 5000)
```

http-kit's stop function is `(fn [] ...)` — zero arity. Calling it with `:timeout 5000` either silently ignores the arg or throws, depending on the version. The server may not actually drain connections cleanly on SIGTERM.

```clojure
;; ✅ fix
(defn shutdown! []
  (println "\nshutting down...")
  (when @server (@server))
  (System/exit 0))
```

---

### B15 — `GET /tasks` with both `?status=` and `?for=` uses OR instead of AND `[QUERY BUG]`

```clojure
(and status for-who)
["SELECT * FROM tasks WHERE status=? AND (assigned_to=? OR claimed_by=?) ..." status for-who for-who]
```

When filtering by both status and agent, you get tasks where status matches AND (assigned to OR claimed by) that agent. This is probably right for "show me that agent's open tasks". But the `for-who` clause also matches `claimed_by`, which means a task assigned to agent A but claimed by agent B will show up in agent A's list as claimed, which can mislead agents polling for their work. Consider whether `assigned_to` and `claimed_by` should be separate filter params. Low priority but worth a comment in the code.

---

## Execution Plan

Three phases. Each phase produces a working, deployable server. No phase requires the next to be useful.

---

### Phase 1 — Ship It (1–2 hours)
*Fixes the things that are broken right now.*

**Goal:** SSE works, agents can claim tasks reliably, the UI is usable.

| # | Fix | Location |
|---|-----|----------|
| B1 | SSE `:init` headers | `handle-stream` (×2 — channel and :all) |
| B2 | Dead client leak + set snapshot | `broadcast!`, `sse-keepalive!` |
| B3 | Claim loser gets 409 not nil | `handle-task-claim!` |
| B7 | `io/make-parents` broken | `init-db!` |
| B8 | Malformed JSON error message | `parse-body` |
| B12 | Duplicate messages + unbounded allMessages | UI JS — `addMsg`, `seenIds` set |
| B11 | History loads only general | UI JS — `loadHistory` + server `GET /history` |

Phase 1 is purely fixes. Nothing changes about the API or data model.

---

### Phase 2 — Reliable (2–4 hours)
*Fixes the things that will cause pain after a few days of real use.*

**Goal:** Tasks don't get corrupted, messages don't get lost on reconnect, disk doesn't grow forever.

| # | Fix | Location |
|---|-----|----------|
| B4 | Agent ownership on done/abandon | `handle-task-done!`, `handle-task-abandon!` |
| B5 | Task state machine guards | same handlers |
| B6 | Path traversal in file fetch | `handle-file-fetch` |
| B9 | Index on `messages(ch, type)` | `init-db!` |
| B10 | Presence table cleanup | `cleanup-old-messages!` (rename to `cleanup!`) |
| B13 | Last-Event-ID gap recovery | `broadcast!`, `handle-stream` |
| B14 | Shutdown fn arity | `shutdown!` |

B13 is the most involved change — touch `broadcast!` signature, both `handle-stream` usages, and the UI deduplication from Phase 1.

---

### Phase 3 — Polish (1–2 hours)
*Small things that make daily use less annoying.*

| # | Fix | Notes |
|---|-----|-------|
| B15 | Document/fix task list filter semantics | Maybe split `?for=` into `?assigned=` and `?claimed=` |
| — | `GET /tasks/:id` — fetch single task | Agents need this to check task state without listing all |
| — | `GET /channels` — list known channels | Populated from distinct `ch` values in messages table |
| — | UI: show task panel inline, not just create form | Let humans see task status without knowing the API |
| — | Non-verbose mode still logs cleanup counts | One `println` in `cleanup!` for auditability |

The two new endpoints (`GET /tasks/:id` and `GET /channels`) are small and complete the API surface. Agents currently have to list all tasks and filter client-side to check a single task, which is unnecessary.

---

## What's Not Changing

These were flagged in the first audit pass but are **correct to leave as-is** for this project:

- **No auth** — the mesh handles it, this is by design
- **ULID uses ThreadLocalRandom** — fine for a small mesh, collision probability is negligible
- **Blobs are never deleted** — README says so, manual cleanup is fine
- **Single SQLite file** — right choice for this scale, WAL handles concurrency
- **Cleanup on every restart** — intentional, should be noted in a code comment
- **No delivery guarantees** — README is explicit; use NATS if you need them
- **`@(promise)` forever block** — correct, SIGTERM is handled
