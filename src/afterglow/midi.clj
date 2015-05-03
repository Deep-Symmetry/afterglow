(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.effects.color :refer [build-color-assigners
                                             find-rgb-heads]]
            [afterglow.effects.util :refer [always-active
                                            end-immediately]]
            [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :refer [spy]]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects.util Effect)))

;; How many pulses should we average?
(def ^:private max-clock-intervals 12)

;; A simple protocol for our clock sync object, allowing it to be started and stopped,
;; and the status checked.
(defprotocol IClockSync
  (sync-start [this])
  (sync-stop [this])
  (sync-status [this]))

(defn- sync-handler
  "Called whenever a MIDI message is received from the clock source. If it is a clock pulse, update
  the ring buffer in which we are collecting timestamps, and if we have enough, calculate a BPM value
  and update the associated metronome."
  [msg buffer metronome]
  (case (:status msg)
    :timing-clock (let [timestamp (now)]
                    (swap! buffer conj timestamp)
                    (when (> (count @buffer) 2)
                      (let [passed (- timestamp (peek @buffer))
                            intervals (dec (count @buffer))
                            mean (/ passed intervals)]
                        (metro-bpm metronome (double (/ 60000 (* mean 24)))))))
    (:start :stop) (reset! buffer (ring-buffer max-clock-intervals)) ; Clock is being reset!
    nil))

;; A simple object which holds the values necessary to establish a link between an external
;; source of MIDI clock messages and the metronome driving the timing of a light show.
(defrecord ClockSync [metronome midi-clock-source buffer] 
    IClockSync
    (sync-start [this]
      (midi/midi-handle-events midi-clock-source (fn [msg] (sync-handler msg buffer metronome))))
    (sync-stop [this]
      (midi/midi-handle-events midi-clock-source (fn [msg] nil)))
    (sync-status [this]
      (let [n (count @buffer)]
        {:type :midi,
         :status (cond
                   (empty? @buffer)           "Inactive, no clock pulses have been received."
                   (< n  max-clock-intervals) (str "Stalled? Clock pulse buffer has " n " of " max-clock-intervals " pulses in it.")
                   :else                      "Running, clock pulse buffer is full.")})))

(defn sync-to-midi-clock
  "Returns a sync function that will cause the beats-per-minute
  setting of the supplied metronome to track the MIDI clock messages
  received from the named MIDI source. This is intended for use with
  afterglow.show/sync-to-external-clock."
  [midi-clock-source]
  (fn [^afterglow.rhythm.Metronome metronome]
    (let [sync-handler (ClockSync. metronome (midi/midi-in midi-clock-source) (atom (ring-buffer max-clock-intervals)))]
      (sync-start sync-handler)
      sync-handler)))

(def default-down-beat-color
  "The default color to flash on the down beats."
  (colors/lighten (colors/create-color :red) 20))

(def default-other-beat-color
  "The default color to flash on beats that are not down beats."
  (colors/darken (colors/create-color :yellow) 30))

(defn metronome-cue
  "Returns an effect function which flashes the supplied fixtures to
  the beats of the show metronome, emphasizing the down beat, which is
  a great way to test and understand metronome synchronization. The
  color of the flashes can be controlled by the :down-beat-color
  and :other-beat-color arguments (defaulting to red with lightness
  70, and yellow with lightness 20, respectively)."
  [fixtures & {:keys [down-beat-color other-beat-color] :or {down-beat-color default-down-beat-color
                                                             other-beat-color default-other-beat-color}}]
  (let [heads (find-rgb-heads fixtures)
        f (fn [show snapshot target previous-assignment]
            (pspy :metronome-cue
                  (let [raw-intensity (* 2 (- (/ 1 2) (snapshot-beat-phase snapshot 1)))
                        intensity (if (neg? raw-intensity) 0 raw-intensity)
                        base-color (if (snapshot-down-beat? snapshot) down-beat-color other-beat-color)]
                    (colors/create-color {:h (colors/hue base-color)
                                          :s (colors/saturation base-color)
                                          :l (* (colors/lightness base-color) intensity)}))))
        assigners (build-color-assigners heads f)]
    (Effect. "Metronome" always-active (fn [show snapshot] assigners) end-immediately)))
