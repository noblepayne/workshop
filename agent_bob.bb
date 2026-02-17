#!/usr/bin/env bb
;; Agent 2: Bob the Worker
(load-file "client.bb")
(require '[workshop.client :as ws])

(ws/configure! {:url "http://localhost:4242" :agent "worker.bob"})

(println "[Bob] Starting worker agent...")

;; Create a few tasks first
(dotimes [i 3]
  (ws/create-task! {:title (str "Process batch " i)
                   :context {:batch-id i :items 100}})
  (println (format "[Bob] Created task %d/3" (inc i))))

;; Now work on open tasks
(dotimes [attempt 8]
  (Thread/sleep 1000)
  (let [tasks (ws/open-tasks)]
    (when (seq tasks)
      (let [task (first tasks)]
        (println (format "[Bob] Found task: %s" (:title task)))
        (try
          (when-let [claimed (ws/claim-task! (:id task))]
            (println (format "[Bob] Claimed task %s, working..." (:id task)))
            (Thread/sleep 2000) ;; Simulate work
            (ws/complete-task! (:id task) {:processed true :items 42})
            (println (format "[Bob] Completed task %s" (:id task))))
          (catch Exception e
            (println (format "[Bob] Could not claim: %s" (.getMessage e)))))))))

(println "[Bob] Done!")
