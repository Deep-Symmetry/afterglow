(ns afterglow.effects.channel
  "Effects pipeline functions for working with individual DMX channels."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :refer [always-active end-immediately]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :refer [ubyte]]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :refer [clamp-percent-float clamp-rgb-int]])
  (:import (afterglow.effects Assigner Effect)))

(defn apply-channel-value
  "A function which sets the DMX buffer value(s) for a channel, supporting fine channels
  as well (in which there is a high and low byte), using any fractional part of the value
  to determine the fine channel if one is present."
  [buffers channel value]
  (when-let [levels (get buffers (:universe channel))]
    (if-let [fine-index (:fine-index channel)]
      (do
        (aset levels (:index channel) (ubyte value))
        (aset levels fine-index (ubyte (math/round (* 255 (- value (int value)))))))
      (aset levels (:index channel) (ubyte (math/round value))))))

(defn build-channel-assigner
  "Returns an assigner which applies the specified assignment function to the supplied channel."
  [channel f]
  (Assigner. :channel (keyword (str "u" (:universe channel) "a" (:address channel))) channel f))

(defn build-raw-channel-assigners
  "Returns a list of assigners which apply a channel assignment
  function to all the supplied channels."
  [channels f]
  (map #(build-channel-assigner % f) channels))

(defn build-fixed-channel-cue
  "Returns an effect which simply assigns a fixed value to all the
  supplied channels. If htp? is true, applies
  highest-takes-precedence (i.e. compares the value to the previous
  assignment for the channel, and lets the highest value remain)."
  [name level channels & {:keys [htp?]}]
  (let [f (if htp?
            (fn [show snapshot target previous-assignment]
              (max level (or (params/resolve-param previous-assignment show snapshot) 0)))
            (fn [show snapshot target previous-assignment] level))
        assigners (build-raw-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn channel-cue
  "Returns an effect which assigns a dynamic value to all the supplied
  channels. If htp? is true, applies highest-takes-precedence (i.e.
  compares the value to the previous assignment for the channel, and
  lets the highest value remain)."
  [name level channels & {:keys [htp?]}]
  {:pre [(some? name) (some? *show*) (sequential? channels)]}
  (params/validate-param-type level Number)
  (let [f (if htp?
            ;; We need to resolve any dynamic parameters at this point so we can apply the
            ;; highest-take-precedence rule.
            (fn [show snapshot target previous-assignment]
              (max (clamp-rgb-int (params/resolve-param level show snapshot))
                   (or (clamp-rgb-int (params/resolve-param previous-assignment show snapshot)) 0)))
            ;; We can defer resolution to the final DMX calculation stage.
            (fn [show snapshot target previous-assignment]
              level))
        assigners (build-raw-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn raw-channel-cue
  "Returns an effect which simply calls a function to obtain the
  current level for all the supplied channels, runs forever, and ends
  immediately when requested."
  [name f channels]
  {:pre [(some? name) (ifn? f) (sequential? channels)]}
  (let [assigners (build-raw-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn channel-assignment-resolver
  "Resolves the assignment of a level to a single DMX channel."
  [show buffers snapshot target assignment _]
  ;; Resolve in case it is frame dynamic
  (let [resolved (clamp-rgb-int (params/resolve-param assignment show snapshot))]
    (apply-channel-value buffers target resolved)))

(defn build-head-function-assigner
  "Returns a function assigner which applies the specified assignment
  function to the channels of the provided head or fixture which
  implement the specified named function. The head or fixture
  must (directly) have a channel implementing the named function."
  [function head assign-f]
  (Assigner. :function (keyword (str "f" (:id head) "-" (name function))) head assign-f))

(defn build-head-function-assigners
  "Returns a list of function assigners type which apply an assignment
  function to the channel of all the supplied heads or fixtures which
  implement the specified named function. Each head or fixture
  must (directly) have a channel implementing the named function."
  [function heads assign-f]
  (map #(build-head-function-assigner function % assign-f) heads))

(defn build-head-parameter-function-assigner
  "Returns an function assigner which applies a parameter to the
  channel of the supplied head or fixture which implements the
  specified named function. If the parameter is not frame-dynamic, it
  gets resolved when creating this assigner. Otherwise, resolution is
  deferred to frame rendering time. The head or fixture
  must (directly) have a channel implementing the named function."
  [function head param show snapshot]
  (let [resolved (params/resolve-unless-frame-dynamic param show snapshot head)]
    (build-head-function-assigner
     function head (fn [show snapshot target previous-assignment] resolved))))

(defn build-head-parameter-function-assigners
  "Returns a list of assigners which apply a parameter to the
  channel(s) of the supplied heads or fixtures which implement the
  specified named function. Each head or fixture must (directly) have
  a channel implementing the named function."
  [function heads param show]
  (let [snapshot (rhythm/metro-snapshot (:metronome show))]
    (map #(build-head-parameter-function-assigner function % param show snapshot) heads)))

(defn find-heads-with-function
  "Returns all heads of the supplied fixtures which have a channel
  that implements the specified function."
  [function fixtures]
  (filter #(some #{(keyword function)} (keys (:function-map %))) (channels/expand-heads fixtures)))

(defn function-cue
  "Returns an effect which assigns a dynamic value to all channels of
  the supplied fixtures or heads which have a range that implements
  the specified function. (Functions are a way for fixtures to use the
  same DMX channel to do multiple things, allocating ranges of values
  to get more dense use from a smaller number of channel
  allocations.)

  If htp? is true, applies highest-takes-precedence (i.e. compares the
  value to the previous assignment for the channels implementing the
  function, and lets the highest value remain).

  If you have multiple effects trying to control different functions
  which use the same channel, you are unlikely to get the results you
  want. Hopefully the fixture designers chose how to share channels
  wisely, avoiding this pitfall."
  [name function level fixtures & {:keys [htp?]}]
  {:pre [(some? *show*) (some? name) (some? function) (sequential? fixtures)]}
  (params/validate-param-type level Number)
  (let [function (keyword function)
        heads (find-heads-with-function function fixtures)
        f (if htp?
            ;; We need to resolve any dynamic parameters at this point so we can apply the
            ;; highest-take-precedence rule.
            (fn [show snapshot target previous-assignment]
              (max (params/resolve-param level show snapshot target)
                   (or (params/resolve-param previous-assignment show snapshot target) 0)))
            ;; We can defer parameter resolution until the final DMX level assignment stage.
            (fn [show snapshot target previous-assignment]
              level))
        assigners (build-head-function-assigners function heads f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn function-value-scaler
  "Converts a named function value from an arbitrary range to a
  percentage along that range. Designed to be partially applied in a
  fixture definition, so [[function-percentage-to-dmx]] can pass the
  value being resolved as the last parameter."
  {:doc/format :markdown}
  [range-min range-max value]
  {:pre [(< range-min range-max)]}
  (if (< value range-min)
    0
    (if (> value range-max)
      100
      (* (- value range-min) (/ 100 (- range-max range-min))))))

(defn function-percentage-to-dmx
  "Given a percentage value and a named function specfication which
  identifies a range of DMX channel values, scales the percentage into
  that range. If the function spec has a value scaler function
  attached to it (via the key `:scale-fn`), call that with the value
  to get the percentage before scaling it to the DMX range."
  {:doc/format :markdown}
  [percent function-spec]
  (let [range (- (:end function-spec) (:start function-spec))
        scaler (:scale-fn function-spec)
        percent (clamp-percent-float (if (ifn? scaler)
                                       (scaler percent)
                                       percent))]
    (math/round (+ (:start function-spec) (* (/ percent 100) range)))))

(defn function-assignment-resolver
  "Resolves the assignment of a level to a named function on a head or
  fixture."
  [show buffers snapshot target assignment target-id]
  ;; Resolve in case it is frame dynamic
  (let [resolved (params/resolve-param assignment show snapshot target)
        target-name (name target-id)
        function-key (keyword (subs target-name (inc (.indexOf target-name "-"))))
        [channel function-spec] (function-key (:function-map target))]
    (apply-channel-value buffers channel (function-percentage-to-dmx resolved function-spec))))
