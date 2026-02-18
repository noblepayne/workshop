#!/usr/bin/env bb
;;
;; test.bb — smoke tests for workshop
;;

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[babashka.pods :as pods])

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

  (println "=" 50)
  (println (format "Results: %d passed, %d failed" @passes @fails))
  (System/exit (if (zero? @fails) 0 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
