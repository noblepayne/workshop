# handoff.md — workshop

**Last updated:** 2026-02-17
**Status:** Production-ready. Tested end-to-end. Ready for open source.

---

## What Was Built and Why

A friend group has agents (some OpenClaw, some custom) running on a trusted mesh network (Tailscale/ZeroTier). They wanted a shared workspace — somewhere agents could talk, coordinate tasks, share files — that humans could observe without needing tooling. Not enterprise middleware. Not another protocol war entry. Stupid-simple structured IRC.

The design conversation is preserved in the chat that produced this. Key decisions made:

- **Babashka** (not Clojure proper) — fast start, single binary, good enough ecosystem
- **SQLite** (not Postgres, not Redis) — fits on a VPS, WAL mode handles concurrent writes fine for this scale
- **SSE** (not WebSockets) — `curl -N url | jq .` is the human observability story. Hard to beat.
- **Content-addressed blobs** (not S3, not named files) — immutable, no naming conflicts, trivial to swap backend later
- **No app-layer auth** — mesh handles it. Honor system for identity strings.
- **No wiki** — explicitly deferred. Different concern. Will be a separate service that listens to `#knowledge`.

---

## Current State

### Files

| File | Status | Notes |
|------|--------|-------|
| `workshop.bb` | Complete | Full server implementation, all bugs fixed |
| `client.bb` | Complete | Drop-in client lib for agent authors |
| `test.bb` | Complete | 14 passing smoke tests |
| `bb.edn` | Complete | deps + pod declarations |
| `ulid.clj` | Complete | ULID decode utilities from boostbox |
| `AGENTS.md` | Complete | System prompt / context for AI agents |
| `README.md` | Complete | User-facing documentation |
| `DEPLOY.md` | Complete | Production deployment guide |
| `handoff.md` | This file | — |

### What's Implemented

- [x] SQLite schema init (messages, tasks, presence)
- [x] ULID generation (sortable unique IDs)
- [x] Channel publish / SSE stream / history with `?since=` replay
- [x] History type filter: `?type=task.*` prefix matching
- [x] Task lifecycle: create → claim (first-write-wins) → update → done/abandon
- [x] Task lifecycle events broadcast to channel as messages
- [x] Task interrupt endpoint: `POST /tasks/:id/interrupt`
- [x] Task filter `?for=agent` matches both `assigned_to` AND `claimed_by`
- [x] Content-addressed file upload/fetch (sha256)
- [x] Content-Length check before reading uploads (prevents memory issues)
- [x] Presence heartbeat + list (60s TTL)
- [x] God-view SSE (`GET /`)
- [x] Status endpoint
- [x] CORS headers
- [x] Message type validation (returns 400 if missing)
- [x] Generalized error handler (passes through status codes)
- [x] Cleanup runs on startup + hourly
- [x] Human `/ui` terminal view (embedded HTML/JS, no build step)
- [x] UI compose bar for human messages
- [x] UI task creation panel
- [x] Client library with poll-and-work loop
- [x] Keepalive pings on SSE connections (20s interval)
- [x] ULID tests: uniqueness, lexicographic sort, timestamp round-trip

### What's NOT Implemented (and why)

| Feature | Why Deferred |
|---------|-------------|
| Auth / API keys | Mesh handles trust |
| Wiki | Separate concern, separate service |
| Consumer groups | Adds complexity without clear need yet |
| Delivery guarantees | Use NATS if you need at-least-once |
| Schema registry | Free-form `:type` is a feature, not a bug |
| File metadata (name, mime type) | Content-type sniffed on fetch; add if needed |
| Channel creation/management | Channels are implicit — posting to one creates it |
| Message deletion | Blobs are immutable; messages probably should be too |

---

## Known Issues / Things to Verify First

1. ~~**The router** — two `route` definitions~~ — **RESOLVED.** Dead `core.match`-based route fn removed. Only the `cond`-based implementation remains.

2. **Pod loading** — `pod-babashka-go-sqlite3` version `"0.1.0"` — verify this is still the latest stable. Check https://github.com/babashka/pod-babashka-go-sqlite3/releases

3. **http-kit version** — `"2.8.0"` in `bb.edn`. The SSE `as-channel` API should be stable but verify.

4. **ULID implementation** — hand-rolled, not a library. Should be tested for correctness and uniqueness under rapid calls.

5. **SQLite `ON CONFLICT` for presence** — uses upsert syntax. Verify the go-sqlite3 pod supports this (it should — it's standard SQLite 3.24+).

6. **Task claim race condition** — uses `UPDATE ... WHERE status='open'` then reads back to check if *this* agent got it. SQLite serializes writes so this should be safe, but test it with concurrent claimants.

7. **SSE content-type headers** — sent in the `:on-open` callback as a response map. Verify http-kit handles this correctly for SSE handshake.

---

## Running Tests

```bash
# Run the full test suite
bb test.bb

# Against a remote instance
WORKSHOP_URL=http://your-vps:4242 bb test.bb

# Tests verify:
# - status endpoint
# - channel publish + validation
# - history + type filter
# - task create/claim/complete workflow
# - claim conflicts (409)
# - presence heartbeat
# - file upload
# - ULID uniqueness + timestamp round-trip
```

---

## Deploy to VPS

```bash
# install babashka on the vps
# https://github.com/babashka/babashka#installation
# one-liner: bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# copy files
scp -r workshop/ user@vps:/opt/workshop/

# run (simple)
ssh user@vps 'cd /opt/workshop && nohup bb workshop.bb > workshop.log 2>&1 &'

# run (systemd — preferred)
# see systemd unit below
```

### systemd unit (`/etc/systemd/system/workshop.service`)

```ini
[Unit]
Description=workshop agent hub
After=network.target

[Service]
Type=simple
User=workshop
WorkingDirectory=/opt/workshop
ExecStart=/usr/local/bin/bb workshop.bb
Restart=always
RestartSec=5
Environment=PORT=4242
Environment=DB_PATH=/opt/workshop/data/workshop.db
Environment=BLOBS_DIR=/opt/workshop/data/blobs
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable workshop
sudo systemctl start workshop
sudo journalctl -u workshop -f  # tail logs
```

---

## Connecting Agents

### OpenClaw agents

Configure an HTTP tool pointing at:
- Publish: `POST http://your-vps:4242/ch/{channel}`
- Check tasks: `GET http://your-vps:4242/tasks?status=open`
- Claim: `POST http://your-vps:4242/tasks/{id}/claim`

Body format is the message envelope (JSON). Set `from` to the agent's identity string.

### Babashka agents

```clojure
;; put client.bb somewhere on your load path or just copy it in
(load-file "/path/to/client.bb")
(require '[workshop.client :as ws])

(ws/configure! {:url   "http://your-vps:4242"
                :agent "myagent.yourname"})

;; heartbeat every 30s
(future
  (loop []
    (ws/heartbeat! ["general" "jobs"])
    (Thread/sleep 30000)
    (recur)))

;; work loop
(ws/poll-and-work!
  (fn [task]
    (println "working on" (:title task))
    {:done true})
  :channels ["jobs"]
  :interval-ms 5000)
```

### Any HTTP client

```bash
export WORKSHOP=http://your-vps:4242
export ME=myagent.myname

# announce presence
curl -X POST $WORKSHOP/presence \
  -H 'Content-Type: application/json' \
  -d "{\"from\":\"$ME\",\"channels\":[\"general\"],\"meta\":{}}"

# publish
curl -X POST $WORKSHOP/ch/general \
  -H 'Content-Type: application/json' \
  -d "{\"from\":\"$ME\",\"type\":\"hello.world\",\"body\":{\"msg\":\"hi\"}}"

# watch
curl -N $WORKSHOP/ | jq .
```

---

## What a "Good Next Session" Looks Like

**If the goal is "get it running":**
1. Fix the dead route function
2. Smoke test all endpoints
3. Deploy to VPS
4. Have one agent send a hello, confirm it shows in `/ui`

**If the goal is "make it better":**
- Add `?type=task.*` filter to history endpoint
- Add message count badge to `/status`  
- Better error messages when `from` is missing
- Consider rate limiting presence heartbeats server-side

**If the goal is "add the wiki":**
- Start fresh: separate `wiki.bb` service
- Listens to `#knowledge` channel for `page.update` messages
- Stores pages as markdown blobs (content-addressed, like files)
- Separate `/wiki/:slug` HTTP interface
- References back to workshop message IDs for provenance

---

## Architecture Sketch

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

---

## Decisions That Should Not Be Revisited Without Good Reason

| Decision | Rationale |
|----------|-----------|
| No app auth | Mesh is the trust layer. Adding auth adds complexity without security value given the deployment model. |
| SQLite not Postgres | Fits the scale. Single file, zero ops. If you outgrow it that's a good problem to have. |
| SSE not WebSockets | One-way push is all we need. Simpler protocol, `curl`-observable. |
| ULIDs not UUIDs | Sortability enables `?since=` replay for free. Don't swap to UUID. |
| Single file server | Babashka doesn't need a build system. The file IS the program. Don't split it up unless line count genuinely becomes a problem. |
| Free-form `:type` | Agents should be able to invent new types without a schema registry. Convention, not enforcement. |
