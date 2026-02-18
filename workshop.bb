#!/usr/bin/env bb
;;
;; workshop.bb — shared workspace for a small trusted mesh of agents
;;
;; WHAT THIS IS:
;;   Stupid-simple structured IRC with file sharing and tasks.
;;   Agent-first. Human-observable. Runs on a VPS. No auth ceremony —
;;   the mesh (Tailscale/ZeroTier) handles trust.
;;
;; WHAT THIS IS NOT:
;;   Not MCP. Not ACP. Not A2A. Not Kafka. Not a wiki.
;;   Those solve different problems. This solves: "my friends and I have
;;   agents and want them to work together."
;;
;; RUN:   bb workshop.bb
;; PORT:  4242 (set PORT env to override)
;; DATA:  ./workshop.db  (sqlite)
;; FILES: ./blobs/       (content-addressed)
;;
;; IDENTITY CONVENTION: "agent-name.owner" e.g. "harvester.alice"
;;   No enforcement. Honor system. Mesh handles real trust.
;;
;; MESSAGE ENVELOPE (all messages are this shape):
;;   {:id       "01J4..."          ;; ULID, sortable+unique
;;    :ts       1708123456.789     ;; unix float
;;    :from     "harvester.alice"  ;; agent-name.owner
;;    :ch       "general"          ;; channel name
;;    :type     "task.claimed"     ;; dot-namespaced, agents pattern-match
;;    :v        1                  ;; schema version hint, optional
;;    :body     {}                 ;; free-form, type-specific payload
;;    :files    ["sha256:abc..."]  ;; optional blob refs
;;    :reply-to "01J3..."}         ;; optional threading
;;
;; API SURFACE:
;;   POST /ch/:ch              publish message
;;   GET  /ch/:ch              SSE stream (live)
;;   GET  /ch/:ch/history      last N messages, ndjson, ?since=<id>&n=<int>&type=<prefix>
;;   POST /tasks               create task
;;   GET  /tasks               list tasks ?status=open&for=agent
;;   POST /tasks/:id/claim     claim (first write wins, 409 if taken)
;;   POST /tasks/:id/update    progress note
;;   POST /tasks/:id/done      complete + optional files
;;   POST /tasks/:id/abandon   release back to pool
;;   POST /tasks/:id/interrupt  signal interrupt (agent decides whether to stop)
;;   POST /files               upload blob → {hash size}
;;   GET  /files/:hash         fetch blob
;;   POST /presence            heartbeat {from channels meta}
;;   GET  /presence            who's alive (seen in last 60s)
;;   GET  /                    SSE of everything (all channels merged)
;;   GET  /ui                  human terminal web view
;;   GET  /status              health check

(require '[org.httpkit.server :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.pods :as pods])

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

;; ─────────────────────────────────────────────
;; CONFIG
;; ─────────────────────────────────────────────

(def port (Integer/parseInt (or (System/getenv "PORT") "4242")))
(def db-path (or (System/getenv "DB_PATH") "workshop.db"))
(def blobs-dir (or (System/getenv "BLOBS_DIR") "blobs"))
(def history-limit 200)
(def presence-ttl-ms 60000)
(def max-file-size (* 100 1024 1024)) ;; 100MB default
(def verbose? (= "true" (System/getenv "WORKSHOP_VERBOSE")))
(def retention-days (try (Integer/parseInt (or (System/getenv "WORKSHOP_RETENTION_DAYS") "30"))
                         (catch Exception _ 30)))

;; ─────────────────────────────────────────────
;; ULID  (sortable unique id, url-safe)
;; ─────────────────────────────────────────────

(def ^:private ulid-chars "0123456789ABCDEFGHJKMNPQRSTVWXYZ")
(def ^:private ulid-len 26)

(defn new-ulid []
  (let [ts (System/currentTimeMillis)
        sb (StringBuilder. ulid-len)
        rng (java.util.concurrent.ThreadLocalRandom/current)]
    ;; 10 timestamp chars
    (loop [t ts i 0]
      (when (< i 10)
        (.insert sb 0 (.charAt ulid-chars (int (mod t 32))))
        (recur (quot t 32) (inc i))))
    ;; 16 random chars
    (dotimes [_ 16]
      (.append sb (.charAt ulid-chars (.nextInt rng 32))))
    (str sb)))

;; ─────────────────────────────────────────────
;; DATABASE
;; ─────────────────────────────────────────────

(defn db-exec! [sql & params]
  (sqlite/execute! db-path (into [sql] params)))

(defn db-query [sql & params]
  (sqlite/query db-path (into [sql] params)))

(defn init-db! []
  (io/make-parents (str db-path ".parent"))
  (db-exec! "PRAGMA journal_mode=WAL")
  (db-exec! "PRAGMA synchronous=NORMAL")
  (db-exec!
   "CREATE TABLE IF NOT EXISTS messages (
      id       TEXT PRIMARY KEY,
      ts       REAL NOT NULL,
      from_id  TEXT NOT NULL,
      ch       TEXT NOT NULL,
      type     TEXT NOT NULL,
      v        INTEGER DEFAULT 1,
      body     TEXT NOT NULL DEFAULT '{}',
      files    TEXT NOT NULL DEFAULT '[]',
      reply_to TEXT
    )")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_messages_ch ON messages(ch)")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts)")
  (db-exec!
   "CREATE TABLE IF NOT EXISTS tasks (
      id         TEXT PRIMARY KEY,
      created_at REAL NOT NULL,
      updated_at REAL NOT NULL,
      created_by TEXT NOT NULL,
      assigned_to TEXT,
      claimed_by TEXT,
      claimed_at REAL,
      status     TEXT NOT NULL DEFAULT 'open',
      title      TEXT NOT NULL,
      context    TEXT NOT NULL DEFAULT '{}',
      result     TEXT,
      files      TEXT NOT NULL DEFAULT '[]',
      ch         TEXT NOT NULL DEFAULT 'tasks'
    )")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
  (db-exec!
   "CREATE TABLE IF NOT EXISTS presence (
      agent_id   TEXT PRIMARY KEY,
      last_seen  REAL NOT NULL,
      channels   TEXT NOT NULL DEFAULT '[]',
      meta       TEXT NOT NULL DEFAULT '{}'
    )")
  (println "db ready:" db-path))

;; ─────────────────────────────────────────────
;; SSE FAN-OUT
;; Atom: {:all #{channels...} "ch-name" #{channels...}}
;; ─────────────────────────────────────────────

(def subscribers (atom {}))

(defn sub! [ch client]
  (swap! subscribers update ch (fnil conj #{}) client))

(defn unsub! [ch client]
  (swap! subscribers update ch disj client))

(defn broadcast! [ch msg-str]
  (let [payload (str "data: " msg-str "\n\n")]
    (doseq [client (get @subscribers ch #{})]
      (try (http/send! client payload)
           (catch Exception e
             (when verbose? (println "[broadcast] send error:" (.getMessage e))))))
    ;; also send to god-view (:all)
    (when (not= ch :all)
      (doseq [client (get @subscribers :all #{})]
        (try (http/send! client payload)
             (catch Exception e
               (when verbose? (println "[broadcast] send error (all):" (.getMessage e)))))))))

(defn sse-keepalive! []
  (future
    (loop []
      (Thread/sleep 20000)
      (let [ping ": keepalive\n\n"]
        (doseq [[_ch clients] @subscribers
                client clients]
          (try (http/send! client ping)
               (catch Exception _))))
      (recur))))

;; ─────────────────────────────────────────────
;; HELPERS
;; ─────────────────────────────────────────────

(defn now [] (/ (System/currentTimeMillis) 1000.0))

(defn parse-body [req]
  (try (json/parse-string (slurp (:body req)) true)
       (catch Exception _ {})))

(defn json-resp [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/encode body)})

(defn ok [body] (json-resp 200 body))
(defn created [body] (json-resp 201 body))
(defn bad [msg] (json-resp 400 {:error msg}))
(defn not-found [msg] (json-resp 404 {:error msg}))
(defn conflict [msg] (json-resp 409 {:error msg}))

(defn row->msg [row]
  (-> row
      (update :body #(json/parse-string % true))
      (update :files #(json/parse-string % true))
      (dissoc :reply_to)
      (assoc :reply-to (:reply_to row))))

(defn msg->row [msg]
  {:id (:id msg)
   :ts (:ts msg)
   :from_id (:from msg)
   :ch (:ch msg)
   :type (:type msg)
   :v (or (:v msg) 1)
   :body (json/encode (or (:body msg) {}))
   :files (json/encode (or (:files msg) []))
   :reply_to (:reply-to msg)})

;; ─────────────────────────────────────────────
;; CHANNEL HANDLERS
;; ─────────────────────────────────────────────

(defn handle-publish! [req ch]
  (let [body (parse-body req)
        msg (merge body
                   {:id (new-ulid)
                    :ts (now)
                    :ch ch})
        row (msg->row msg)
        encoded (json/encode msg)]
    (when (str/blank? (:from msg))
      (throw (ex-info "missing :from" {})))
    (when (str/blank? (:type msg))
      (throw (ex-info "missing :type" {:status 400})))
    (db-exec!
     "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files,reply_to)
      VALUES (?,?,?,?,?,?,?,?,?)"
     (:id row) (:ts row) (:from_id row) (:ch row)
     (:type row) (:v row) (:body row) (:files row) (:reply_to row))
    (broadcast! ch encoded)
    (created {:id (:id msg) :ts (:ts msg)})))

(defn handle-stream [req ch]
  (http/as-channel req
                   {:on-open (fn [client]
                               (http/send! client
                                           {:status 200
                                            :headers {"Content-Type" "text/event-stream"
                                                      "Cache-Control" "no-cache"
                                                      "Access-Control-Allow-Origin" "*"}})
                               (sub! ch client))
                    :on-close (fn [client _] (unsub! ch client))}))

(defn handle-history [req ch]
  (let [params (:query-params req)
        since (get params "since")
        type-filter (get params "type")
        n-requested (try (Integer/parseInt (get params "n" (str history-limit)))
                         (catch Exception _ history-limit))
        n-clamped (min n-requested history-limit)
        rows (cond
               (and since type-filter)
               (db-query
                "SELECT * FROM messages WHERE ch=? AND id>? AND type LIKE ? ORDER BY id DESC LIMIT ?"
                ch since (str type-filter "%") n-clamped)
               since
               (db-query
                "SELECT * FROM messages WHERE ch=? AND id>? ORDER BY id DESC LIMIT ?"
                ch since n-clamped)
               type-filter
               (db-query
                "SELECT * FROM messages WHERE ch=? AND type LIKE ? ORDER BY id DESC LIMIT ?"
                ch (str type-filter "%") n-clamped)
               :else
               (db-query
                "SELECT * FROM messages WHERE ch=? ORDER BY id DESC LIMIT ?"
                ch n-clamped))
        msgs (->> rows (map row->msg) reverse)]
    {:status 200
     :headers {"Content-Type" "application/x-ndjson"
               "Access-Control-Allow-Origin" "*"}
     :body (str/join "\n" (map json/encode msgs))}))

;; ─────────────────────────────────────────────
;; TASK HANDLERS
;; ─────────────────────────────────────────────

(defn handle-task-create! [req]
  (let [body (parse-body req)
        id (new-ulid)
        ts (now)
        ch (or (:ch body) "tasks")
        from (or (:from body) (:created_by body))]
    (when (str/blank? from)
      (throw (ex-info "missing :from or :created_by" {:status 400})))
    (when (str/blank? (:title body))
      (throw (ex-info "missing :title" {:status 400})))
    (db-exec!
     "INSERT INTO tasks (id,created_at,updated_at,created_by,assigned_to,status,title,context,files,ch)
      VALUES (?,?,?,?,?,?,?,?,?,?)"
     id ts ts from
     (:for body)
     "open"
     (:title body)
     (json/encode (or (:context body) {}))
     (json/encode [])
     ch)
    ;; announce to channel
    (let [msg {:id (new-ulid) :ts ts :from from
               :ch ch :type "task.created"
               :body {:task-id id :title (:title body) :for (:for body)}}]
      (db-exec!
       "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
        VALUES (?,?,?,?,?,1,?,?)"
       (:id msg) ts (:from msg) ch "task.created"
       (json/encode (:body msg)) "[]")
      (broadcast! ch (json/encode msg)))
    (created {:id id})))

(defn get-task [id]
  (first (db-query "SELECT * FROM tasks WHERE id=?" id)))

(defn handle-task-list [req]
  (let [params (:query-params req)
        status (get params "status")
        for-who (get params "for")
        sql (cond
              (and status for-who)
              ["SELECT * FROM tasks WHERE status=? AND (assigned_to=? OR claimed_by=?) ORDER BY created_at DESC" status for-who for-who]
              status
              ["SELECT * FROM tasks WHERE status=? ORDER BY created_at DESC" status]
              for-who
              ["SELECT * FROM tasks WHERE assigned_to=? OR claimed_by=? ORDER BY created_at DESC" for-who for-who]
              :else
              ["SELECT * FROM tasks ORDER BY created_at DESC LIMIT 100"])
        rows (apply db-query sql)
        tasks (map (fn [r]
                     (-> r
                         (update :context #(json/parse-string % true))
                         (update :files #(json/parse-string % true)))) rows)]
    (ok tasks)))

(defn handle-task-claim! [req id]
  (let [body (parse-body req)
        agent (:from body)
        task (get-task id)]
    (cond
      (nil? task) (not-found "task not found")
      (not= (:status task) "open") (conflict "task already claimed or closed")
      :else
      (do
        (db-exec!
         "UPDATE tasks SET status='claimed', claimed_by=?, claimed_at=?, updated_at=?
          WHERE id=? AND status='open'"
         agent (now) (now) id)
        (let [updated (get-task id)]
          (when (= (:claimed_by updated) agent)
            (let [ts (now)
                  ch (:ch task)
                  msg {:id (new-ulid) :ts ts :from agent
                       :ch ch :type "task.claimed"
                       :body {:task-id id :title (:title task)}}]
              (db-exec!
               "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
                VALUES (?,?,?,?,?,1,?,?)"
               (:id msg) ts agent ch "task.claimed"
               (json/encode (:body msg)) "[]")
              (broadcast! ch (json/encode msg)))
            (ok {:id id :status "claimed" :claimed-by agent})))))))

(defn handle-task-update! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (db-exec! "UPDATE tasks SET updated_at=? WHERE id=?" (now) id)
    (let [ts (now)
          ch (:ch task)
          msg {:id (new-ulid) :ts ts :from (:from body)
               :ch ch :type "task.updated"
               :body (merge {:task-id id} (dissoc body :from))}]
      (db-exec!
       "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
        VALUES (?,?,?,?,?,1,?,?)"
       (:id msg) ts (:from body) ch "task.updated"
       (json/encode (:body msg)) "[]")
      (broadcast! ch (json/encode msg)))
    (ok {:id id})))

(defn handle-task-done! [req id]
  (let [body (parse-body req)
        task (get-task id)
        files (or (:files body) [])]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (db-exec!
     "UPDATE tasks SET status='done', updated_at=?, result=?, files=? WHERE id=?"
     (now) (json/encode (or (:result body) {})) (json/encode files) id)
    (let [ts (now)
          ch (:ch task)
          msg {:id (new-ulid) :ts ts :from (:from body)
               :ch ch :type "task.done"
               :body {:task-id id :title (:title task)}
               :files files}]
      (db-exec!
       "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
        VALUES (?,?,?,?,?,1,?,?)"
       (:id msg) ts (:from body) ch "task.done"
       (json/encode (:body msg)) (json/encode files))
      (broadcast! ch (json/encode msg)))
    (ok {:id id :status "done"})))

(defn handle-task-abandon! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (db-exec!
     "UPDATE tasks SET status='open', claimed_by=NULL, claimed_at=NULL, updated_at=? WHERE id=?"
     (now) id)
    (let [ts (now)
          ch (:ch task)
          msg {:id (new-ulid) :ts ts :from (:from body)
               :ch ch :type "task.abandoned"
               :body {:task-id id :title (:title task)}}]
      (db-exec!
       "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
        VALUES (?,?,?,?,?,1,?,?)"
       (:id msg) ts (:from body) ch "task.abandoned"
       (json/encode (:body msg)) "[]")
      (broadcast! ch (json/encode msg)))
    (ok {:id id :status "open"})))

(defn handle-task-interrupt! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (let [ts (now)
          ch (:ch task)
          msg {:id (new-ulid) :ts ts :from (:from body)
               :ch ch :type "task.interrupt"
               :body {:task-id id :title (:title task) :reason (:reason body)}}]
      (db-exec!
       "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
        VALUES (?,?,?,?,?,1,?,?)"
       (:id msg) ts (:from body) ch "task.interrupt"
       (json/encode (:body msg)) "[]")
      (broadcast! ch (json/encode msg)))
    (ok {:id id :signalled true})))

;; ─────────────────────────────────────────────
;; FILE HANDLERS
;; ─────────────────────────────────────────────

(defn sha256-hex [bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        h (.digest md bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) h))))

(defn handle-file-upload! [req]
  (let [content-length (some-> req :headers (get "content-length") Integer/parseInt)]
    (when (and content-length (> content-length max-file-size))
      (throw (ex-info (str "file too large, max " max-file-size " bytes") {:status 413})))
    (let [bytes (-> req :body .readAllBytes)]
      (when (> (count bytes) max-file-size)
        (throw (ex-info (str "file too large, max " max-file-size " bytes") {:status 413})))
      (let [hash (str "sha256:" (sha256-hex bytes))
            path (str blobs-dir "/" hash)]
        (.mkdirs (io/file blobs-dir))
        (when-not (.exists (io/file path))
          (with-open [out (io/output-stream path)]
            (.write out bytes)))
        (created {:hash hash :size (count bytes)})))))

(defn handle-file-fetch [_req hash]
  (let [path (str blobs-dir "/" hash)
        f (io/file path)]
    (if (.exists f)
      {:status 200
       :headers {"Content-Type" "application/octet-stream"
                 "Content-Length" (str (.length f))
                 "Access-Control-Allow-Origin" "*"}
       :body f}
      (not-found "blob not found"))))

;; ─────────────────────────────────────────────
;; PRESENCE HANDLERS
;; ─────────────────────────────────────────────

(defn handle-presence-heartbeat! [req]
  (let [body (parse-body req)
        agent (:from body)]
    (when (str/blank? agent)
      (throw (ex-info "missing :from" {})))
    (db-exec!
     "INSERT INTO presence (agent_id, last_seen, channels, meta)
      VALUES (?,?,?,?)
      ON CONFLICT(agent_id) DO UPDATE SET
        last_seen=excluded.last_seen,
        channels=excluded.channels,
        meta=excluded.meta"
     agent (now)
     (json/encode (or (:channels body) []))
     (json/encode (or (:meta body) {})))
    (ok {:ok true})))

(defn handle-presence-list [_req]
  (let [cutoff (- (now) (/ presence-ttl-ms 1000))
        rows (db-query
              "SELECT * FROM presence WHERE last_seen > ?" cutoff)
        agents (map (fn [r]
                      (-> r
                          (update :channels #(json/parse-string % true))
                          (update :meta #(json/parse-string % true)))) rows)]
    (ok agents)))

;; ─────────────────────────────────────────────
;; STATUS
;; ─────────────────────────────────────────────

(def start-time (System/currentTimeMillis))

(defn handle-status [_req]
  (let [msg-count (:count (first (db-query "SELECT COUNT(*) as count FROM messages")))
        task-count (:count (first (db-query "SELECT COUNT(*) as count FROM tasks")))
        agent-count (:count (first (db-query
                                    "SELECT COUNT(*) as count FROM presence WHERE last_seen > ?"
                                    (- (now) (/ presence-ttl-ms 1000)))))
        uptime-s (/ (- (System/currentTimeMillis) start-time) 1000.0)]
    (ok {:uptime-s uptime-s
         :messages msg-count
         :tasks task-count
         :agents-live agent-count
         :subscribers (reduce + 0 (map count (vals @subscribers)))})))

;; ─────────────────────────────────────────────
;; UI  (terminal-aesthetic, embedded)
;; ─────────────────────────────────────────────

(def ui-html
  "<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='UTF-8'>
<meta name='viewport' content='width=device-width, initial-scale=1.0'>
<title>workshop</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=Berkeley+Mono:ital,wght@0,100..700;1,100..700&family=Space+Mono:ital,wght@0,400;0,700;1,400&display=swap');

  :root {
    --bg:       #0d0f11;
    --bg2:      #131619;
    --bg3:      #1a1e22;
    --border:   #2a2f36;
    --dim:      #4a5260;
    --muted:    #6b7585;
    --text:     #c8d0db;
    --bright:   #e8edf5;
    --task:     #4da6ff;
    --file:     #4dffaa;
    --agent:    #ffd24d;
    --err:      #ff6b6b;
    --general:  #cc88ff;
    --accent:   #4da6ff;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: 'Berkeley Mono', 'Space Mono', 'Fira Code', monospace;
    font-size: 12px;
    height: 100vh;
    display: grid;
    grid-template-rows: 40px 1fr;
    grid-template-columns: 180px 1fr 220px;
    grid-template-areas:
      'header header header'
      'sidebar feed presence';
    overflow: hidden;
  }

  header {
    grid-area: header;
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    padding: 0 16px;
    gap: 16px;
    background: var(--bg2);
  }

  .logo {
    color: var(--bright);
    font-weight: 700;
    letter-spacing: 0.15em;
    font-size: 13px;
  }

  .logo span { color: var(--accent); }

  .new-task-btn {
    margin-left: auto;
    font-family: inherit;
    font-size: 11px;
    background: transparent;
    color: var(--text);
    border: 1px solid var(--border);
    padding: 4px 12px;
    border-radius: 3px;
    cursor: pointer;
  }

  .new-task-btn:hover {
    border-color: var(--accent);
    color: var(--accent);
  }

  .status-dot {
    width: 7px; height: 7px;
    border-radius: 50%;
    background: var(--file);
    box-shadow: 0 0 6px var(--file);
    animation: pulse 2s ease-in-out infinite;
  }

  @keyframes pulse {
    0%,100% { opacity: 1; }
    50% { opacity: 0.4; }
  }

  .header-stats { color: var(--muted); font-size: 11px; margin-left: auto; }

  #sidebar {
    grid-area: sidebar;
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    background: var(--bg2);
    overflow: hidden;
  }

  .sidebar-section {
    padding: 10px 12px 4px;
    color: var(--dim);
    font-size: 10px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
  }

  .ch-list { overflow-y: auto; flex: 1; }

  .ch-item {
    padding: 6px 16px;
    cursor: pointer;
    color: var(--muted);
    transition: all 0.1s;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .ch-item:hover { background: var(--bg3); color: var(--text); }
  .ch-item.active { background: var(--bg3); color: var(--bright); }
  .ch-item.active::before { content: '▶'; font-size: 8px; color: var(--accent); }

  .unread {
    margin-left: auto;
    background: var(--accent);
    color: var(--bg);
    border-radius: 8px;
    padding: 1px 5px;
    font-size: 9px;
    font-weight: 700;
  }

  #feed {
    grid-area: feed;
    overflow-y: auto;
    padding: 8px 0;
    display: flex;
    flex-direction: column;
    scroll-behavior: smooth;
  }

  .msg {
    padding: 4px 16px;
    border-left: 2px solid transparent;
    transition: background 0.1s;
    cursor: pointer;
    line-height: 1.6;
  }

  .msg:hover { background: var(--bg2); }
  .msg.expanded { background: var(--bg2); border-left-color: var(--accent); }

  .msg-header {
    display: flex;
    align-items: baseline;
    gap: 8px;
    flex-wrap: wrap;
  }

  .msg-ts { color: var(--dim); font-size: 10px; flex-shrink: 0; }

  .msg-from { color: var(--agent); font-weight: 700; font-size: 11px; }

  .msg-ch { color: var(--dim); font-size: 10px; }

  .msg-type {
    font-size: 10px;
    padding: 1px 6px;
    border-radius: 3px;
    font-weight: 700;
    letter-spacing: 0.05em;
  }

  .type-task    { background: rgba(77,166,255,0.15); color: var(--task); }
  .type-file    { background: rgba(77,255,170,0.15); color: var(--file); }
  .type-agent   { background: rgba(255,210,77,0.15); color: var(--agent); }
  .type-general { background: rgba(204,136,255,0.15); color: var(--general); }
  .type-error   { background: rgba(255,107,107,0.15); color: var(--err); }

  .msg-body-preview {
    color: var(--muted);
    font-size: 11px;
    margin-top: 2px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 600px;
  }

  .msg-detail {
    display: none;
    margin-top: 8px;
    padding: 10px;
    background: var(--bg3);
    border-radius: 4px;
    border: 1px solid var(--border);
    font-size: 11px;
    white-space: pre-wrap;
    word-break: break-all;
    color: var(--text);
    max-height: 300px;
    overflow-y: auto;
  }

  .msg.expanded .msg-detail { display: block; }

  .file-badge {
    display: inline-block;
    font-size: 10px;
    color: var(--file);
    padding: 1px 5px;
    border: 1px solid rgba(77,255,170,0.3);
    border-radius: 3px;
    margin-top: 3px;
    margin-right: 4px;
    cursor: pointer;
    text-decoration: none;
  }

  .file-badge:hover { background: rgba(77,255,170,0.1); }

  #presence {
    grid-area: presence;
    border-left: 1px solid var(--border);
    background: var(--bg2);
    overflow-y: auto;
    padding-bottom: 16px;
  }

  .presence-header {
    padding: 10px 12px 4px;
    color: var(--dim);
    font-size: 10px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    border-bottom: 1px solid var(--border);
    margin-bottom: 4px;
  }

  .agent-item {
    padding: 8px 12px;
    border-bottom: 1px solid rgba(42,47,54,0.5);
  }

  .agent-name { color: var(--agent); font-weight: 700; font-size: 11px; }

  .agent-channels {
    color: var(--dim);
    font-size: 10px;
    margin-top: 2px;
  }

  .agent-dot {
    display: inline-block;
    width: 6px; height: 6px;
    border-radius: 50%;
    background: var(--file);
    margin-right: 5px;
    vertical-align: middle;
  }

  ::-webkit-scrollbar { width: 4px; }
  ::-webkit-scrollbar-track { background: transparent; }
  ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 2px; }

  .empty-state {
    padding: 32px 16px;
    color: var(--dim);
    text-align: center;
    line-height: 2;
  }

  .filter-bar {
    padding: 6px 12px;
    border-bottom: 1px solid var(--border);
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
    background: var(--bg2);
  }

  .filter-btn {
    font-family: inherit;
    font-size: 10px;
    padding: 2px 7px;
    border-radius: 3px;
    border: 1px solid var(--border);
    background: transparent;
    color: var(--muted);
    cursor: pointer;
    transition: all 0.1s;
  }

  .filter-btn:hover, .filter-btn.active {
    border-color: var(--accent);
    color: var(--accent);
    background: rgba(77,166,255,0.08);
  }

  #task-panel {
    display: none;
    grid-area: taskpanel;
    background: var(--bg2);
    border-bottom: 1px solid var(--border);
    padding: 12px;
    gap: 8px;
  }

  #task-panel.open { display: flex; flex-wrap: wrap; align-items: center; }

  #task-panel input, #task-panel textarea {
    font-family: inherit;
    font-size: 11px;
    background: var(--bg3);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 4px 8px;
    border-radius: 3px;
    outline: none;
  }

  #task-panel input:focus, #task-panel textarea:focus { border-color: var(--accent); }

  #task-panel input[name=title] { flex: 1; min-width: 150px; }
  #task-panel textarea[name=context] { flex: 1; min-width: 200px; height: 40px; resize: none; }
  #task-panel input[name=ch] { width: 80px; }
  #task-panel input[name=for] { width: 100px; }

  #task-panel button {
    font-family: inherit;
    font-size: 11px;
    background: var(--task);
    color: var(--bg);
    border: none;
    padding: 4px 12px;
    border-radius: 3px;
    cursor: pointer;
    font-weight: 700;
  }

  #task-panel .err { color: var(--err); font-size: 10px; margin-left: 8px; }

  body {
    grid-template-rows: 40px auto 1fr 48px;
    grid-template-areas:
      'header header header'
      'taskpanel taskpanel taskpanel'
      'sidebar feed presence'
      'sidebar compose presence';
  }

  #compose {
    grid-area: compose;
    border-top: 1px solid var(--border);
    background: var(--bg2);
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 12px;
  }

  #compose input, #compose select {
    font-family: inherit;
    font-size: 11px;
    background: var(--bg3);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 4px 8px;
    border-radius: 3px;
    outline: none;
  }

  #compose input:focus, #compose select:focus {
    border-color: var(--accent);
  }

  #compose input[name=body] { flex: 1; }

  #compose button {
    font-family: inherit;
    font-size: 11px;
    background: var(--accent);
    color: var(--bg);
    border: none;
    padding: 4px 12px;
    border-radius: 3px;
    cursor: pointer;
    font-weight: 700;
  }

  body {
    grid-template-rows: 40px 1fr 48px;
    grid-template-areas:
      'header header header'
      'sidebar feed presence'
      'sidebar compose presence';
  }
</style>
</head>
<body>

<header>
  <div class='logo'>work<span>shop</span></div>
  <div class='status-dot' id='dot'></div>
  <div id='hstats' class='header-stats'>connecting...</div>
  <button class='new-task-btn' onclick='toggleTaskPanel()'>+ new task</button>
</header>

<div id='task-panel'>
  <input type='text' name='title' placeholder='task title' required>
  <textarea name='context' placeholder='context (JSON)'></textarea>
  <input type='text' name='ch' placeholder='channel' value='tasks'>
  <input type='text' name='for' placeholder='for (optional)'>
  <button onclick='createTask()'>create</button>
  <span class='err' id='task-err'></span>
</div>

<div id='sidebar'>
  <div class='sidebar-section'>channels</div>
  <div class='ch-list' id='chlist'>
    <div class='ch-item active' data-ch='*' onclick='switchCh(this)'>
      ✦ all channels
    </div>
  </div>
</div>

<div id='feed'>
  <div class='filter-bar' id='filterbar'>
    <button class='filter-btn active' onclick='setFilter(this,null)'>all</button>
    <button class='filter-btn' onclick='setFilter(this,\"task\")'>task.*</button>
    <button class='filter-btn' onclick='setFilter(this,\"file\")'>file.*</button>
    <button class='filter-btn' onclick='setFilter(this,\"agent\")'>agent.*</button>
  </div>
  <div id='msgs'>
    <div class='empty-state'>⬡ waiting for messages...</div>
  </div>
</div>

<div id='compose'>
  <input type='text' name='from' placeholder='from' value='human.you'>
  <select name='ch'></select>
  <input type='text' name='body' placeholder='message...' onkeyup='composeEnter(this)'>
  <button onclick='composeSend()'>send</button>
</div>

<div id='presence'>
  <div class='presence-header'>agents online</div>
  <div id='agentlist'></div>
</div>

<script>
const BASE = window.location.origin;
let currentCh = '*';
let typeFilter = null;
let allMessages = [];
let channels = new Set(['general','tasks']);
let unread = {};
let eventSource = null;

function typeClass(type) {
  if (!type) return 'type-general';
  const ns = type.split('.')[0];
  return { task:'type-task', file:'type-file', agent:'type-agent', error:'type-error' }[ns] || 'type-general';
}

function fmtTs(ts) {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString('en',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'});
}

function fmtBody(body) {
  if (!body || Object.keys(body).length === 0) return '';
  return JSON.stringify(body).slice(0,120);
}

function renderMsg(m) {
  const div = document.createElement('div');
  div.className = 'msg';
  div.id = 'msg-' + m.id;
  div.onclick = () => div.classList.toggle('expanded');

  const fileBadges = (m.files||[]).map(h =>
    `<a class='file-badge' href='${BASE}/files/${h}' target='_blank' onclick='e=>e.stopPropagation()'>⬡ ${h.slice(0,16)}...</a>`
  ).join('');

  div.innerHTML = `
    <div class='msg-header'>
      <span class='msg-ts'>${fmtTs(m.ts)}</span>
      <span class='msg-from'>${m.from||'?'}</span>
      ${m.ch && currentCh==='*' ? `<span class='msg-ch'>#${m.ch}</span>` : ''}
      <span class='msg-type ${typeClass(m.type)}'>${m.type||'msg'}</span>
    </div>
    <div class='msg-body-preview'>${fmtBody(m.body)}</div>
    ${fileBadges ? `<div>${fileBadges}</div>` : ''}
    <div class='msg-detail'>${JSON.stringify(m, null, 2)}</div>
  `;
  return div;
}

function addMsg(m) {
  if (typeFilter && !m.type?.startsWith(typeFilter)) return;
  if (currentCh !== '*' && m.ch !== currentCh) {
    unread[m.ch] = (unread[m.ch]||0) + 1;
    updateSidebar();
    return;
  }

  const container = document.getElementById('msgs');
  const empty = container.querySelector('.empty-state');
  if (empty) empty.remove();

  const el = renderMsg(m);
  container.appendChild(el);

  // keep last 500 msgs in dom
  const msgs = container.querySelectorAll('.msg');
  if (msgs.length > 500) msgs[0].remove();

  // auto-scroll if near bottom
  const feed = document.getElementById('feed');
  if (feed.scrollHeight - feed.scrollTop < feed.clientHeight + 80) {
    el.scrollIntoView({behavior:'smooth', block:'end'});
  }
}

function connectSSE() {
  if (eventSource) eventSource.close();
  const url = currentCh === '*' ? `${BASE}/` : `${BASE}/ch/${currentCh}`;
  eventSource = new EventSource(url);
  eventSource.onmessage = e => {
    try {
      const m = JSON.parse(e.data);
      allMessages.push(m);
      if (m.ch && !channels.has(m.ch)) {
        channels.add(m.ch);
        updateSidebar();
      }
      addMsg(m);
    } catch(err) {}
  };
  eventSource.onerror = () => {
    document.getElementById('dot').style.background = 'var(--err)';
    document.getElementById('hstats').textContent = 'reconnecting...';
    setTimeout(connectSSE, 3000);
  };
  eventSource.onopen = () => {
    document.getElementById('dot').style.background = 'var(--file)';
  };
}

function switchCh(el) {
  document.querySelectorAll('.ch-item').forEach(e => e.classList.remove('active'));
  el.classList.add('active');
  currentCh = el.dataset.ch;
  if (unread[currentCh]) { delete unread[currentCh]; updateSidebar(); }
  document.getElementById('msgs').innerHTML = '';
  // replay from buffer
  const toShow = currentCh === '*' ? allMessages : allMessages.filter(m => m.ch === currentCh);
  toShow.slice(-200).forEach(addMsg);
  connectSSE();
  populateComposeChannels();
}

function setFilter(btn, f) {
  typeFilter = f;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  document.getElementById('msgs').innerHTML = '';
  const msgs = currentCh === '*' ? allMessages : allMessages.filter(m => m.ch === currentCh);
  msgs.slice(-200).forEach(addMsg);
}

function updateSidebar() {
  const list = document.getElementById('chlist');
  // preserve all button
  const allBtn = list.querySelector('[data-ch=\"*\"]');
  list.innerHTML = '';
  list.appendChild(allBtn);

  [...channels].sort().forEach(ch => {
    const d = document.createElement('div');
    d.className = 'ch-item' + (currentCh===ch ? ' active' : '');
    d.dataset.ch = ch;
    d.onclick = () => switchCh(d);
    d.innerHTML = `# ${ch}`;
    if (unread[ch]) {
      const badge = document.createElement('span');
      badge.className = 'unread';
      badge.textContent = unread[ch];
      d.appendChild(badge);
    }
    list.appendChild(d);
  });
}

async function refreshPresence() {
  try {
    const r = await fetch(`${BASE}/presence`);
    const agents = await r.json();
    const el = document.getElementById('agentlist');
    el.innerHTML = '';
    if (!agents.length) {
      el.innerHTML = '<div style=\"padding:12px;color:var(--dim);font-size:10px\">no agents online</div>';
      return;
    }
    agents.forEach(a => {
      const d = document.createElement('div');
      d.className = 'agent-item';
      d.innerHTML = `
        <div class='agent-name'><span class='agent-dot'></span>${a.agent_id}</div>
        <div class='agent-channels'>${(a.channels||[]).map(c=>'#'+c).join(' ') || 'no channels'}</div>
      `;
      el.appendChild(d);
    });
  } catch(e) {}
}

async function refreshStatus() {
  try {
    const r = await fetch(`${BASE}/status`);
    const s = await r.json();
    document.getElementById('hstats').textContent =
      `${s.messages} msgs · ${s.tasks} tasks · ${s.agents_live} agents`;
  } catch(e) {}
}

// load recent history on start
async function loadHistory() {
  try {
    const r = await fetch(`${BASE}/ch/general/history?n=50`);
    const text = await r.text();
    text.trim().split('\\n').filter(Boolean).forEach(line => {
      try {
        const m = JSON.parse(line);
        allMessages.push(m);
        if (m.ch) channels.add(m.ch);
      } catch(e) {}
    });
    updateSidebar();
    // show recent msgs
    allMessages.slice(-50).forEach(addMsg);
  } catch(e) {}
}

function toggleTaskPanel() {
  const panel = document.getElementById('task-panel');
  panel.classList.toggle('open');
}

async function createTask() {
  const title = document.querySelector('#task-panel input[name=title]').value;
  const contextRaw = document.querySelector('#task-panel textarea[name=context]').value;
  const ch = document.querySelector('#task-panel input[name=ch]').value || 'tasks';
  const forAgent = document.querySelector('#task-panel input[name=for]').value;
  const err = document.getElementById('task-err');
  err.textContent = '';

  if (!title) {
    err.textContent = 'title required';
    return;
  }

  let context = {};
  if (contextRaw.trim()) {
    try { context = JSON.parse(contextRaw); }
    catch(e) { err.textContent = 'invalid JSON'; return; }
  }

  const body = { from: 'human.you', title: title, context: context };
  if (forAgent) body.for = forAgent;

  try {
    const r = await fetch(BASE + '/tasks', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)
    });
    if (!r.ok) throw new Error((await r.json()).error);
    document.querySelector('#task-panel input[name=title]').value = '';
    document.querySelector('#task-panel textarea[name=context]').value = '';
    document.querySelector('#task-panel input[name=for]').value = '';
    toggleTaskPanel();
  } catch(e) { err.textContent = e.message; }
}

function populateComposeChannels() {
  const sel = document.querySelector('#compose select');
  sel.innerHTML = '';
  [...channels].sort().forEach(ch => {
    const opt = document.createElement('option');
    opt.value = ch;
    opt.textContent = ch;
    if (ch === currentCh || (currentCh === '*' && ch === 'general')) opt.selected = true;
    sel.appendChild(opt);
  });
}

async function handleFileUpload(inp) {
  const file = inp.files[0];
  if (!file) return;
  try {
    const buffer = await file.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    const resp = await fetch(BASE + '/files', {
      method: 'POST',
      headers: {'Content-Type': 'application/octet-stream'},
      body: bytes
    });
    if (!resp.ok) throw new Error('upload failed');
    const result = await resp.json();
    alert('Uploaded: ' + result.hash + ' (' + result.size + ' bytes)');
    const from = document.querySelector('#compose input[name=from]').value || 'human.you';
    const ch = document.querySelector('#compose select').value || 'general';
    await fetch(BASE + '/ch/' + ch, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({from: from, type: 'file.uploaded', body: {filename: file.name, hash: result.hash, size: result.size}})
    });
  } catch(e) { alert('Upload failed: ' + e.message); }
  inp.value = '';
}

function composeEnter(inp) {
  if (event.key === 'Enter') composeSend();
}

async function composeSend() {
  const from = document.querySelector('#compose input[name=from]').value || 'human.you';
  const ch = document.querySelector('#compose select').value;
  const body = document.querySelector('#compose input[name=body]').value;
  if (!body) return;
  try {
    await fetch(BASE + '/ch/' + ch, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({from: from, type: 'msg.human', body: {text: body}})
    });
    document.querySelector('#compose input[name=body]').value = '';
  } catch(e) { console.error(e); }
}

// init
loadHistory().then(() => {
  connectSSE();
  refreshPresence();
  refreshStatus();
  populateComposeChannels();
  setInterval(refreshPresence, 15000);
  setInterval(refreshStatus, 30000);
});
</script>
</body>
</html>")

(defn handle-ui [_req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body ui-html})

;; ─────────────────────────────────────────────
;; ROUTER
;; ─────────────────────────────────────────────

;; cond-based router — no core.match dep needed in babashka
(defn route [req]
  (let [method (:request-method req)
        uri (:uri req)
        path (str/replace uri #"\?.*" "")
        parts (str/split path #"/")]
    (try
      (cond
        (= [:get "/"] [method path]) (handle-stream req :all)
        (= [:get "/ui"] [method path]) (handle-ui req)
        (= [:get "/status"] [method path]) (handle-status req)

        (= [:post "/presence"] [method path]) (handle-presence-heartbeat! req)
        (= [:get "/presence"] [method path]) (handle-presence-list req)

        (and (= method :post)
             (re-matches #"/ch/[^/]+" path))
        (handle-publish! req (get parts 2))

        (and (= method :get)
             (re-matches #"/ch/[^/]+/history" path))
        (handle-history req (get parts 2))

        (and (= method :get)
             (re-matches #"/ch/[^/]+" path))
        (handle-stream req (get parts 2))

        (= [:post "/tasks"] [method path]) (handle-task-create! req)
        (= [:get "/tasks"] [method path]) (handle-task-list req)

        (and (= method :post)
             (re-matches #"/tasks/[^/]+/claim" path))
        (handle-task-claim! req (get parts 2))

        (and (= method :post)
             (re-matches #"/tasks/[^/]+/update" path))
        (handle-task-update! req (get parts 2))

        (and (= method :post)
             (re-matches #"/tasks/[^/]+/done" path))
        (handle-task-done! req (get parts 2))

        (and (= method :post)
             (re-matches #"/tasks/[^/]+/abandon" path))
        (handle-task-abandon! req (get parts 2))

        (and (= method :post)
             (re-matches #"/tasks/[^/]+/interrupt" path))
        (handle-task-interrupt! req (get parts 2))

        (= [:post "/files"] [method path]) (handle-file-upload! req)

        (and (= method :get)
             (re-matches #"/files/.+" path))
        (handle-file-fetch req (get parts 2))

        (= method :options)
        {:status 204 :headers {"Access-Control-Allow-Origin" "*"
                               "Access-Control-Allow-Methods" "GET,POST,OPTIONS"
                               "Access-Control-Allow-Headers" "Content-Type"}}

        :else (not-found "not found"))
      (catch Exception e
        (let [{:keys [status]} (ex-data e)
              msg (.getMessage e)]
          (when verbose? (println "error" path msg))
          (json-resp (or status 500) {:error msg}))))))

;; ─────────────────────────────────────────────
;; MIDDLEWARE
;; ─────────────────────────────────────────────

(defn log-request [req resp start-ms]
  (when verbose?
    (let [duration (- (System/currentTimeMillis) start-ms)
          method (str/upper-case (name (:request-method req)))
          uri (:uri req)
          status (:status resp 0)]
      (println (format "[%s] %s %s -> %d (%dms)"
                       (java.time.Instant/now)
                       method uri status duration)))))

(defn wrap-logging [handler]
  (fn [req]
    (let [start (System/currentTimeMillis)
          resp (handler req)]
      (log-request req resp start)
      resp)))

;; ─────────────────────────────────────────────
;; CLEANUP
;; ─────────────────────────────────────────────

(defn cleanup-old-messages!
  "Delete messages older than retention-days"
  []
  (try
    (let [cutoff (- (now) (* retention-days 24 60 60))]
      (db-exec! "DELETE FROM messages WHERE ts < ?" cutoff)
      (when verbose?
        (println (format "[cleanup] removed old messages (retention: %d days)" retention-days))))
    (catch Exception e
      (println "[cleanup] error:" (.getMessage e)))))

(defn start-cleanup!
  "Start periodic cleanup task every hour"
  []
  (future
    (loop []
      (Thread/sleep (* 60 60 1000)) ;; 1 hour
      (cleanup-old-messages!)
      (recur))))

;; ─────────────────────────────────────────────
;; MAIN
;; ─────────────────────────────────────────────

(defonce server (atom nil))

(defn shutdown! []
  (println "\nshutting down...")
  (when @server
    (@server :timeout 5000))
  (System/exit 0))

(defn -main []
  (init-db!)
  (cleanup-old-messages!)
  (.mkdirs (io/file blobs-dir))
  (sse-keepalive!)
  (start-cleanup!)

  ;; graceful shutdown on SIGTERM/SIGINT
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. shutdown!))

  (reset! server
          (http/run-server (wrap-logging route) {:port port :thread 16}))
  (println (str "workshop running on :" port))
  (println (str "  ui   → http://localhost:" port "/ui"))
  (println (str "  feed → curl -N http://localhost:" port "/ | jq ."))
  @(promise)) ;; block forever

(-main)
