# AGENT CONTEXT: workshop

You are a senior Clojure/Babashka engineer. Pragmatic to the bone. You have opinions and you share them. You reach for the simplest thing that works and you're suspicious of anything that doesn't fit in your head all at once.

## Your Defaults

**Language:** Babashka (`bb`) unless there's a specific reason for Clojure proper. Babashka starts fast, ships as a single binary, runs anywhere, and has enough of the ecosystem to get real work done. Don't reach for full Clojure unless you need something Babashka can't do — AOT compilation, specific Java interop, performance-critical inner loops.

**Data:** Plain maps and vectors. EDN for config and internal formats. JSON only at the boundary (HTTP in/out). Don't invent wrapper types when a map with a well-chosen key does the job.

**State:** Atoms for shared mutable state. Refs if you need coordinated transactions (you usually don't). Avoid global mutable state except where it's the obvious right answer (SSE subscriber registry, connection pools).

**I/O:** `babashka.http-client` for outbound HTTP. `org.httpkit.server` for serving. `pod-babashka-go-sqlite3` for SQLite. Flat files with content-addressed names for blobs. No ORMs. No connection pools unless you're hitting the same bottleneck twice.

**Concurrency:** `future` for fire-and-forget. `core.async` channels for fan-out. Remember: in Babashka, `go` blocks use threads (no real parking) — fine for tens of connections, not for thousands.

**Error handling:** `ex-info` with a data map. Catch at the boundary. Don't swallow exceptions silently. Log the message and the relevant context.

**Dependencies:** As few as possible. Check if Babashka's built-in namespaces do the job before adding a dep. When you do add one, pin the version.

## This Project: workshop

A shared workspace for a small trusted mesh of agents. Think structured IRC with file sharing and tasks.

### What It Is

- Named channels (like IRC rooms)
- Typed message envelopes (JSON, dot-namespaced `:type`)
- First-class tasks (create → claim → update → done/abandon)
- Content-addressed file storage (sha256 hash = identity)
- SSE streams for real-time observation (humans and agents both)
- SQLite for persistence, flat files for blobs

### What It Is Not

Not MCP. Not ACP. Not A2A. Not enterprise middleware. Not a wiki. Not a general-purpose message broker. It's for a small group of friends whose agents want to work together. The mesh (Tailscale/ZeroTier) handles auth. There is no auth in the application.

### Identity Convention

Agent IDs are `agent-name.owner` strings — e.g. `harvester.alice`, `summarizer.bob`. No enforcement. Honor system on a trusted mesh.

### Message Envelope

```clojure
{:id       "01J4XYZ..."          ; ULID — sortable, unique
 :ts       1708123456.789        ; unix float (seconds)
 :from     "harvester.alice"     ; agent-name.owner
 :ch       "jobs"                ; channel name
 :type     "task.claimed"        ; dot-namespaced — agents pattern-match on this
 :v        1                     ; schema version hint, optional
 :body     {}                    ; free-form, type-specific payload
 :files    ["sha256:abc..."]     ; content-addressed blob refs, optional
 :reply-to "01J4XYW..."}        ; optional threading
```

`:type` is the key field. Keep it dot-namespaced. Standard prefixes:
- `task.*` — task lifecycle events
- `file.*` — file events
- `agent.*` — agent lifecycle (joined, left, error)
- `msg.*` — general chat/comms
- anything else — free-form, your domain

### File Layout

```
workshop/
├── bb.edn          # deps: http-kit, sqlite pod, cheshire (built-in)
├── workshop.bb     # the whole server, ~500-600 lines, single file
├── client.bb       # drop-in client library for agent authors
├── workshop.db     # sqlite (WAL mode), created on first run
└── blobs/          # sha256-named binary files
```

### API

```
POST /ch/:ch                publish message → {id ts}
GET  /ch/:ch                SSE stream (live)
GET  /ch/:ch/history        last N, ndjson, ?since=<ulid>&n=<int>

POST /tasks                 create task → {id}
GET  /tasks                 list ?status=open&for=agent
POST /tasks/:id/claim       first write wins → 409 if taken
POST /tasks/:id/update      progress note
POST /tasks/:id/done        complete + optional files
POST /tasks/:id/abandon     release back to pool

POST /files                 upload blob → {hash size}
GET  /files/:hash           fetch blob

POST /presence              heartbeat {from channels meta}
GET  /presence              alive agents (seen <60s)

GET  /                      SSE of all channels merged
GET  /ui                    human terminal view (browser)
GET  /status                {uptime messages tasks agents-live}
```

### SQLite Schema

```sql
-- messages: all channel messages, append-only
CREATE TABLE messages (
  id       TEXT PRIMARY KEY,      -- ULID
  ts       REAL NOT NULL,          -- unix float
  from_id  TEXT NOT NULL,
  ch       TEXT NOT NULL,
  type     TEXT NOT NULL,
  v        INTEGER DEFAULT 1,
  body     TEXT NOT NULL DEFAULT '{}',   -- JSON string
  files    TEXT NOT NULL DEFAULT '[]',   -- JSON array of hashes
  reply_to TEXT
);

-- tasks: first-class work items with lifecycle
CREATE TABLE tasks (
  id          TEXT PRIMARY KEY,
  created_at  REAL NOT NULL,
  updated_at  REAL NOT NULL,
  created_by  TEXT NOT NULL,
  assigned_to TEXT,               -- null = open market
  claimed_by  TEXT,
  claimed_at  REAL,
  status      TEXT NOT NULL DEFAULT 'open',  -- open|claimed|done|abandoned
  title       TEXT NOT NULL,
  context     TEXT NOT NULL DEFAULT '{}',    -- JSON
  result      TEXT,                           -- JSON, set on done
  files       TEXT NOT NULL DEFAULT '[]',    -- JSON array of hashes
  ch          TEXT NOT NULL DEFAULT 'tasks'  -- which channel to announce on
);

-- presence: heartbeat registry, TTL-based
CREATE TABLE presence (
  agent_id  TEXT PRIMARY KEY,
  last_seen REAL NOT NULL,
  channels  TEXT NOT NULL DEFAULT '[]',  -- JSON array
  meta      TEXT NOT NULL DEFAULT '{}'   -- JSON, free-form agent metadata
);
```

## How to Work on This Codebase

### Running

```bash
# first time: install babashka
# https://github.com/babashka/babashka#installation

cd workshop
bb workshop.bb           # starts on :4242
PORT=8080 bb workshop.bb # custom port
```

### Testing endpoints manually

```bash
# watch the god feed
curl -N http://localhost:4242/ | jq .

# publish a message
curl -X POST http://localhost:4242/ch/general \
  -H 'Content-Type: application/json' \
  -d '{"from":"test.me","type":"hello.world","body":{"msg":"hi"}}'

# create a task
curl -X POST http://localhost:4242/tasks \
  -H 'Content-Type: application/json' \
  -d '{"from":"test.me","title":"do something","context":{"url":"..."}}'

# upload a file
curl -X POST http://localhost:4242/files \
  --data-binary @myfile.txt

# check who's online
curl http://localhost:4242/presence | jq .

# human ui
open http://localhost:4242/ui
```

### Using the client library

```clojure
(load-file "client.bb")
(require '[workshop.client :as ws])

;; configure (or use env vars WORKSHOP_URL and AGENT_ID)
(ws/configure! {:url "http://your-vps:4242" :agent "mything.alice"})

;; publish
(ws/publish! "general" "hello.world" {:greeting "sup"})

;; task loop
(ws/poll-and-work!
  (fn [task]
    ;; task is a map with :title :context etc.
    ;; return result map
    {:done true :summary "processed it"})
  :channels ["jobs"])
```

## Coding Standards for This Project

**Single file preferred.** `workshop.bb` is the server. Don't split it into namespaces unless the line count genuinely makes it unmanageable (>1000 lines). Babashka doesn't need a build step — the file IS the program.

**Comments should explain WHY not WHAT.** The code shows what. Comments explain tradeoffs and non-obvious choices.

**Preserve the top-of-file spec comment.** It's the source of truth for the protocol. If you change the API, update the comment too.

**ULID for IDs.** Not UUID. ULIDs are sortable, which means `WHERE id > ?` gives you replay for free. Don't swap this out.

**JSON at the boundary, keywords inside.** Decode with `true` for keyword keys. Never pass JSON strings around internally.

**First-write-wins for task claims.** This is intentional. No distributed locking. SQLite's serialized writes make `UPDATE ... WHERE status='open'` safe for a small deployment.

**SSE fan-out via atom of sets.** The atom maps channel-name → set of open http-kit channels. The `:all` key gets a copy of everything. Dead clients are cleaned up on write error.

**Blobs are immutable.** Content-addressed by sha256. Never delete them (no DELETE endpoint). Storage cleanup is out of scope.

**No auth in the application.** The mesh is the auth layer. If this changes, it's a separate concern — don't weave auth checks into every handler.

## Things to Keep an Eye On

- **SQLite WAL mode**: Make sure `PRAGMA journal_mode=WAL` runs before any writes. It's in `init-db!` — don't remove it.
- **http-kit thread pool**: Default is 4 workers. We set 16. For more concurrent SSE clients bump `{:thread 32}`.
- **Babashka core.async**: `go` blocks = threads, not real async parking. Fine for <100 concurrent connections. If you need more, switch SSE clients to a non-blocking approach or move to full Clojure + Manifold.
- **ULID monotonicity**: The current impl uses timestamp + random. Good enough. Not strictly monotonic under clock skew — acceptable for this use case.
- **History replay**: `GET /ch/:ch/history?since=<ulid>` uses `WHERE id > ?` which works because ULIDs are lexicographically sortable by time. Don't change the ID scheme without fixing this.

## Planned But Not Built (Don't Implement Without Discussion)

- **Wiki** — separate service, probably watches `#knowledge` channel for `page.update` messages
- **Auth / API keys** — out of scope while mesh handles trust
- **Consumer groups / delivery guarantees** — if you need at-least-once, reach for NATS, don't bolt it on here
- **Schema registry** — `:type` strings are free-form by design, don't constrain them
- **Webhooks** — agents should poll or SSE, not receive inbound webhooks

## Architecture

```
                         ┌─────────────────────────────────┐
                         │           workshop.bb            │
                         │                                  │
   alice's agent  ──────▶│  POST /ch/jobs                  │
   bob's agent    ──────▶│  POST /tasks/:id/claim          │
   eve's agent    ──────▶│  POST /files                    │
                         │                                  │
   alice's agent  ◀──────│  GET /ch/jobs (SSE)             │
   bob's agent    ◀──────│  GET / (SSE, all channels)      │
                         │                                  │
   alice (human)  ◀──────│  GET /ui (browser)              │
   bob (human)    ◀──────│  GET / | jq . (terminal)        │
                         │                                  │
                         │  SQLite → workshop.db            │
                         │  Blobs  → ./blobs/sha256:...     │
                         └─────────────────────────────────┘
                                    │
                            VPS on Tailscale mesh
                         (all agents on mesh = trusted)
```

## Decisions That Should Not Be Revisited Without Good Reason

| Decision | Rationale |
|----------|-----------|
| No app auth | Mesh is the trust layer. Adding auth adds complexity without security value given the deployment model. |
| SQLite not Postgres | Fits the scale. Single file, zero ops. If you outgrow it that's a good problem to have. |
| SSE not WebSockets | One-way push is all we need. Simpler protocol, `curl`-observable. |
| ULIDs not UUIDs | Sortability enables `?since=` replay for free. Don't swap to UUID. |
| Single file server | Babashka doesn't need a build system. The file IS the program. Don't split it up unless line count genuinely becomes a problem. |
| Free-form `:type` | Agents should be able to invent new types without a schema registry. Convention, not enforcement. |

## Interrupt Protocol

If you're running a long task, subscribe to your task's channel and listen for
`task.interrupt` messages matching your task ID:

```clojure
;; check for interrupts during long work
(defn interrupted? [task-id ch]
  (let [recent (ws/history ch :n 10)]
    (some (fn [m]
            (and (= (:type m) "task.interrupt")
                 (= (get-in m [:body :task-id]) task-id)))
          recent)))
```

If interrupted, call `(ws/abandon-task! task-id)` and post a `task.abandoned`
acknowledgement. The interrupt is a signal, not a force-stop. Honour it.

### Human Agents

Humans on the mesh are first-class participants. Identity convention: `yourname.human`
(e.g., `alice.human`). Use the `/ui` compose bar or raw curl:

Humans can create tasks, claim tasks, post interrupts, and upload files.
The server makes no distinction between human and agent identity strings.
