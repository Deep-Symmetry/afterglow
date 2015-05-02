(ns afterglow.midi
  "Handles MIDI communication."
  (:require [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :refer [spy]]))

;; A simple protocol for our MIDI clock sync object, allowing it to be started and stopped.
(defprotocol IClockSync
  (sync-start [this])
  (sync-stop [this]))

;; The maximum number of clock pulse intervals to keep for averaging.
(def ^:private max-clock-intervals 12)

(defn- sync-handler
  "Called whenever a MIDI message is received from the clock source. If it is a clock pulse, update
  the ring buffer in which we are collecting timestamps, and if we have enough, calculate a BPM value
  and update the associated metronome."
  [metronome msg intervals last-timestamp]
  (case (:status msg)
    :timing-clock (let [timestamp (now)]
                    (when @last-timestamp
                      (let [delta (- timestamp @last-timestamp)]
                        (if (< delta 1000)
                          (do ; Not a completely ridiculous interval, so process this clock pulse
                            (swap! intervals conj delta)
                            (when (> (count @intervals) 2)
                              (let [mean (/ (reduce + @intervals) (count @intervals))]
                                (metro-bpm metronome (double (/ 60000 (* mean 24)))))))
                          (reset! intervals (ring-buffer max-clock-intervals))))) ; Too long a gap, discard data and start over.
                    (reset! last-timestamp timestamp))
    (:start :stop) (reset! intervals (ring-buffer max-clock-intervals)) ; Clock is being reset!
    nil))

;; A simple object which holds the values necessary to establish a link between an external
;; source of MIDI clock messages and the metronome driving the timing of a light show.
(defrecord ClockSync [metronome midi-clock-source intervals last-timestamp] 
    IClockSync
    (sync-start [this]
      (midi/midi-handle-events midi-clock-source (fn [msg] (sync-handler metronome msg intervals last-timestamp))))
    (sync-stop [this]
      (midi/midi-handle-events midi-clock-source (fn [msg] nil))))

(defn sync-to-midi-clock
  "Cause the beats-per-minute setting of the supplied metronome to track the MIDI clock messages
  received from the named MIDI source. This synchronization can be stopped by calling the sync-stop
  function on the object returned."
  [^afterglow.rhythm.Metronome metronome ^String midi-clock-source]
  (let [sync-handler (ClockSync. metronome (midi/midi-in midi-clock-source) (atom (ring-buffer max-clock-intervals)) (atom nil))]
    (sync-start sync-handler)
    sync-handler))
