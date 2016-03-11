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

(def float-epsilon
  "The tolerance value for comparing two floating point numbers. If
  the difference between the values is smaller than this, after
  scaling appropriately for very small or very large numbers, they
  will be considered equal."
  0.00001)

(defn- scale
  "Calculate a suitable scale factor for floating point comparison
  tolerance."
  [x y]
  (if (or (zero? x) (zero? y))
    1
    (math/abs x)))

(defn float=
  "Compare two floating point numbers for equality, with a tolerance
  specified by `epsilon`, which defaults to `float-epsilon` if not
  provided."
  ([x y] (float= x y float-epsilon))
  ([x y epsilon] (<= (math/abs (- x y))
                     (* (scale x y) epsilon))))

(defn float<
  "Checks whether `x` is less than `y` with an equality tolerance
  specified by `epsilon`, which defaults to `float-epsilon` if not
  provided."
  ([x y] (float< x y float-epsilon))
  ([x y epsilon] (< x
                    (- y (* (scale x y) epsilon)))))

(defn float>
  "Checks whether `x` is greater than `y` with an equality tolerance
  specified by `epsilon`, which defaults to `float-epsilon` if not
  provided."
  ([x y] (float< y x))
  ([x y epsilon] (float< y x epsilon)))

(defn float<=
  "Checks whether `x` is less than or equal to `y` with an equality
  tolerance specified by `epsilon`, which defaults to `float-epsilon`
  if not provided."
  ([x y] (not (float> x y)))
  ([x y epsilon] (not (float> x y epsilon))))

(defn float>=
  "Checks whether `x` is greater than or equal to `y` with an equality
  tolerance specified by `epsilon`, which defaults to `float-epsilon`
  if not provided."
  ([x y] (not (float< x y)))
  ([x y epsilon] (not (float< x y epsilon))))

(defn normalize-cue-variable-value
  "Given a raw value that has been looked up for a cue variable,
  convert it to the appropriate type based on the variable
  specification."
  [var-spec raw]
  (when (some? raw)
    (case (:type var-spec)
      :boolean (boolean raw)
      :integer (Math/round (double raw))
      :color raw
      (double raw))))
