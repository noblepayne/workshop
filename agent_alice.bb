#!/usr/bin/env bb
;; Agent 1: Alice the Publisher
(load-file "client.bb")
(require '[workshop.client :as ws])

(ws/configure! {:url "http://localhost:4242" :agent "publisher.alice"})

(println "[Alice] Starting publisher agent...")
(dotimes [i 5]
  (Thread/sleep 1500)
  (ws/publish! "general" "chat.message" {:msg (str "Hello from Alice! Message " (inc i))})
  (println (format "[Alice] Published message %d/5" (inc i))))

(println "[Alice] Done!")
