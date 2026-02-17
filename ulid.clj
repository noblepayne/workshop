;; This implementation is based on clj-ulid
;; https://github.com/theikkila/clj-ulid
;; Licensed under the MIT License
(ns boostbox.ulid
  (:require [clojure.string :as str]))

(def ^:const ^String BASE32_DEFAULT
  "Crockford's Base32 Alphabet."
  "0123456789ABCDEFGHJKMNPQRSTVWXYZ")

(def ^:const base32-value-map
  "Mapping from Base32 characters to their integer values."
  (zipmap BASE32_DEFAULT (range 32)))

(def ^:const value-base32-map
  "Mapping from integer values to Base32 characters."
  (zipmap (range 32) BASE32_DEFAULT))
(def ^:const encoding-length
  "Length of Base32 alphabet."
  (count BASE32_DEFAULT))

(defn encode
  "Encode a non-negative integer to Base32 string of specified length.

   Args:
     n: non-negative integer to encode
     length: desired length of output string (pads with zeros if needed)

   Returns:
     Base32-encoded string"
  {:static true}
  [n length]
  (->
   (reduce
    (fn [state _]
      (let [{:keys [n encoded]} state
            m (rem n encoding-length)
            n-next (/ (- n m) encoding-length)]
        {:encoded (str (get value-base32-map m) encoded)
         :n n-next}))
    {:encoded "" :n (max n 0)}
    (range length))
   :encoded))

(defn decode [s]
  (reduce (fn [acc c]
            (let [v (get base32-value-map c)
                  big-v (java.math.BigInteger. (str v))]
              (-> acc
                  (.shiftLeft 5)
                  (.or big-v))))
          java.math.BigInteger/ZERO
          s))

(defn ulid->bytes [s]
  (.toByteArray (decode s)))

(defn decode-timestamp
  "Decode string to integer with Crockford's Base32"
  {:static true}
  [s]
  (as-> s $
    (reverse $)
    (map (partial get base32-value-map) $)
    (map bit-shift-left $ (iterate (partial + 5) 0))
    (reduce bit-or $)))

(defn ulid->timestamp
  "Get timestamp from ULID"
  [ulid-string]
  (-> ulid-string
      (subs 0 10)
      str/upper-case
      (decode-timestamp)))
