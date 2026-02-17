#!/usr/bin/env bb
;; Agent 3: Eve the File Sharer
(load-file "client.bb")
(require '[workshop.client :as ws])

(ws/configure! {:url "http://localhost:4242" :agent "analyzer.eve"})

(println "[Eve] Starting file agent...")

;; Create temp files and upload them
(dotimes [i 3]
  (Thread/sleep 2000)
  
  ;; Create a temp file
  (let [temp-file (java.io.File/createTempFile (str "analysis-" i) ".txt")]
    (spit temp-file (str "Analysis Results Batch " i "\n"
                        "Generated: " (java.time.Instant/now) "\n"
                        "Records processed: " (* (inc i) 100) "\n"
                        "Status: SUCCESS\n"))
    
    ;; Upload it
    (let [hash (ws/upload-file! temp-file)]
      (println (format "[Eve] Uploaded file %d/3: %s" (inc i) hash))
      
      ;; Reference in a message
      (ws/publish! "results" "analysis.complete"
                  {:batch i :records (* (inc i) 100)}
                  :files [hash])
      (println (format "[Eve] Published results for batch %d" i)))
    
    ;; Clean up temp file
    (.delete temp-file)))

(println "[Eve] Done!")
