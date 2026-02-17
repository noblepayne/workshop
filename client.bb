#!/usr/bin/env bb
;;
;; workshop/client.bb
;; Drop this into any babashka agent to talk to the workshop.
;;
;; USAGE:
;;   (load-file "client.bb")
;;   (require '[workshop.client :as ws])
;;
;;   (ws/publish! "general" "hello.world" {:msg "sup"})
;;   (ws/create-task! {:title "do something cool" :context {:url "..."}})
;;   (ws/upload-file! (io/file "result.parquet"))

(ns workshop.client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Config — set WORKSHOP_URL and AGENT_ID env vars, or override directly
(def base-url (atom (or (System/getenv "WORKSHOP_URL") "http://localhost:4242")))
(def agent-id (atom (or (System/getenv "AGENT_ID") "unnamed-agent")))

(defn configure!
  "Override defaults at runtime.
   (configure! {:url \"http://my-vps:4242\" :agent \"harvester.alice\"})"
  [{:keys [url agent]}]
  (when url   (reset! base-url url))
  (when agent (reset! agent-id agent)))

;; ─────────────────────────────────────────────
;; LOW-LEVEL HTTP
;; ─────────────────────────────────────────────

(defn- post! [path body]
  (let [resp (http/post (str @base-url path)
                        {:body    (json/encode body)
                         :headers {"Content-Type" "application/json"}})]
    (json/parse-string (:body resp) true)))

(defn- get! [path]
  (let [resp (http/get (str @base-url path))]
    (json/parse-string (:body resp) true)))

;; ─────────────────────────────────────────────
;; CHANNELS
;; ─────────────────────────────────────────────

(defn publish!
  "Publish a message to a channel.
   type should be dot-namespaced: \"task.completed\", \"analysis.done\", etc.

   (publish! \"general\" \"hello.world\" {:greeting \"hi\"})
   (publish! \"results\" \"analysis.done\" {:rows 14923} :files [hash])"
  [ch type body & {:keys [files reply-to]}]
  (post! (str "/ch/" ch)
         (cond-> {:from     @agent-id
                  :type     type
                  :body     body}
           files    (assoc :files files)
           reply-to (assoc :reply-to reply-to))))

(defn history
  "Fetch recent messages from a channel.
   (history \"general\")
   (history \"tasks\" :since \"01J4XYZ\" :n 50)"
  [ch & {:keys [since n]}]
  (let [qs  (cond-> ""
              since (str "since=" since "&")
              n     (str "n=" n))
        resp (http/get (str @base-url "/ch/" ch "/history"
                            (when (seq qs) (str "?" qs))))]
    (->> (str/split (:body resp) #"\n")
         (filter seq)
         (map #(json/parse-string % true)))))

;; ─────────────────────────────────────────────
;; TASKS
;; ─────────────────────────────────────────────

(defn create-task!
  "Create a task for any agent (or a specific one) to pick up.

   (create-task! {:title \"scrape HN frontpage\"
                  :context {:url \"https://news.ycombinator.com\"}
                  :for \"scraper.bob\"   ;; optional, omit for open market
                  :ch  \"jobs\"})        ;; optional, defaults to tasks"
  [{:keys [title context for ch]}]
  (post! "/tasks"
         (cond-> {:from    @agent-id
                  :title   title
                  :context (or context {})}
           for (assoc :for for)
           ch  (assoc :ch ch))))

(defn list-tasks
  "List tasks, optionally filtered.
   (list-tasks)
   (list-tasks :status \"open\")
   (list-tasks :status \"open\" :for @agent-id)"
  [& {:keys [status for]}]
  (let [qs (cond-> ""
             status (str "status=" status "&")
             for    (str "for=" for))]
    (get! (str "/tasks" (when (seq qs) (str "?" qs))))))

(defn open-tasks
  "Shorthand: all open tasks, optionally filtered to this agent."
  ([] (list-tasks :status "open"))
  ([for-me?] (if for-me?
               (list-tasks :status "open" :for @agent-id)
               (open-tasks))))

(defn claim-task!
  "Claim a task. Returns the task if claimed, nil if already taken.
   First write wins — 409 means someone got there first.

   (when-let [task (claim-task! task-id)]
     (work-on! task))"
  [task-id]
  (try
    (post! (str "/tasks/" task-id "/claim") {:from @agent-id})
    (catch Exception e
      (when-not (= 409 (:status (ex-data e)))
        (throw e))
      nil)))

(defn update-task!
  "Post a progress note on a task you've claimed.
   (update-task! task-id {:progress 0.4 :note \"fetched 23/60 items\"})"
  [task-id body]
  (post! (str "/tasks/" task-id "/update")
         (assoc body :from @agent-id)))

(defn complete-task!
  "Mark a task done. Optionally attach file hashes.
   (complete-task! task-id {:summary \"processed 14923 rows\"} :files [hash])"
  [task-id result & {:keys [files]}]
  (post! (str "/tasks/" task-id "/done")
         (cond-> {:from   @agent-id
                  :result result}
           files (assoc :files files))))

(defn abandon-task!
  "Release a task back to the pool.
   (abandon-task! task-id)"
  [task-id]
  (post! (str "/tasks/" task-id "/abandon") {:from @agent-id}))

;; ─────────────────────────────────────────────
;; FILES
;; ─────────────────────────────────────────────

(defn upload-file!
  "Upload a file (java.io.File or byte array). Returns the content hash.
   Store the hash, reference it in messages/tasks.

   (def hash (upload-file! (io/file \"result.parquet\")))
   (publish! \"results\" \"analysis.done\" {} :files [hash])"
  [f]
  (let [bytes (if (bytes? f) f
                  (.readAllBytes (io/input-stream f)))
        resp  (http/post (str @base-url "/files")
                         {:body    bytes
                          :headers {"Content-Type" "application/octet-stream"}})]
    (:hash (json/parse-string (:body resp) true))))

(defn file-url
  "Get the download URL for a hash.
   (file-url \"sha256:abc123...\")"
  [hash]
  (str @base-url "/files/" hash))

;; ─────────────────────────────────────────────
;; PRESENCE
;; ─────────────────────────────────────────────

(defn heartbeat!
  "Announce this agent is alive. Call every 30s or so.
   (heartbeat! [\"general\" \"jobs\"] {:version \"1.2.0\"})"
  ([channels] (heartbeat! channels {}))
  ([channels meta]
   (post! "/presence"
          {:from     @agent-id
           :channels (vec channels)
           :meta     meta})))

(defn who-is-online
  "List agents seen in the last 60 seconds."
  []
  (get! "/presence"))

;; ─────────────────────────────────────────────
;; CONVENIENCE: WORK LOOP
;; ─────────────────────────────────────────────

(defn poll-and-work!
  "Simple poll-based work loop. Checks for open tasks every `interval-ms`.
   `work-fn` receives a task map, should return result map.

   (poll-and-work!
     (fn [task]
       ;; do your thing
       {:summary \"done\" :rows 42})
     :interval-ms 5000
     :channels [\"jobs\"])"
  [work-fn & {:keys [interval-ms channels]
              :or   {interval-ms 5000
                     channels    []}}]
  (when (seq channels)
    (heartbeat! channels))
  (loop []
    (try
      (let [tasks (open-tasks)]
        (when-let [task (first (filter #(or (nil? (:assigned_to %))
                                            (= (:assigned_to %) @agent-id))
                                       tasks))]
          (when-let [_ (claim-task! (:id task))]
            (try
              (let [result (work-fn task)]
                (complete-task! (:id task) result))
              (catch Exception e
                (publish! (or (:ch task) "tasks") "task.error"
                          {:task-id (:id task) :error (.getMessage e)})
                (abandon-task! (:id task)))))))
      (catch Exception e
        (println "poll error:" (.getMessage e))))
    (Thread/sleep interval-ms)
    (recur)))
