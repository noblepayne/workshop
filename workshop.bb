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
;;
;; RUN:   bb workshop.bb
;; PORT:  4242 (set PORT env to override)
;; DATA:  ./workshop.db  (sqlite, WAL mode)
;; FILES: ./blobs/       (content-addressed)
;;
;; IDENTITY CONVENTION: "agent-name.owner" e.g. "harvester.alice"
;;   No enforcement. Honor system. Mesh handles real trust.
;;
;; MESSAGE ENVELOPE:
;;   {:id       "01J4..."          ;; ULID, sortable+unique
;;    :ts       1708123456.789     ;; unix float
;;    :from     "harvester.alice"  ;; agent-name.owner
;;    :ch       "general"          ;; channel name
;;    :type     "task.claimed"     ;; dot-namespaced, agents pattern-match
;;    :v        1                  ;; schema version hint
;;    :body     {}                 ;; free-form, type-specific payload
;;    :files    ["sha256:abc..."]  ;; optional blob refs
;;    :reply-to "01J3..."}         ;; optional threading
;;
;; API:
;;   POST /ch/:ch              publish message
;;   GET  /ch/:ch              SSE stream (live)
;;   GET  /ch/:ch/history      last N messages, ndjson ?since=<id>&n=<int>&type=<prefix>
;;   GET  /history             last N messages across all channels
;;   GET  /channels            list all channels seen
;;   POST /tasks               create task
;;   GET  /tasks               list tasks ?status=open&for=agent
;;   GET  /tasks/:id           fetch single task
;;   POST /tasks/:id/claim     claim (first write wins, 409 if taken)
;;   POST /tasks/:id/update    progress note (any agent)
;;   POST /tasks/:id/done      complete + optional files (claiming agent only)
;;   POST /tasks/:id/abandon   release back to pool (claiming agent only)
;;   POST /tasks/:id/interrupt signal interrupt (any agent)
;;   POST /files               upload blob → {hash size}
;;   GET  /files/:hash         fetch blob
;;   POST /presence            heartbeat {from channels meta}
;;   GET  /presence            who's alive (seen in last 60s)
;;   GET  /                    SSE of everything (all channels merged)
;;   GET  /ui                  human terminal web view
;;   GET  /status              health + counts

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
(def max-file-size (* 100 1024 1024)) ;; 100MB
(def verbose? (= "true" (System/getenv "WORKSHOP_VERBOSE")))
(def retention-days (try (Integer/parseInt (or (System/getenv "WORKSHOP_RETENTION_DAYS") "30"))
                         (catch Exception _ 30)))

;; ─────────────────────────────────────────────
;; ULID  (sortable unique id, url-safe)
;; ─────────────────────────────────────────────

(def ^:private ulid-chars "0123456789ABCDEFGHJKMNPQRSTVWXYZ")
(def ^:private ulid-len 26)

(defn new-ulid []
  (let [ts  (System/currentTimeMillis)
        sb  (StringBuilder. ulid-len)
        rng (java.util.concurrent.ThreadLocalRandom/current)]
    ;; 10 timestamp chars — built LSB-first with insert-at-0, so final order is MSB-first (correct)
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
  ;; Create parent directories for db-path if they don't exist
  (io/make-parents (io/file db-path))
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
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_messages_ch    ON messages(ch)")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_messages_ts    ON messages(ts)")
  ;; Composite index covers both channel-filtered and type-prefix history queries
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_messages_ch_type ON messages(ch, type)")
  (db-exec!
   "CREATE TABLE IF NOT EXISTS tasks (
      id          TEXT PRIMARY KEY,
      created_at  REAL NOT NULL,
      updated_at  REAL NOT NULL,
      created_by  TEXT NOT NULL,
      assigned_to TEXT,
      claimed_by  TEXT,
      claimed_at  REAL,
      status      TEXT NOT NULL DEFAULT 'open',
      title       TEXT NOT NULL,
      context     TEXT NOT NULL DEFAULT '{}',
      result      TEXT,
      files       TEXT NOT NULL DEFAULT '[]',
      ch          TEXT NOT NULL DEFAULT 'tasks'
    )")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
  (db-exec! "CREATE INDEX IF NOT EXISTS idx_tasks_ch     ON tasks(ch)")
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
;;
;; subscribers atom shape: {ch #{client ...}, :all #{client ...}}
;;
;; IMPORTANT: always snapshot the inner set before iterating
;; (into [] ...) before doseq prevents ConcurrentModificationException
;; when unsub! is called from another thread mid-iteration.
;; Dead clients are removed on first failed send rather than accumulating forever.
;; ─────────────────────────────────────────────

(def subscribers (atom {}))

(defn sub! [ch client]
  (swap! subscribers update ch (fnil conj #{}) client))

(defn unsub! [ch client]
  (swap! subscribers update ch disj client))

(defn broadcast! [ch msg-str msg-id]
  ;; Include SSE id: field so browsers can send Last-Event-ID on reconnect
  (let [payload (str "id: " msg-id "\ndata: " msg-str "\n\n")]
    (doseq [client (into [] (get @subscribers ch #{}))]
      (try (http/send! client payload)
           (catch Exception _ (unsub! ch client))))
    ;; also fan out to god-view (:all) subscribers
    (when (not= ch :all)
      (doseq [client (into [] (get @subscribers :all #{}))]
        (try (http/send! client payload)
             (catch Exception _ (unsub! ch :all)))))))

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

;; ─────────────────────────────────────────────
;; HELPERS
;; ─────────────────────────────────────────────

(defn now [] (/ (System/currentTimeMillis) 1000.0))

(defn parse-body [req]
  (let [raw (try (slurp (:body req)) (catch Exception _ ""))]
    (if (str/blank? raw)
      {}
      (try (json/parse-string raw true)
           (catch Exception _
             (throw (ex-info "invalid JSON body" {:status 400})))))))

(defn json-resp [status body]
  {:status  status
   :headers {"Content-Type"                "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body    (json/encode body)})

(defn ok       [body] (json-resp 200 body))
(defn created  [body] (json-resp 201 body))
(defn bad      [msg]  (json-resp 400 {:error msg}))
(defn not-found [msg] (json-resp 404 {:error msg}))
(defn conflict  [msg] (json-resp 409 {:error msg}))
(defn forbidden [msg] (json-resp 403 {:error msg}))

(def sse-headers
  {"Content-Type"                "text/event-stream"
   "Cache-Control"               "no-cache"
   "X-Accel-Buffering"           "no"     ;; critical: prevents nginx from buffering the stream
   "Connection"                  "keep-alive"
   "Access-Control-Allow-Origin" "*"})

(defn row->msg [row]
  (-> row
      (update :body  #(json/parse-string % true))
      (update :files #(json/parse-string % true))
      (dissoc :reply_to)
      (assoc  :reply-to (:reply_to row))))

(defn msg->row [msg]
  {:id       (:id msg)
   :ts       (:ts msg)
   :from_id  (:from msg)
   :ch       (:ch msg)
   :type     (:type msg)
   :v        (or (:v msg) 1)
   :body     (json/encode (or (:body msg) {}))
   :files    (json/encode (or (:files msg) []))
   :reply_to (:reply-to msg)})

(defn parse-task-row [r]
  (-> r
      (update :context #(json/parse-string % true))
      (update :files   #(json/parse-string % true))))

;; ─────────────────────────────────────────────
;; CHANNEL HANDLERS
;; ─────────────────────────────────────────────

(defn handle-publish! [req ch]
  (let [body    (parse-body req)
        msg     (merge body {:id (new-ulid) :ts (now) :ch ch})
        row     (msg->row msg)
        encoded (json/encode msg)]
    (when (str/blank? (:from msg))
      (throw (ex-info "missing :from" {:status 400})))
    (when (str/blank? (:type msg))
      (throw (ex-info "missing :type" {:status 400})))
    (db-exec!
     "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files,reply_to)
      VALUES (?,?,?,?,?,?,?,?,?)"
     (:id row) (:ts row) (:from_id row) (:ch row)
     (:type row) (:v row) (:body row) (:files row) (:reply_to row))
    (broadcast! ch encoded (:id msg))
    (created {:id (:id msg) :ts (:ts msg)})))

(defn handle-stream [req ch]
  ;; Replay any missed messages if client sends Last-Event-ID on reconnect.
  ;; This ensures no messages are lost during brief disconnects.
  (let [method (:request-method req)]
    ;; HEAD request: just return headers, no body (for browser EventSource preflight)
    (if (= method :head)
      {:status 200 :headers sse-headers}
      ;; GET request: set up SSE channel
      ;; Note: Don't use :init - send headers via send! in :on-open so they flush immediately
      ;; Firefox EventSource needs headers sent before it transitions to OPEN state
      (let [last-id (get-in req [:headers "last-event-id"])
            missed  (when (and last-id (not= ch :all))
                      (->> (db-query
                            "SELECT * FROM messages WHERE ch=? AND id>? ORDER BY id ASC"
                            (if (= ch :all) nil ch) last-id)
                           (map row->msg)))
            missed-all (when (and last-id (= ch :all))
                         (->> (db-query
                               "SELECT * FROM messages WHERE id>? ORDER BY id ASC"
                               last-id)
                              (map row->msg)))]
        (http/as-channel req
                         {:on-open  (fn [client]
                                      ;; Send headers first so Firefox EventSource transitions to OPEN
                                      (http/send! client {:status 200 :headers sse-headers} false)
                                      ;; Send missed messages if reconnecting
                                      (doseq [msg (or missed missed-all)]
                                        (let [frame (str "id: " (:id msg) "\ndata: " (json/encode msg) "\n\n")]
                                          (http/send! client frame)))
                                      ;; Subscribe for live updates
                                      (sub! ch client))
                          :on-close (fn [client _] (unsub! ch client))})))))

(defn handle-history [req ch]
  (let [params      (:query-params req)
        since       (get params "since")
        type-filter (get params "type")
        n-req       (try (Integer/parseInt (get params "n" (str history-limit)))
                         (catch Exception _ history-limit))
        n           (min n-req history-limit)
        rows        (cond
                      (and since type-filter)
                      (db-query
                       "SELECT * FROM messages WHERE ch=? AND id>? AND type LIKE ? ORDER BY id DESC LIMIT ?"
                       ch since (str type-filter "%") n)
                      since
                      (db-query
                       "SELECT * FROM messages WHERE ch=? AND id>? ORDER BY id DESC LIMIT ?"
                       ch since n)
                      type-filter
                      (db-query
                       "SELECT * FROM messages WHERE ch=? AND type LIKE ? ORDER BY id DESC LIMIT ?"
                       ch (str type-filter "%") n)
                      :else
                      (db-query
                       "SELECT * FROM messages WHERE ch=? ORDER BY id DESC LIMIT ?"
                       ch n))
        msgs        (->> rows (map row->msg) reverse)]
    {:status  200
     :headers {"Content-Type"                "application/x-ndjson"
               "Access-Control-Allow-Origin" "*"}
     :body    (str/join "\n" (map json/encode msgs))}))

(defn handle-global-history [req]
  (let [params (:query-params req)
        n-req  (try (Integer/parseInt (get params "n" "100")) (catch Exception _ 100))
        n      (min n-req history-limit)
        rows   (db-query "SELECT * FROM messages ORDER BY id DESC LIMIT ?" n)
        msgs   (->> rows (map row->msg) reverse)]
    {:status  200
     :headers {"Content-Type"                "application/x-ndjson"
               "Access-Control-Allow-Origin" "*"}
     :body    (str/join "\n" (map json/encode msgs))}))

(defn handle-channels [_req]
  (let [rows (db-query "SELECT DISTINCT ch FROM messages ORDER BY ch")
        chs  (map :ch rows)]
    (ok chs)))

;; ─────────────────────────────────────────────
;; TASK HELPERS
;; ─────────────────────────────────────────────

(defn get-task [id]
  (first (db-query "SELECT * FROM tasks WHERE id=?" id)))

(defn task-announce! [ts ch from type body files]
  (let [msg {:id (new-ulid) :ts ts :from from :ch ch :type type :body body}]
    (db-exec!
     "INSERT INTO messages (id,ts,from_id,ch,type,v,body,files)
      VALUES (?,?,?,?,?,1,?,?)"
     (:id msg) ts from ch type
     (json/encode body) (json/encode (or files [])))
    (broadcast! ch (json/encode (assoc msg :files (or files []))) (:id msg))))

;; ─────────────────────────────────────────────
;; TASK HANDLERS
;; ─────────────────────────────────────────────

(defn handle-task-create! [req]
  (let [body (parse-body req)
        id   (new-ulid)
        ts   (now)
        ch   (or (:ch body) "tasks")
        from (or (:from body) (:created_by body))]
    (when (str/blank? from)
      (throw (ex-info "missing :from or :created_by" {:status 400})))
    (when (str/blank? (:title body))
      (throw (ex-info "missing :title" {:status 400})))
    (db-exec!
     "INSERT INTO tasks (id,created_at,updated_at,created_by,assigned_to,status,title,context,files,ch)
      VALUES (?,?,?,?,?,?,?,?,?,?)"
     id ts ts from (:for body) "open" (:title body)
     (json/encode (or (:context body) {}))
     (json/encode [])
     ch)
    (task-announce! ts ch from "task.created"
                    {:task-id id :title (:title body) :for (:for body)} nil)
    (created {:id id})))

(defn handle-task-get [_req id]
  (let [task (get-task id)]
    (if task
      (ok (parse-task-row task))
      (not-found "task not found"))))

(defn handle-task-list [req]
  (let [params  (:query-params req)
        status  (get params "status")
        for-who (get params "for")
        ;; ?for= matches assigned_to OR claimed_by — intentional, shows "my tasks" either way
        sql     (cond
                  (and status for-who)
                  ["SELECT * FROM tasks WHERE status=? AND (assigned_to=? OR claimed_by=?) ORDER BY created_at DESC"
                   status for-who for-who]
                  status
                  ["SELECT * FROM tasks WHERE status=? ORDER BY created_at DESC" status]
                  for-who
                  ["SELECT * FROM tasks WHERE assigned_to=? OR claimed_by=? ORDER BY created_at DESC"
                   for-who for-who]
                  :else
                  ["SELECT * FROM tasks ORDER BY created_at DESC LIMIT 100"])
        rows    (apply db-query sql)]
    (ok (map parse-task-row rows))))

(defn handle-task-claim! [req id]
  (let [body  (parse-body req)
        agent (:from body)
        task  (get-task id)]
    (when (str/blank? agent)
      (throw (ex-info "missing :from" {:status 400})))
    (cond
      (nil? task)                    (not-found "task not found")
      (not= (:status task) "open")   (conflict (str "task is " (:status task) ", not open"))
      :else
      (do
        (db-exec!
         "UPDATE tasks SET status='claimed', claimed_by=?, claimed_at=?, updated_at=?
          WHERE id=? AND status='open'"
         agent (now) (now) id)
        (let [updated (get-task id)]
          (if (= (:claimed_by updated) agent)
            (do
              (task-announce! (now) (:ch task) agent "task.claimed"
                              {:task-id id :title (:title task)} nil)
              (ok {:id id :status "claimed" :claimed-by agent}))
            ;; lost the race — another agent claimed between our check and update
            (conflict "lost claim race — already claimed")))))))

(defn handle-task-update! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (when (str/blank? (:from body))
      (throw (ex-info "missing :from" {:status 400})))
    (db-exec! "UPDATE tasks SET updated_at=? WHERE id=?" (now) id)
    (task-announce! (now) (:ch task) (:from body) "task.updated"
                    (merge {:task-id id} (dissoc body :from)) nil)
    (ok {:id id})))

(defn handle-task-done! [req id]
  (let [body  (parse-body req)
        task  (get-task id)
        files (or (:files body) [])]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (when (str/blank? (:from body))
      (throw (ex-info "missing :from" {:status 400})))
    ;; Only the claiming agent can mark done (or if unclaimed, the creator)
    (when (and (:claimed_by task)
               (not= (:from body) (:claimed_by task)))
      (throw (ex-info "only the claiming agent can mark this task done" {:status 403})))
    (when (not= (:status task) "claimed")
      (throw (ex-info (str "task must be claimed to mark done, currently: " (:status task))
                      {:status 409})))
    (db-exec!
     "UPDATE tasks SET status='done', updated_at=?, result=?, files=? WHERE id=?"
     (now) (json/encode (or (:result body) {})) (json/encode files) id)
    (task-announce! (now) (:ch task) (:from body) "task.done"
                    {:task-id id :title (:title task)} files)
    (ok {:id id :status "done"})))

(defn handle-task-abandon! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (when (str/blank? (:from body))
      (throw (ex-info "missing :from" {:status 400})))
    ;; Only the claiming agent can abandon
    (when (and (:claimed_by task)
               (not= (:from body) (:claimed_by task)))
      (throw (ex-info "only the claiming agent can abandon this task" {:status 403})))
    (when (not= (:status task) "claimed")
      (throw (ex-info (str "task must be claimed to abandon, currently: " (:status task))
                      {:status 409})))
    (db-exec!
     "UPDATE tasks SET status='open', claimed_by=NULL, claimed_at=NULL, updated_at=? WHERE id=?"
     (now) id)
    (task-announce! (now) (:ch task) (:from body) "task.abandoned"
                    {:task-id id :title (:title task)} nil)
    (ok {:id id :status "open"})))

(defn handle-task-interrupt! [req id]
  (let [body (parse-body req)
        task (get-task id)]
    (when (nil? task)
      (throw (ex-info "task not found" {:status 404})))
    (when (str/blank? (:from body))
      (throw (ex-info "missing :from" {:status 400})))
    (task-announce! (now) (:ch task) (:from body) "task.interrupt"
                    {:task-id id :title (:title task) :reason (:reason body)} nil)
    (ok {:id id :signalled true})))

;; ─────────────────────────────────────────────
;; FILE HANDLERS
;; ─────────────────────────────────────────────

(defn sha256-hex [bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        h  (.digest md bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) h))))

(defn handle-file-upload! [req]
  (let [content-length (some-> req :headers (get "content-length") Integer/parseInt)]
    (when (and content-length (> content-length max-file-size))
      (throw (ex-info (str "file too large, max " max-file-size " bytes") {:status 413})))
    (let [bytes (.readAllBytes (:body req))]
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
  ;; Validate hash format to prevent path traversal
  (when-not (re-matches #"sha256:[0-9a-f]{64}" hash)
    (throw (ex-info "invalid hash format" {:status 400})))
  (let [f (io/file blobs-dir hash)]
    (if (.exists f)
      {:status  200
       :headers {"Content-Type"                "application/octet-stream"
                 "Content-Length"              (str (.length f))
                 "Access-Control-Allow-Origin" "*"}
       :body f}
      (not-found "blob not found"))))

;; ─────────────────────────────────────────────
;; PRESENCE HANDLERS
;; ─────────────────────────────────────────────

(defn handle-presence-heartbeat! [req]
  (let [body  (parse-body req)
        agent (:from body)]
    (when (str/blank? agent)
      (throw (ex-info "missing :from" {:status 400})))
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
        rows   (db-query "SELECT * FROM presence WHERE last_seen > ?" cutoff)]
    (ok (map (fn [r]
               (-> r
                   (update :channels #(json/parse-string % true))
                   (update :meta     #(json/parse-string % true))))
             rows))))

;; ─────────────────────────────────────────────
;; STATUS
;; ─────────────────────────────────────────────

(def start-time (System/currentTimeMillis))

(defn handle-status [_req]
  (let [msg-count   (:count (first (db-query "SELECT COUNT(*) as count FROM messages")))
        task-count  (:count (first (db-query "SELECT COUNT(*) as count FROM tasks")))
        agent-count (:count (first (db-query
                                    "SELECT COUNT(*) as count FROM presence WHERE last_seen > ?"
                                    (- (now) (/ presence-ttl-ms 1000)))))
        uptime-s    (/ (- (System/currentTimeMillis) start-time) 1000.0)
        sub-count   (reduce + 0 (map count (vals @subscribers)))]
    (ok {:uptime-s    uptime-s
         :messages    msg-count
         :tasks       task-count
         :agents-live agent-count
         :subscribers sub-count})))

;; ─────────────────────────────────────────────
;; CLEANUP
;; ─────────────────────────────────────────────

(defn cleanup! []
  (try
    (let [msg-cutoff      (- (now) (* retention-days 24 60 60))
          presence-cutoff (- (now) (* 7 24 60 60))]
      ;; Messages
      (db-exec! "DELETE FROM messages WHERE ts < ?" msg-cutoff)
      ;; Stale presence rows (7-day TTL, independent of retention-days)
      (db-exec! "DELETE FROM presence WHERE last_seen < ?" presence-cutoff)
      (println (format "[cleanup] done — retention %dd, presence 7d" retention-days)))
    (catch Exception e
      (println "[cleanup] error:" (.getMessage e)))))

(defn start-cleanup! []
  (future
    (loop []
      (Thread/sleep (* 60 60 1000)) ;; hourly
      (cleanup!)
      (recur))))

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
    --bg:      #0d0f11;
    --bg2:     #131619;
    --bg3:     #1a1e22;
    --border:  #2a2f36;
    --dim:     #4a5260;
    --muted:   #6b7585;
    --text:    #c8d0db;
    --bright:  #e8edf5;
    --task:    #4da6ff;
    --file:    #4dffaa;
    --agent:   #ffd24d;
    --err:     #ff6b6b;
    --general: #cc88ff;
    --accent:  #4da6ff;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: 'Berkeley Mono', 'Space Mono', 'Fira Code', monospace;
    font-size: 12px;
    height: 100vh;
    display: grid;
    grid-template-rows: 40px 1fr 48px;
    grid-template-columns: 180px 1fr 220px;
    grid-template-areas:
      'header  header  header'
      'sidebar feed    presence'
      'sidebar compose presence';
    overflow: hidden;
  }

  header {
    grid-area: header;
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    padding: 0 16px;
    gap: 12px;
    background: var(--bg2);
  }

  .logo { color: var(--bright); font-weight: 700; letter-spacing: 0.15em; font-size: 13px; }
  .logo span { color: var(--accent); }

  .status-dot {
    width: 7px; height: 7px; border-radius: 50%;
    background: var(--file);
    box-shadow: 0 0 6px var(--file);
    animation: pulse 2s ease-in-out infinite;
    flex-shrink: 0;
  }
  .status-dot.err { background: var(--err); box-shadow: 0 0 6px var(--err); animation: none; }

  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }

  .header-stats { color: var(--muted); font-size: 11px; }
  .header-right  { margin-left: auto; display: flex; gap: 8px; align-items: center; }

  .btn {
    font-family: inherit; font-size: 11px;
    background: transparent; color: var(--text);
    border: 1px solid var(--border);
    padding: 4px 10px; border-radius: 3px; cursor: pointer;
  }
  .btn:hover { border-color: var(--accent); color: var(--accent); }

  #sidebar {
    grid-area: sidebar;
    border-right: 1px solid var(--border);
    display: flex; flex-direction: column;
    background: var(--bg2); overflow: hidden;
  }

  .sidebar-section {
    padding: 10px 12px 4px;
    color: var(--dim); font-size: 10px;
    letter-spacing: 0.12em; text-transform: uppercase;
  }

  .ch-list { overflow-y: auto; flex: 1; }

  .ch-item {
    padding: 6px 16px; cursor: pointer;
    color: var(--muted);
    display: flex; align-items: center; gap: 6px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    transition: all 0.1s;
  }
  .ch-item:hover { background: var(--bg3); color: var(--text); }
  .ch-item.active { background: var(--bg3); color: var(--bright); }
  .ch-item.active::before { content: '▶'; font-size: 8px; color: var(--accent); }

  .unread {
    margin-left: auto;
    background: var(--accent); color: var(--bg);
    border-radius: 8px; padding: 1px 5px;
    font-size: 9px; font-weight: 700;
  }

  #feed {
    grid-area: feed;
    overflow-y: auto; padding: 8px 0;
    display: flex; flex-direction: column;
  }

  .filter-bar {
    padding: 6px 12px; border-bottom: 1px solid var(--border);
    display: flex; gap: 4px; flex-wrap: wrap; background: var(--bg2);
  }

  .filter-btn {
    font-family: inherit; font-size: 10px;
    padding: 2px 7px; border-radius: 3px;
    border: 1px solid var(--border);
    background: transparent; color: var(--muted); cursor: pointer; transition: all 0.1s;
  }
  .filter-btn:hover, .filter-btn.active {
    border-color: var(--accent); color: var(--accent);
    background: rgba(77,166,255,0.08);
  }

  .msg {
    padding: 4px 16px;
    border-left: 2px solid transparent;
    transition: background 0.1s; cursor: pointer; line-height: 1.6;
  }
  .msg:hover { background: var(--bg2); }
  .msg.expanded { background: var(--bg2); border-left-color: var(--accent); }

  .msg-header {
    display: flex; align-items: baseline;
    gap: 8px; flex-wrap: wrap;
  }

  .msg-ts     { color: var(--dim); font-size: 10px; flex-shrink: 0; }
  .msg-from   { color: var(--agent); font-weight: 700; font-size: 11px; }
  .msg-ch     { color: var(--dim); font-size: 10px; }

  .msg-type {
    font-size: 10px; padding: 1px 6px;
    border-radius: 3px; font-weight: 700; letter-spacing: 0.05em;
  }
  .type-task    { background: rgba(77,166,255,0.15); color: var(--task); }
  .type-file    { background: rgba(77,255,170,0.15); color: var(--file); }
  .type-agent   { background: rgba(255,210,77,0.15); color: var(--agent); }
  .type-general { background: rgba(204,136,255,0.15); color: var(--general); }
  .type-error   { background: rgba(255,107,107,0.15); color: var(--err); }

  .msg-body-preview {
    color: var(--muted); font-size: 11px; margin-top: 2px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 600px;
  }

  .msg-detail {
    display: none; margin-top: 8px; padding: 10px;
    background: var(--bg3); border-radius: 4px; border: 1px solid var(--border);
    font-size: 11px; white-space: pre-wrap; word-break: break-all;
    color: var(--text); max-height: 300px; overflow-y: auto;
  }
  .msg.expanded .msg-detail { display: block; }

  .file-badge {
    display: inline-block; font-size: 10px; color: var(--file);
    padding: 1px 5px; border: 1px solid rgba(77,255,170,0.3);
    border-radius: 3px; margin-top: 3px; margin-right: 4px;
    cursor: pointer; text-decoration: none;
  }
  .file-badge:hover { background: rgba(77,255,170,0.1); }

  .empty-state { padding: 32px 16px; color: var(--dim); text-align: center; line-height: 2; }

  #presence {
    grid-area: presence;
    border-left: 1px solid var(--border);
    background: var(--bg2); overflow-y: auto; padding-bottom: 16px;
  }

  .presence-header {
    padding: 10px 12px 4px; color: var(--dim); font-size: 10px;
    letter-spacing: 0.12em; text-transform: uppercase;
    border-bottom: 1px solid var(--border); margin-bottom: 4px;
  }

  .agent-item { padding: 8px 12px; border-bottom: 1px solid rgba(42,47,54,0.5); }
  .agent-name { color: var(--agent); font-weight: 700; font-size: 11px; }
  .agent-channels { color: var(--dim); font-size: 10px; margin-top: 2px; }
  .agent-dot {
    display: inline-block; width: 6px; height: 6px;
    border-radius: 50%; background: var(--file); margin-right: 5px; vertical-align: middle;
  }

  #compose {
    grid-area: compose;
    border-top: 1px solid var(--border);
    background: var(--bg2);
    display: flex; align-items: center; gap: 8px; padding: 0 12px;
  }

  #compose input, #compose select, #compose .type-input {
    font-family: inherit; font-size: 11px;
    background: var(--bg3); border: 1px solid var(--border);
    color: var(--text); padding: 4px 8px; border-radius: 3px; outline: none;
  }
  #compose input:focus, #compose select:focus { border-color: var(--accent); }
  #compose input[name=from]  { width: 110px; }
  #compose select[name=ch]   { width: 80px; }
  #compose input[name=mtype] { width: 90px; }
  #compose input[name=body]  { flex: 1; }

  #compose button {
    font-family: inherit; font-size: 11px;
    background: var(--accent); color: var(--bg);
    border: none; padding: 4px 12px; border-radius: 3px; cursor: pointer; font-weight: 700;
  }

  ::-webkit-scrollbar { width: 4px; }
  ::-webkit-scrollbar-track { background: transparent; }
  ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 2px; }

  /* task panel overlay */
  #task-overlay {
    display: none; position: fixed; inset: 0;
    background: rgba(0,0,0,0.6); z-index: 10;
    align-items: center; justify-content: center;
  }
  #task-overlay.open { display: flex; }

  #task-modal {
    background: var(--bg2); border: 1px solid var(--border);
    border-radius: 6px; padding: 20px; width: 420px; display: flex;
    flex-direction: column; gap: 10px;
  }
  #task-modal h3 { color: var(--bright); font-size: 13px; }

  #task-modal input, #task-modal textarea, #task-modal select {
    font-family: inherit; font-size: 11px;
    background: var(--bg3); border: 1px solid var(--border);
    color: var(--text); padding: 6px 8px; border-radius: 3px; outline: none; width: 100%;
  }
  #task-modal input:focus, #task-modal textarea:focus { border-color: var(--accent); }
  #task-modal textarea { height: 60px; resize: vertical; }

  #task-modal .row { display: flex; gap: 8px; }
  #task-modal .row input { flex: 1; }

  #task-modal .actions { display: flex; gap: 8px; align-items: center; }
  #task-modal .actions button {
    font-family: inherit; font-size: 11px;
    background: var(--task); color: var(--bg);
    border: none; padding: 5px 14px; border-radius: 3px; cursor: pointer; font-weight: 700;
  }
  #task-modal .actions .cancel {
    background: transparent; color: var(--muted); border: 1px solid var(--border);
  }
  #task-modal .err { color: var(--err); font-size: 10px; }

  /* reconnect notice */
  #reconnect-notice {
    display: none; position: fixed; bottom: 60px; left: 50%; transform: translateX(-50%);
    background: var(--bg3); border: 1px solid var(--err); border-radius: 4px;
    color: var(--err); font-size: 11px; padding: 6px 14px; z-index: 20;
  }
  #reconnect-notice.show { display: block; }
</style>
</head>
<body>

<header>
  <div class='logo'>work<span>shop</span></div>
  <div class='status-dot' id='dot'></div>
  <div id='hstats' class='header-stats'>connecting...</div>
  <div class='header-right'>
    <button class='btn' onclick='openTaskPanel()'>+ task</button>
  </div>
</header>

<div id='sidebar'>
  <div class='sidebar-section'>channels</div>
  <div class='ch-list' id='chlist'>
    <div class='ch-item active' data-ch='*' onclick='switchCh(this)'>✦ all channels</div>
  </div>
</div>

<div id='feed'>
  <div class='filter-bar' id='filterbar'>
    <button class='filter-btn active' onclick='setFilter(this,null)'>all</button>
    <button class='filter-btn' onclick='setFilter(this,\"task\")'>task.*</button>
    <button class='filter-btn' onclick='setFilter(this,\"file\")'>file.*</button>
    <button class='filter-btn' onclick='setFilter(this,\"agent\")'>agent.*</button>
    <button class='filter-btn' onclick='setFilter(this,\"msg\")'>msg.*</button>
  </div>
  <div id='msgs'><div class='empty-state'>⬡ loading...</div></div>
</div>

<div id='compose'>
  <input type='text' name='from'  placeholder='from'    value='human.you'>
  <select name='ch'></select>
  <input type='text' name='mtype' placeholder='type'    value='msg.human'>
  <input type='text' name='body'  placeholder='message (JSON body or plain text)...'
         onkeydown='if(event.key===\"Enter\")composeSend()'>
  <button onclick='composeSend()'>send</button>
</div>

<div id='presence'>
  <div class='presence-header'>agents online</div>
  <div id='agentlist'></div>
</div>

<div id='task-overlay' onclick='e=>e.target===this&&closeTaskPanel()'>
  <div id='task-modal'>
    <h3>new task</h3>
    <input  type='text' id='t-title'   placeholder='title (required)'>
    <textarea           id='t-context' placeholder='context (optional JSON)'></textarea>
    <div class='row'>
      <input type='text' id='t-ch'  placeholder='channel' value='tasks'>
      <input type='text' id='t-for' placeholder='for agent (optional)'>
    </div>
    <div class='actions'>
      <button onclick='createTask()'>create</button>
      <button class='cancel' onclick='closeTaskPanel()'>cancel</button>
      <span class='err' id='task-err'></span>
    </div>
  </div>
</div>

<div id='reconnect-notice'>⚡ reconnecting...</div>

<script>
const BASE = window.location.origin;

// ── state ──
let currentCh  = '*';
let typeFilter = null;
let allMessages = [];
const seenIds   = new Set();      // global dedup — prevents duplicate renders on reconnect
let channels    = new Set(['general', 'tasks']);
let unread      = {};
let eventSource = null;
let reconnectTimer = null;

// ── type styling ──
function typeClass(type) {
  if (!type) return 'type-general';
  const ns = type.split('.')[0];
  return {task:'type-task', file:'type-file', agent:'type-agent',
          error:'type-error', msg:'type-general'}[ns] || 'type-general';
}

function fmtTs(ts) {
  return new Date(ts * 1000).toLocaleTimeString('en',
    {hour12:false, hour:'2-digit', minute:'2-digit', second:'2-digit'});
}

function fmtBody(body) {
  if (!body || Object.keys(body).length === 0) return '';
  return JSON.stringify(body).slice(0, 140);
}

// ── rendering ──
function renderMsg(m) {
  const div = document.createElement('div');
  div.className = 'msg';
  div.id = 'msg-' + m.id;
  div.onclick = () => div.classList.toggle('expanded');
  const fileBadges = (m.files||[]).map(h =>
    `<a class='file-badge' href='${BASE}/files/${h}' target='_blank'
        onclick='event.stopPropagation()'>⬡ ${h.slice(0,20)}...</a>`
  ).join('');
  div.innerHTML = `
    <div class='msg-header'>
      <span class='msg-ts'>${fmtTs(m.ts)}</span>
      <span class='msg-from'>${m.from || '?'}</span>
      ${currentCh === '*' ? `<span class='msg-ch'>#${m.ch}</span>` : ''}
      <span class='msg-type ${typeClass(m.type)}'>${m.type || 'msg'}</span>
    </div>
    <div class='msg-body-preview'>${fmtBody(m.body)}</div>
    ${fileBadges ? `<div style='margin-top:3px'>${fileBadges}</div>` : ''}
    <div class='msg-detail'>${JSON.stringify(m, null, 2)}</div>
  `;
  return div;
}

function addMsg(m) {
  // Global dedup — safe across history loads, SSE reconnects, and channel switches
  if (seenIds.has(m.id)) return;
  seenIds.add(m.id);

  // Track in memory buffer
  allMessages.push(m);
  // Keep buffer bounded — remove oldest 500 when hitting 2000
  if (allMessages.length > 2000) {
    const removed = allMessages.splice(0, 500);
    removed.forEach(old => seenIds.delete(old.id));
  }

  // Auto-add new channels to sidebar
  if (m.ch && !channels.has(m.ch)) {
    channels.add(m.ch);
    updateSidebar();
    populateComposeChannels();
  }

  // Only render if matches current view
  if (typeFilter && !m.type?.startsWith(typeFilter)) return;
  if (currentCh !== '*' && m.ch !== currentCh) {
    unread[m.ch] = (unread[m.ch] || 0) + 1;
    updateSidebar();
    return;
  }

  const container = document.getElementById('msgs');
  const empty = container.querySelector('.empty-state');
  if (empty) empty.remove();

  container.appendChild(renderMsg(m));

  // Limit DOM to 500 nodes
  const nodes = container.querySelectorAll('.msg');
  if (nodes.length > 500) nodes[0].remove();

  // Auto-scroll if near bottom
  const feed = document.getElementById('feed');
  if (feed.scrollHeight - feed.scrollTop < feed.clientHeight + 100)
    feed.scrollTop = feed.scrollHeight;
}

// ── SSE connection ──
function connectSSE() {
  if (eventSource) { eventSource.close(); eventSource = null; }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }

  const lastId = allMessages.length > 0 ? allMessages[allMessages.length - 1].id : null;
  const url = currentCh === '*' ? `${BASE}/` : `${BASE}/ch/${currentCh}`;
  // Browser automatically appends Last-Event-ID header if we've received events with id: fields
  eventSource = new EventSource(url);

  eventSource.onopen = () => {
    document.getElementById('dot').className = 'status-dot';
    document.getElementById('reconnect-notice').className = '';
  };

  eventSource.onmessage = e => {
    try { addMsg(JSON.parse(e.data)); } catch(err) {}
  };

  eventSource.onerror = () => {
    document.getElementById('dot').className = 'status-dot err';
    document.getElementById('reconnect-notice').className = 'reconnect-notice show';
    eventSource.close();
    eventSource = null;
    reconnectTimer = setTimeout(connectSSE, 3000);
  };
}

// ── sidebar ──
function updateSidebar() {
  const list   = document.getElementById('chlist');
  const allBtn = list.querySelector('[data-ch=\"*\"]');
  list.innerHTML = '';
  list.appendChild(allBtn);
  [...channels].sort().forEach(ch => {
    const d = document.createElement('div');
    d.className = 'ch-item' + (currentCh === ch ? ' active' : '');
    d.dataset.ch = ch;
    d.onclick = () => switchCh(d);
    d.textContent = '# ' + ch;
    if (unread[ch]) {
      const badge = document.createElement('span');
      badge.className = 'unread';
      badge.textContent = unread[ch];
      d.appendChild(badge);
    }
    list.appendChild(d);
  });
}

function switchCh(el) {
  document.querySelectorAll('.ch-item').forEach(e => e.classList.remove('active'));
  el.classList.add('active');
  currentCh = el.dataset.ch;
  delete unread[currentCh];
  updateSidebar();
  // Re-render feed from buffer (seenIds ensures no duplicates)
  document.getElementById('msgs').innerHTML = '';
  const toShow = currentCh === '*' ? allMessages : allMessages.filter(m => m.ch === currentCh);
  // Temporarily bypass seenIds for re-render (they're already in buffer, just not in DOM)
  toShow.slice(-200).forEach(m => {
    if (typeFilter && !m.type?.startsWith(typeFilter)) return;
    const container = document.getElementById('msgs');
    const empty = container.querySelector('.empty-state');
    if (empty) empty.remove();
    container.appendChild(renderMsg(m));
  });
  connectSSE();
  populateComposeChannels();
}

function setFilter(btn, f) {
  typeFilter = f;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  document.getElementById('msgs').innerHTML = '';
  const msgs = currentCh === '*' ? allMessages : allMessages.filter(m => m.ch === currentCh);
  msgs.slice(-200).forEach(m => {
    if (typeFilter && !m.type?.startsWith(typeFilter)) return;
    const container = document.getElementById('msgs');
    const empty = container.querySelector('.empty-state');
    if (empty) empty.remove();
    container.appendChild(renderMsg(m));
  });
}

// ── compose ──
function populateComposeChannels() {
  const sel = document.querySelector('#compose select[name=ch]');
  const cur = sel.value;
  sel.innerHTML = '';
  [...channels].sort().forEach(ch => {
    const opt = document.createElement('option');
    opt.value = ch; opt.textContent = ch;
    if (ch === cur || (!cur && ch === 'general')) opt.selected = true;
    sel.appendChild(opt);
  });
}

async function composeSend() {
  const from  = document.querySelector('#compose input[name=from]').value  || 'human.you';
  const ch    = document.querySelector('#compose select[name=ch]').value;
  const mtype = document.querySelector('#compose input[name=mtype]').value || 'msg.human';
  const raw   = document.querySelector('#compose input[name=body]').value;
  if (!raw.trim()) return;

  // Try to parse body as JSON, otherwise wrap as {text: ...}
  let body;
  try { body = JSON.parse(raw); }
  catch(e) { body = {text: raw}; }

  try {
    await fetch(`${BASE}/ch/${ch}`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({from, type: mtype, body})
    });
    document.querySelector('#compose input[name=body]').value = '';
  } catch(e) { console.error('send failed:', e); }
}

// ── task panel ──
function openTaskPanel()  { document.getElementById('task-overlay').className = 'open'; }
function closeTaskPanel() { document.getElementById('task-overlay').className = ''; }

async function createTask() {
  const title   = document.getElementById('t-title').value.trim();
  const ctxRaw  = document.getElementById('t-context').value.trim();
  const ch      = document.getElementById('t-ch').value.trim() || 'tasks';
  const forWho  = document.getElementById('t-for').value.trim();
  const errEl   = document.getElementById('task-err');
  errEl.textContent = '';

  if (!title) { errEl.textContent = 'title required'; return; }

  let context = {};
  if (ctxRaw) {
    try { context = JSON.parse(ctxRaw); }
    catch(e) { errEl.textContent = 'context must be valid JSON'; return; }
  }

  const from = document.querySelector('#compose input[name=from]').value || 'human.you';
  const payload = {from, title, context, ch};
  if (forWho) payload.for = forWho;

  try {
    const r = await fetch(`${BASE}/tasks`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    if (!r.ok) throw new Error((await r.json()).error);
    document.getElementById('t-title').value   = '';
    document.getElementById('t-context').value = '';
    document.getElementById('t-for').value     = '';
    closeTaskPanel();
  } catch(e) { errEl.textContent = e.message; }
}

// ── presence ──
async function refreshPresence() {
  try {
    const agents = await (await fetch(`${BASE}/presence`)).json();
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
    const s = await (await fetch(`${BASE}/status`)).json();
    document.getElementById('hstats').textContent =
      `${s.messages} msgs · ${s.tasks} tasks · ${s['agents-live']} agents · ${s.subscribers} subs`;
  } catch(e) {}
}

// ── init ──
async function loadHistory() {
  try {
    // Fetch cross-channel history from the global endpoint
    const r    = await fetch(`${BASE}/history?n=100`);
    const text = await r.text();
    text.trim().split('\\n').filter(Boolean).forEach(line => {
      try { addMsg(JSON.parse(line)); } catch(e) {}
    });
    // Also discover any channels that appeared only in the DB
    const chans = await (await fetch(`${BASE}/channels`)).json();
    chans.forEach(ch => channels.add(ch));
  } catch(e) { console.warn('history load failed:', e); }
}

(async () => {
  await loadHistory();
  updateSidebar();
  populateComposeChannels();

  const empty = document.getElementById('msgs').querySelector('.empty-state');
  if (empty) empty.textContent = '⬡ waiting for messages...';

  connectSSE();
  refreshPresence();
  refreshStatus();

  setInterval(refreshPresence, 15000);
  setInterval(refreshStatus,   30000);
})();
</script>
</body>
</html>")

(defn handle-ui [_req]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    ui-html})

;; ─────────────────────────────────────────────
;; ROUTER
;; ─────────────────────────────────────────────

(defn route [req]
  (let [method (:request-method req)
        uri    (:uri req)
        path   (str/replace uri #"\?.*" "")
        parts  (str/split path #"/")]
    (try
      (cond
        ;; ── global ──
        (= [:get "/"]        [method path]) (handle-stream req :all)
        (= [:head "/"]      [method path]) (handle-stream req :all)
        (= [:get "/ui"]      [method path]) (handle-ui req)
        (= [:get "/status"]  [method path]) (handle-status req)
        (= [:get "/history"] [method path]) (handle-global-history req)
        (= [:get "/channels"] [method path]) (handle-channels req)

        ;; ── presence ──
        (= [:post "/presence"] [method path]) (handle-presence-heartbeat! req)
        (= [:get  "/presence"] [method path]) (handle-presence-list req)

        ;; ── channels ──
        (and (= method :post) (re-matches #"/ch/[^/]+" path))
        (handle-publish! req (get parts 2))

        (and (= method :get) (re-matches #"/ch/[^/]+/history" path))
        (handle-history req (get parts 2))

        (and (= method :get) (re-matches #"/ch/[^/]+" path))
        (handle-stream req (get parts 2))

        (and (= method :get) (re-matches #"/ch/[^/]+/history" path))
        (handle-history req (get parts 2))

        ;; ── tasks ──
        (= [:post "/tasks"] [method path]) (handle-task-create! req)
        (= [:get  "/tasks"] [method path]) (handle-task-list req)

        (and (= method :get)  (re-matches #"/tasks/[^/]+" path))
        (handle-task-get req (get parts 2))

        (and (= method :post) (re-matches #"/tasks/[^/]+/claim"     path))
        (handle-task-claim! req (get parts 2))

        (and (= method :post) (re-matches #"/tasks/[^/]+/update"    path))
        (handle-task-update! req (get parts 2))

        (and (= method :post) (re-matches #"/tasks/[^/]+/done"      path))
        (handle-task-done! req (get parts 2))

        (and (= method :post) (re-matches #"/tasks/[^/]+/abandon"   path))
        (handle-task-abandon! req (get parts 2))

        (and (= method :post) (re-matches #"/tasks/[^/]+/interrupt" path))
        (handle-task-interrupt! req (get parts 2))

        ;; ── files ──
        (= [:post "/files"] [method path]) (handle-file-upload! req)

        (and (= method :get) (re-matches #"/files/.+" path))
        (handle-file-fetch req (get parts 2))

        ;; ── CORS preflight ──
        (= method :options)
        {:status 204
         :headers {"Access-Control-Allow-Origin"  "*"
                   "Access-Control-Allow-Methods" "GET,HEAD,POST,OPTIONS"
                   "Access-Control-Allow-Headers" "Content-Type,Last-Event-ID"
                   "Access-Control-Max-Age"       "86400"}}

        :else (not-found "not found"))

      (catch Exception e
        (let [{:keys [status]} (ex-data e)
              msg (.getMessage e)]
          (when verbose? (println "error" path msg))
          (json-resp (or status 500) {:error msg}))))))

;; ─────────────────────────────────────────────
;; LOGGING MIDDLEWARE
;; ─────────────────────────────────────────────

(defn wrap-logging [handler]
  (fn [req]
    (let [start (System/currentTimeMillis)
          resp  (handler req)]
      (when verbose?
        (let [duration (- (System/currentTimeMillis) start)]
          (println (format "[%s] %s %s → %d (%dms)"
                           (java.time.Instant/now)
                           (str/upper-case (name (:request-method req)))
                           (:uri req)
                           (:status resp 0)
                           duration))))
      resp)))

;; ─────────────────────────────────────────────
;; MAIN
;; ─────────────────────────────────────────────

(defonce server (atom nil))

(defn shutdown! []
  (println "\nshutting down...")
  (when @server
    ;; http-kit stop fn is zero-arity
    (@server))
  (System/exit 0))

(defn -main []
  (init-db!)
  ;; Run cleanup once at startup (removes messages older than retention-days)
  (cleanup!)
  (.mkdirs (io/file blobs-dir))
  (sse-keepalive!)
  (start-cleanup!)

  (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

  (reset! server
          (http/run-server (wrap-logging route) {:port port :thread 16}))

  (println (str "workshop running on :" port))
  (println (str "  ui     → http://localhost:" port "/ui"))
  (println (str "  stream → curl -N http://localhost:" port "/ | jq ."))
  (println (str "  status → curl http://localhost:" port "/status | jq ."))

  @(promise)) ;; block main thread forever; shutdown hook handles SIGTERM

(-main)
