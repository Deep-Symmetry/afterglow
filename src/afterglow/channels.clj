(ns
    ^{:doc "Functions for modeling DMX channels"
      :author "James Elliott"}
  afterglow.channels
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre :refer [error info debug]]))

(defn channel [offset]
  {:offset offset})

(defn- assign-channel [universe start-address raw-channel]
  (assoc raw-channel :address (+ (:offset raw-channel) (dec start-address)) :universe universe))

(defn patch-fixture
  "Assign a fixture to a DMX universe and starting channel; resolves all of its channel assignments."
  [fixture universe start-address]
  (let [assigner (partial assign-channel universe start-address)]
    (update-in fixture [:fixture :channels] #(map assigner %))))

(defn extract-channels
  "Given a sequence of fixtures, returns the channels matching the specified predicate."
  [fixtures pred]
  (filter pred (mapcat :channels (map :fixture fixtures))))

(defn full-range
  "Returns a range spefication that encompasses all possible DMX values as a single variable setting."
  [range-type label]
  {:start 0
   :end 255
   :type range-type
   :label label})

;; TODO is this a good range data structure for finding which one a value falls into?
(defn fine-channel
  "Defines a channel for which sometimes multi-byte values are desired, via a separate
channel which specifies the fractional value to be added to the main channel."
  [chan-type offset & {:keys [fine-offset range-label]}]
  (let [base (assoc (channel offset)
                    :type chan-type
                    :ranges [(full-range :variable (or range-label (clojure.string/capitalize (name chan-type))))])]
    (if fine-offset
      (assoc base :fine-offset fine-offset)
      base)))


(defn dimmer
  ([offset]
   (dimmer offset nil))
  ([offset fine-offset]
   (fine-channel :dimmer offset :fine-offset fine-offset :range-label "Intensity")))

;; TODO here is where we would add wavelength support once we can figure out the formulas
(defn color
  ([offset kwd]
   (color offset kwd nil))
  ([offset kwd label]
   (assoc (fine-channel :color offset :range-label (or label (clojure.string/capitalize (name kwd))))
          :color kwd)))

(defn pan
  ([offset]
   (pan offset nil))
  ([offset fine-offset]
   (fine-channel :pan offset :fine-offset fine-offset)))

(defn tilt
  ([offset]
   (tilt offset nil))
  ([offset fine-offset]
   (fine-channel :tilt offset :fine-offset fine-offset)))

(defn focus
  ([offset]
   (focus offset nil))
  ([offset fine-offset]
   (fine-channel :focus offset :fine-offset fine-offset)))

(defn iris
  ([offset]
   (iris offset nil))
  ([offset fine-offset]
   (fine-channel :iris offset :fine-offset fine-offset)))

(defn zoom
  ([offset]
   (zoom offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))

(defn frost
  ([offset]
   (frost offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))


;; TODO pan-tilt channels, with multi-byte support

;; TODO control channels

;; TODO gobo wheel and color wheel channels special variants of control channels?
