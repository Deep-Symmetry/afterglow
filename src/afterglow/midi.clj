(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :refer [spy]]))

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
        (cond
          (empty? @buffer)           "Inactive, no clock pulses have been received."
          (< n  max-clock-intervals) (str "Stalled? Clock pulse buffer has " n " of " max-clock-intervals " pulses in it.")
          :else                      "Running, clock pulse buffer is full."))))

(defn sync-to-midi-clock
  "Cause the beats-per-minute setting of the supplied metronome to track the MIDI clock messages
  received from the named MIDI source. This synchronization can be stopped by calling the sync-stop
  function on the object returned."
  [^afterglow.rhythm.Metronome metronome ^String midi-clock-source]
  (let [sync-handler (ClockSync. metronome (midi/midi-in midi-clock-source) (atom (ring-buffer max-clock-intervals)))]
    (sync-start sync-handler)
    sync-handler))
