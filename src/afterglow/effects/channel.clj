(ns afterglow.effects.channel
  (:require [afterglow.channels :as channels]
            [afterglow.util :refer [ubyte]]
            [afterglow.effects.util :refer :all]
            [taoensso.timbre :as timbre :refer [error warn info debug]]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile]])
  (:import [afterglow.effects.util Assigner Effect]))

(defn apply-channel-value
  "A function which sets the DMX buffer value(s) for a channel, supporting fine channels
  as well (in which there is a high and low byte), using any fractional part of the value
  to determine the fine channel if one is present."
  [buffers channel value]
    (when-let [levels (get buffers (:universe channel))]
    (aset levels (:index channel) (ubyte value))
    (when-let [fine-index (:fine-index channel)]
      (aset levels fine-index (ubyte (Math/round (* 255 (- value (int value)))))))))

(defn build-channel-assigner
  "Returns an assigner which applies the specified assignment function to the supplied channel."
  [channel f]
  (Assigner. :channel (keyword (str "u" (:universe channel) "a" (:address channel))) channel f))

(defn build-channel-assigners
  "Returns a list of assigners which apply a channel assignment function to all the supplied channels."
  [channels f]
  (map #(build-channel-assigner % f) channels))

(defn build-fixed-channel-cue
  "Returns an effect which simply assigns a fixed value to all the supplied channels. If htp?
is true, applies highest-takes-precedence (i.e. compares the value to the previous assignment
  for the channel, and lets the highest value remain)."
  [name level channels htp?]
  (let [f (if htp?
            (fn [show snapshot target previous-assignment] (max level (or previous-assignment 0)))
            (fn [show snapshot target previous-assignment] level))
        assigners (build-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn build-simple-channel-cue
  "Returns an effect which simply calls a function to obtain the current level for all the supplied channels,
  runs forever, and ends immediately when requested."
  [name f channels]
  (let [assigners (build-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn channel-assignment-resolver
  "Resolves the assignmnet of a level to a single DMX channel."
  [show buffers target assignment]
  (apply-channel-value buffers target assignment))
