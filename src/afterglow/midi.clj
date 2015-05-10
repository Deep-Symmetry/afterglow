(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]))

;; How many pulses should we average?
(def ^:private max-clock-intervals 12)

(defonce ^:private midi-inputs (atom {}))

(defonce ^:private synced-metronomes (atom {}))

(defonce ^:private control-mappings (atom {}))

(defn- clock-message-handler
  "Invoked whenever any midi input device being managed receives a
  clock message. Checks whether there are any metronomes being synced to
  that device, and if so, passes along the event."
  [msg]
  (doseq [handler (vals (get @synced-metronomes (:name (:device msg))))]
    (handler msg)))

(defn- cc-message-handler
  "Invoked whenever any midi input device being managed receives a
  control-change message. Checks whether there are show variables
  mapped to it, and if so, updates them."
  [msg]
  (doseq [show (vals (get-in @control-mappings [(:name (:device msg)) (:channel msg) (:note msg)]))]
    (doseq [handler (vals show)]
      (handler msg))))

(defn- incoming-message-handler
  "Attached to all midi input devices we manage. Fields incoming MIDI
  messages, looks for registered interest in them (metronome sync,
  variable mappings, etc.) and dispatches to the appropriate specific
  handler."
  [msg]

  (case (:status msg)
    (:timing-clock :start :stop) (clock-message-handler msg)
    :control-change (cc-message-handler msg)))

(defn- find-midi-in
  "Find midi input matching the specified name; if we have already
  created an object to process events from it, reuse that one."
  [device-name]
  (let [found (midi/midi-in device-name)
        existing (get @midi-inputs (:name found))]
    (or existing (do
                   (midi/midi-handle-events found incoming-message-handler)
                   (swap! midi-inputs #(assoc % (:name found) found))
                   found))))

;; A simple protocol for our clock sync object, allowing it to be started and stopped,
;; and the status checked.
(defprotocol IClockSync
  (sync-start [this])
  (sync-stop [this])
  (sync-status [this]))

(defn- sync-handler
  "Called whenever a MIDI message is received for a synced metronome.
  If it is a clock pulse, update the ring buffer in which we are
  collecting timestamps, and if we have enough, calculate a BPM value
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

(defn- add-synced-metronome
  [midi-clock-source metronome sync-fn]
  (swap! synced-metronomes #(assoc-in % [(:name midi-clock-source) metronome] sync-fn)))

(defn- remove-synced-metronome
  [midi-clock-source metronome]
  (swap! synced-metronomes #(update-in % [(:name midi-clock-source)] dissoc metronome)))

;; A simple object which holds the values necessary to establish a link between an external
;; source of MIDI clock messages and the metronome driving the timing of a light show.
(defrecord ClockSync [metronome midi-clock-source buffer] 
    IClockSync
    (sync-start [this]
      (add-synced-metronome midi-clock-source metronome (fn [msg] (sync-handler msg buffer metronome))))
    (sync-stop [this]
      (remove-synced-metronome midi-clock-source metronome))
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
  [midi-device-name]
  (fn [^afterglow.rhythm.Metronome metronome]
    (let [sync-handler (ClockSync. metronome (find-midi-in midi-device-name) (atom (ring-buffer max-clock-intervals)))]
      (sync-start sync-handler)
      sync-handler)))

(defn- assign-show-variable
  "Helper function to swap in a new value for a show variable."
  [show key newval]
  (swap! (:variables show) #(assoc % key newval)))

(defn add-var-control-mapping
  [midi-device-name channel control-number show variable & {:keys [min max] :or {min 0 max 127}}]
  (when-not (< min max)
    (throw (IllegalArgumentException. "min must be less than max")))
  (let [range (- max min)
        key (keyword variable)]
    (swap! control-mappings #(assoc-in % [(:name (find-midi-in midi-device-name))
                                          (int channel) (int control-number) show key]
                                       (fn [msg]
                                         (let [newval (float (+ min (/ (* (:velocity msg) range) 127)))]
                                           (assign-show-variable show key newval)))))))

(defn remove-var-control-mapping
  [midi-device-name channel control-number show variable]
  (let [range (- max min)
        key (keyword variable)]
    (swap! control-mappings #(update-in % [(:name (find-midi-in midi-device-name))
                                           (int channel) (int control-number) show] dissoc key))))
