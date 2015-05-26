(ns afterglow.util
  "Utility functions that are likely to be widely useful"
  {:author "James Elliott"}
  (:require [clojure.math.numeric-tower :as math]))

(defn ubyte
  "Convert small integer to its signed byte equivalent. Necessary for convenient handling of DMX values
  in the range 0-255, since Java does not have unsigned numbers."
  [val]
   (if (>= val 128)
     (byte (- val 256))
     (byte val)))

(defn unsign
  "Convert a signed byte to its unsigned int equivalent, in the range 0-255."
  [val]
  (bit-and val 0xff))

(defn valid-dmx-value?
  "Checks that the supplied value is within the legal range for a DMX
  channel assignment."
  [v]
  (<= 0 v 255))

(defn find-closest-key
  "Finds the key closest to the one specified in a sorted map."
  [sm k]
  (if-let [a (first (rsubseq sm <= k))]
    (let [a (key a)]
      (if (= a k)
        a
        (if-let [b (first (subseq sm >= k))]
          (let [b (key b)]
            (if (< (math/abs (- k b)) (math/abs (- k a)))
              b
              a))
          a)))
    (when-let [found (first (subseq sm >= k))]
      (key found))))
