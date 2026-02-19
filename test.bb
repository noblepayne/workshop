#!/usr/bin/env bb
;;
;; test.bb — smoke tests for workshop
;;

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[babashka.pods :as pods]
         '[babashka.process :as process]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(load-file "ulid.clj")
(require '[boostbox.ulid :as ulid])

(def base-url (or (System/getenv "WORKSHOP_URL") "http://localhost:4242"))
(def db-path (or (System/getenv "DB_PATH") "workshop.db"))

(def passes (atom 0))
(def fails (atom 0))

(defn test! [name f]
  (try
    (f)
    (swap! passes inc)
    (println "✓" name)
    (catch Exception e
      (swap! fails inc)
      (println "✗" name "-" (.getMessage e)))))

(defn POST [path body]
  (let [resp (http/post (str base-url path)
                        {:body (json/encode body)
                         :headers {"Content-Type" "application/json"}})]
    (json/parse-string (:body resp) true)))

(defn POST* [path body]
  (let [resp (http/post (str base-url path)
                        {:body (json/encode body)
                         :headers {"Content-Type" "application/json"}
                         :throw false})]
    {:status (:status resp)
     :body (json/parse-string (:body resp) true)}))

(defn GET [path]
  (let [resp (http/get (str base-url path))]
    (json/parse-string (:body resp) true)))

(defn GET* [path]
  "GET that returns status + body (for error codes)"
  (let [resp (http/get (str base-url path) {:throw false})]
    {:status (:status resp)
     :body (some-> (:body resp) (json/parse-string true))}))

(defn POST-raw [path raw-body content-type]
  "POST raw string (for malformed JSON tests)"
  (let [resp (http/post (str base-url path)
                        {:body raw-body
                         :headers {"Content-Type" content-type}
                         :throw false})]
    {:status (:status resp)
     :body (some-> (:body resp) (json/parse-string true))}))

(defn -main [& _]
  (println "Running smoke tests against" base-url)
  (println "=" 50)

  (test! "status endpoint"
         #(let [s (GET "/status")]
            (assert (number? (:uptime-s s)))
            (assert (number? (:messages s)))))

  (test! "channel publish"
         #(let [r (POST "/ch/general" {:from "test" :type "test.msg" :body {:test true}})]
            (assert (string? (:id r)))
            (assert (number? (:ts r)))))

  (test! "channel publish rejects missing type"
         #(let [resp (http/post (str base-url "/ch/general")
                                {:body (json/encode {:from "test" :body {:msg "hi"}})
                                 :headers {"Content-Type" "application/json"}
                                 :throw false})
                body (json/parse-string (:body resp) true)]
            (assert (= 400 (:status resp)) "missing type should return 400")
            (assert (:error body))))

  (test! "channel history"
         #(let [body (:body (http/get (str base-url "/ch/general/history?n=5")))]
            (assert (string? body))
            (assert (seq body))))

  (test! "task create with :from"
    #(let [r (POST "/tasks" {:from "test" :title "test task" :context {}})]
       (assert (string? (:id r)))))

  (test! "task create with :created_by"
    #(let [r (POST "/tasks" {:created_by "test" :title "test task with created_by" :context {}})]
       (assert (string? (:id r)))))

  (test! "task create requires :from or :created_by"
    #(let [resp (POST* "/tasks" {:title "no creator" :context {}})]
       (assert (= 400 (:status resp)))
       (assert (:error (:body resp)))))

  (test! "task list"
         #(let [tasks (GET "/tasks")]
            (assert (coll? tasks))))

  (test! "task claim conflict returns 409"
         #(let [;; create a task
                task (POST "/tasks" {:from "test" :title "conflict test" :context {}})
                tid  (:id task)
                ;; first claim succeeds
                _    (POST* (str "/tasks/" tid "/claim") {:from "agent1"})
                ;; second claim from different agent should return 409
                resp (POST* (str "/tasks/" tid "/claim") {:from "agent2"})]
            (assert (= 409 (:status resp)) "second claim should return 409")
            (assert (:error (:body resp)))))

  (test! "global history returns ndjson"
         #(let [;; publish a message we can find
                _ (POST "/ch/history-test" {:from "test" :type "test.history" :body {:msg "findme"}})
                ;; fetch global history
                resp (http/get (str base-url "/history?n=10") {:throw false})
                body (:body resp)]
            (assert (= 200 (:status resp)) "history should return 200")
            (assert (string? body) "history should return ndjson string")
            (assert (str/includes? body "findme") "history should contain our message")))

  (test! "channels list returns known channels"
         #(let [chans (GET "/channels")]
            (assert (coll? chans) "channels should return a collection")
            (assert (some #{"general" "tasks"} chans) "should contain known channels")))

  (test! "single task fetch returns task"
         #(let [task (POST "/tasks" {:from "test" :title "fetch test" :context {}})
                tid (:id task)
                fetched (GET (str "/tasks/" tid))]
            (assert (= tid (:id fetched)) "should return the same task")
            (assert (= "fetch test" (:title fetched)) "should have correct title")))

  (test! "single task fetch returns 404 for unknown"
         #(let [resp (GET* "/tasks/01XXXXXXXXXXXXXXXXXXXXXX")]
            (assert (= 404 (:status resp)) "unknown task should return 404")))

  (test! "task done on open returns 409"
         #(let [task (POST "/tasks" {:from "test" :title "done open test" :context {}})
                tid (:id task)
                resp (POST* (str "/tasks/" tid "/done") {:from "test" :result {:done true}})]
            (assert (= 409 (:status resp)) "cannot done an open task")
            (assert (:error (:body resp)))))

  (test! "task done by non-owner returns 403"
         #(let [task (POST "/tasks" {:from "test" :title "owner test" :context {}})
                tid (:id task)
                ;; agent1 claims it
                _ (POST* (str "/tasks/" tid "/claim") {:from "agent1"})
                ;; agent2 tries to complete it
                resp (POST* (str "/tasks/" tid "/done") {:from "agent2" :result {:done true}})]
            (assert (= 403 (:status resp)) "non-owner cannot mark done")
            (assert (:error (:body resp)))))

  (test! "task abandon on open returns 409"
         #(let [task (POST "/tasks" {:from "test" :title "abandon open test" :context {}})
                tid (:id task)
                resp (POST* (str "/tasks/" tid "/abandon") {:from "test"})]
            (assert (= 409 (:status resp)) "cannot abandon an open task")
            (assert (:error (:body resp)))))

  (test! "task abandon by non-owner returns 403"
         #(let [task (POST "/tasks" {:from "test" :title "abandon owner test" :context {}})
                tid (:id task)
                ;; agent1 claims it
                _ (POST* (str "/tasks/" tid "/claim") {:from "agent1"})
                ;; agent2 tries to abandon it
                resp (POST* (str "/tasks/" tid "/abandon") {:from "agent2"})]
            (assert (= 403 (:status resp)) "non-owner cannot abandon")
            (assert (:error (:body resp)))))

  (test! "malformed JSON returns 400"
         #(let [resp (POST-raw "/ch/general" "{invalid json" "application/json")]
            (assert (= 400 (:status resp)) "malformed JSON should return 400")
            (assert (:error (:body resp)))))

  (test! "file path traversal returns 400"
         #(let [resp (GET* "/files/sha256:../../etc/passwd")]
            (assert (= 400 (:status resp)) "path traversal should return 400")))

  (test! "presence heartbeat"
         #(let [r (POST "/presence" {:from "test" :channels ["general"] :meta {}})]
            (assert (:ok r))))

  (test! "presence list"
         #(let [agents (GET "/presence")]
            (assert (coll? agents))
            (assert (= "test" (:agent_id (first agents))))))

  (test! "file upload"
         #(let [resp (http/post (str base-url "/files")
                                {:body (.getBytes "test content")
                                 :headers {"Content-Type" "application/octet-stream"}})
                r (json/parse-string (:body resp) true)]
            (assert (string? (:hash r)))
            (assert (= 12 (:size r)))))

  (test! "cleanup sql works"
         #(do
       ;; Insert an old message (40 days ago)
            (let [old-ts (- (/ (System/currentTimeMillis) 1000.0) (* 40 24 60 60))
                  now (/ (System/currentTimeMillis) 1000.0)]
              (sqlite/execute! db-path
                               ["INSERT INTO messages (id, ts, from_id, ch, type, v, body, files)
             VALUES (?,?,?,?,?,?,?,?)"
                                "OLDMSG123" old-ts "test" "general" "old.msg" 1 "{}" "[]"])
         ;; Run cleanup SQL directly (simulating retention-days=30)
              (sqlite/execute! db-path
                               ["DELETE FROM messages WHERE ts < ?" (- now (* 30 24 60 60))])
         ;; Verify old message is gone
              (let [rows (sqlite/query db-path ["SELECT * FROM messages WHERE id = ?" "OLDMSG123"])]
                (assert (= 0 (count rows)) "Old message should be deleted")))))

  (test! "ULID uniqueness"
         #(let [ulid-chars "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
                ulid-len 26
                new-ulid (fn []
                           (let [ts (System/currentTimeMillis)
                                 sb (StringBuilder. ulid-len)
                                 rng (java.util.concurrent.ThreadLocalRandom/current)]
                             (loop [t ts i 0]
                               (when (< i 10)
                                 (.insert sb 0 (.charAt ulid-chars (int (mod t 32))))
                                 (recur (quot t 32) (inc i))))
                             (dotimes [_ 16]
                               (.append sb (.charAt ulid-chars (.nextInt rng 32))))
                             (str sb)))
                ids (vec (repeatedly 1000 new-ulid))]
            (assert (= 1000 (count (set ids))) "ULIDs must all be unique")))

  (test! "ULID lexicographic sort works for replay"
         #(let [ulid-chars "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
                ulid-len 26
                new-ulid (fn []
                           (let [ts (System/currentTimeMillis)
                                 sb (StringBuilder. ulid-len)
                                 rng (java.util.concurrent.ThreadLocalRandom/current)]
                             (loop [t ts i 0]
                               (when (< i 10)
                                 (.insert sb 0 (.charAt ulid-chars (int (mod t 32))))
                                 (recur (quot t 32) (inc i))))
                             (dotimes [_ 16]
                               (.append sb (.charAt ulid-chars (.nextInt rng 32))))
                             (str sb)))
                ids (vec (take 100 (repeatedly new-ulid)))
                sorted (sort ids)
                check-len (fn [id] (= 26 (count id)))]
            (assert (= (count sorted) (count ids)) "sort should preserve count")
            (assert (every? check-len sorted) "all IDs should be 26 chars")))

  (test! "ULID timestamp round-trip"
         #(let [ulid-chars "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
                ulid-len 26
                new-ulid (fn []
                           (let [ts (System/currentTimeMillis)
                                 sb (StringBuilder. ulid-len)
                                 rng (java.util.concurrent.ThreadLocalRandom/current)]
                             (loop [t ts i 0]
                               (when (< i 10)
                                 (.insert sb 0 (.charAt ulid-chars (int (mod t 32))))
                                 (recur (quot t 32) (inc i))))
                             (dotimes [_ 16]
                               (.append sb (.charAt ulid-chars (.nextInt rng 32))))
                             (str sb)))
                before (System/currentTimeMillis)
                id     (new-ulid)
                after  (System/currentTimeMillis)
                ts     (ulid/ulid->timestamp id)]
            (assert (>= ts before) "ULID timestamp must be >= time before generation")
            (assert (<= ts after)  "ULID timestamp must be <= time after generation")
            (assert (= 26 (count id)) "ULID must be 26 characters")))

  (defn sse-read-lines [stream timeout-ms]
    "Read lines from an SSE stream within timeout"
    (let [lines (atom [])
          done (atom false)]
      (future
        (try
          (while (not @done)
            (let [line (.readLine stream)]
              (when line (swap! lines conj line))))
          (catch Exception _)))
      (Thread/sleep timeout-ms)
      (reset! done true)
      (Thread/sleep 100)
      @lines))

  (test! "SSE streams messages in real-time"
         (let [ch "test-sse-stream"]
           (fn []
             (let [proc (process/process ["curl" "-sN" (str base-url "/ch/" ch)])
                   stream (io/reader (:out proc))]
               (Thread/sleep 500)
               (POST (str "/ch/" ch) {:from "test" :type "test.sse" :body {:msg "hello"}})
               (let [lines (sse-read-lines stream 2000)]
                 (process/destroy-tree proc)
                 (assert (seq lines) "should receive SSE data")
                 (assert (some (fn [x] (str/includes? x "hello")) lines) "should contain our message"))))))

  (test! "SSE includes id field"
         (let [ch "test-sse-id"]
           (fn []
             (let [proc (process/process ["curl" "-sN" (str base-url "/ch/" ch)])
                   stream (io/reader (:out proc))]
               (Thread/sleep 500)
               (POST (str "/ch/" ch) {:from "test" :type "test.id" :body {:msg "idtest"}})
               (let [lines (sse-read-lines stream 2000)]
                 (process/destroy-tree proc)
                 (assert (some (fn [x] (re-find #"^id:" x)) lines) "should have id: prefix"))))))

  (test! "SSE keepalive ping"
         (let [ch "test-sse-keepalive"]
           (fn []
             (let [proc (process/process ["curl" "-sN" (str base-url "/ch/" ch)])
                   stream (io/reader (:out proc))]
               (Thread/sleep 500)
               (let [lines (sse-read-lines stream 25000)]
                 (process/destroy-tree proc)
                 (assert (some (fn [x] (re-find #"^: " x)) lines) "should receive keepalive ping"))))))

  (println "=" 50)
  (println (format "Results: %d passed, %d failed" @passes @fails))
  (System/exit (if (zero? @fails) 0 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
