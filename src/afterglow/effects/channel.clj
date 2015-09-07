(ns afterglow.effects.channel
  "Effects pipeline functions for working with individual DMX channels."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.controllers :as ct]
            [afterglow.effects.params :as params]
            [afterglow.effects :as fx :refer [always-active end-immediately]]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :refer [ubyte]]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :refer [clamp-percent-float clamp-unit-float clamp-rgb-int]])
  (:import (afterglow.effects Assigner Effect)))

(defn apply-channel-value
  "A function which sets the DMX buffer value(s) for a channel, supporting fine channels
  as well (in which there is a high and low byte), using any fractional part of the value
  to determine the fine channel value if one is present.

  Also supports inverted channels (as needed for some fixtures which
  have inverted dimmers). Such channels are specified by containing an
  `:inverted-from` key which specifies the DMX value at which
  inversion occurs. If the entire DMX range is inverted, in other
  words 0 represents the highest value and 255 the lowest,
  `:inverted-from` will be present with the value `0`. For dimmers
  which are still black at zero, but which leap to full brightness at
  1 then dim as the value grows towards 255, `:inverted-from` will be
  present with the value `1`. Non-inverted channels will lack the key
  entirely."
 {:doc/format :markdown}
  [buffers channel value]
  (when-let [levels (get buffers (:universe channel))]
    (let [adjusted-value (if-let [pivot (:inverted-from channel)]
                           (if (< value pivot)
                             value
                             (- 255 (- value pivot)))
                           value)]
      (if-let [fine-index (:fine-index channel)]
        (do
          (aset levels (:index channel) (ubyte adjusted-value))
          (aset levels fine-index (ubyte (math/round (* 255 (- adjusted-value (int adjusted-value)))))))
        (aset levels (:index channel) (ubyte (math/round adjusted-value)))))))

(defn build-channel-assigner
  "Returns an assigner which applies the specified assignment function to the supplied channel."
  [channel f]
  (Assigner. :channel (keyword (str "u" (:universe channel) "a" (:address channel))) channel f))

(defn build-raw-channel-assigners
  "Returns a list of assigners which apply a channel assignment
  function to all the supplied channels."
  [channels f]
  (map #(build-channel-assigner % f) channels))

(defn build-fixed-channel-effect
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

(defn channel-effect
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

(defn raw-channel-effect
  "Returns an effect which simply calls a function to obtain the
  current level for all the supplied channels, runs forever, and ends
  immediately when requested."
  [name f channels]
  {:pre [(some? name) (ifn? f) (sequential? channels)]}
  (let [assigners (build-raw-channel-assigners channels f)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

;; Resolves the assignment of a level to a single DMX channel.
(defmethod fx/resolve-assignment :channel [assignment show snapshot buffers]
  ;; Resolve in case it is frame dynamic
  (let [resolved (clamp-rgb-int (params/resolve-param (:value assignment) show snapshot))]
    (apply-channel-value buffers (:target assignment) resolved)))

;; Fades between two assignments to a channel; often you won't want to do this, especially for
;; multi-function channels, so fade effects should probably default to excluding channel assigners
;; unless the show developer has explicitly requested them.
(defmethod fx/fade-between-assignments :channel [from-assignment to-assignment fraction show snapshot]
  (cond (<= fraction 0) from-assignment
        (>= fraction 1) to-assignment
        ;; We are blending, so we need to resolve any remaining dynamic parameters now, and make sure
        ;; fraction really does only range between 0 and 1.
        :else (let [from-resolved (clamp-rgb-int (params/resolve-param (or (:value from-assignment) 0) show snapshot))
                    to-resolved (clamp-rgb-int (params/resolve-param (or (:value to-assignment) 0) show snapshot))
                    fraction (clamp-unit-float fraction)]
                (merge from-assignment {:value (+ (* fraction to-resolved) (* (- 1.0 fraction) from-resolved))}))))

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

(defn function-effect
  "Returns an effect which assigns a dynamic value to all channels of
  the supplied fixtures or heads which have a range that implements
  the specified function. (Functions are a way for fixtures to use the
  same DMX channel to do multiple things, allocating ranges of values
  to get more dense use from a smaller number of channel allocations.)
  The `function` argument is the keyword by which the function
  information will be found for the supplied `fixtures`. The actual
  value sent for the channel associated with `function` for each
  fixture will be calculated by treating `level` as a percentage of
  the way between the lowest and highest DMX values assigned to that
  named function for the fixture. The name displayed for the effect in
  user interfaces is determined by `name`.

  If `:htp?` is passed with a `true` value, applies
  highest-takes-precedence (i.e. compares the value to the previous
  assignment for the channels implementing the function, and lets the
  highest value remain).

  If you have multiple effects trying to control different functions
  which use the same channel, you are unlikely to get the results you
  want. Hopefully the fixture designers chose how to share channels
  wisely, avoiding this pitfall."
  {:doc/format :markdown}
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

;; Resolves the assignment of a level to a named function on a head or fixture.
(defmethod fx/resolve-assignment :function [assignment show snapshot buffers]
  ;; Resolve in case it is frame dynamic
  (let [target (:target assignment)
        resolved (params/resolve-param (:value assignment) show snapshot target)
        target-name (name (:target-id assignment))
        function-key (keyword (subs target-name (inc (.indexOf target-name "-"))))
        [channel function-spec] (function-key (:function-map target))]
    (apply-channel-value buffers channel (function-percentage-to-dmx resolved function-spec))))

;; Fades between two assignments to a function. This won't mean much if the function is fixed
;; over its range, but that is harmless and so not worth suppressing, especially in case the
;; fixture definition is incorrect, and different values actually do have different effects.
(defmethod fx/fade-between-assignments :function [from-assignment to-assignment fraction show snapshot]
  (cond (<= fraction 0) from-assignment
        (>= fraction 1) to-assignment
        ;; We are blending, so we need to resolve any remaining dynamic parameters now, and make sure
        ;; fraction really does only range between 0 and 1.
        :else (let [from-resolved (clamp-percent-float
                                   (params/resolve-param (or (:value from-assignment) 0) show snapshot))
                    to-resolved (clamp-percent-float (params/resolve-param (or (:value to-assignment) 0) show snapshot))
                    fraction (clamp-unit-float fraction)]
                (merge from-assignment {:value (+ (* fraction to-resolved) (* (- 1.0 fraction) from-resolved))}))))
