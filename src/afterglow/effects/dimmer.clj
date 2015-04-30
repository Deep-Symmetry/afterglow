(ns afterglow.effects.dimmer
  "Effects pipeline functions for working with dimmer channels for fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.util :as fx-util]
            [taoensso.timbre.profiling :refer [pspy]]))

(defn- assign-level
  "Assigns a dimmer level to the channel."
  [level channel]
  (assoc channel :value level))

(defn dimmer-channel?
  "Returns true if the supplied channel is a dimmer."
  [c]
  (= (:type c) :dimmer))

(defn- gather-dimmer-channels
  "Finds all channels in the supplied fixture list which are dimmers, even if they are inside heads."
  [fixtures]
  (let [heads (channels/extract-heads-with-some-matching-channel fixtures dimmer-channel?)]
    (channels/extract-channels heads dimmer-channel?)))

;; TODO UNUSED, remove once sure this will not be needed.
(defn- build-dimmer-assigners
  "Reurns a list of assigners which apply an assignment function to all dimmer channels in the
  supplied fixtures (and their individually-dimmable heads, if applicable)."
  [fixtures value f]
  (map #(chan-fx/build-channel-assigner % f) (gather-dimmer-channels fixtures)))

(defn dimmer-cue
  "Returns an effect which simply assigns a fixed value to all dimmers of the supplied fixtures. If htp?
  is true, use highest-takes-precedence (i.e. compare to the previous assignment, and let the higher value
  remain)."
  ([level fixtures]
   (dimmer-cue level fixtures true))
  ([level fixtures htp?]
   (fx-util/validate-dmx-value level "level")
   (let [dimmers (gather-dimmer-channels fixtures)]
     (chan-fx/build-fixed-channel-cue (str "Dimmers=" level (when htp?) " (HTP)") level dimmers htp?))))

(defn dimmer-oscillator
  "Returns an effect function which drives the dimmer channels of the supplied fixtures according to
  a supplied oscillator function and the show metronome.  If :htp? is true, use highest-takes-precedence
  (i.e. compare to the previous assignment, and let the higher value remain). Unless otherwise specified,
  via :min and :max, ranges from 0 to 255. Returns a fractional value, because that can be handled by
  channels with an associated fine channel (commonly pan and tilt), and will be resolved in the process
  of assigning the value to the DMX channels."
  [osc fixtures & {:keys [min max htp?] :or {min 0 max 255 htp? true}}]
  (fx-util/validate-dmx-value min "min")
  (fx-util/validate-dmx-value max "max")
  (when-not (< min max)
    (throw (IllegalArgumentException. "min must be less than max")))
  (let [range (long (- max min))
        chans (channels/extract-channels fixtures #(= (:type %) :dimmer))
        f (if htp?
            (fn [show snapshot target previous-assignment]
              (pspy :dimmer-oscillator-htp
                    (let [phase (osc snapshot)
                          new-level (+ min (* range phase))]
                      (clojure.core/max new-level (or previous-assignment 0)))))
            (fn [show snapshot target previous-assignment]
              (pspy :dimmer-oscillator
                    (let [phase (osc snapshot)
                          new-level (+ min (* range phase))]
                      new-level))))]
    (chan-fx/build-simple-channel-cue (str "Dimmer Oscillator " min "-" max (when htp? " (HTP)")) f chans)))

