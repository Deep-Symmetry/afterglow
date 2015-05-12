(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi])
  (:import [java.util.regex Pattern]))

;; How many pulses should we average?
(def ^:private max-clock-intervals 12)

(defonce ^:private midi-inputs (atom []))

(defonce ^:private synced-metronomes (atom {}))

(defonce ^:private control-mappings (atom {}))

(defonce ^:private global-handlers (atom #{}))

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
  (doseq [handler (vals (get-in @control-mappings [(:name (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defn- incoming-message-handler
  "Attached to all midi input devices we manage. Fields incoming MIDI
  messages, looks for registered interest in them (metronome sync,
  variable mappings, etc.) and dispatches to the appropriate specific
  handler."
  [msg]

  ;; First call the global message handlers
  (doseq [handler @global-handlers]
    (try
      (handler msg)
      (catch Exception e
        (taoensso.timbre/error e "Problem runing global MIDI event handler"))))

  (case (:status msg)
    (:timing-clock :start :stop) (clock-message-handler msg)
    :control-change (cc-message-handler msg)
    nil))

(defn- connect-midi-in
  "Open a MIDI input device and cause it to send its events to
  our event distribution handler."
  [device]
  (let [opened (midi/midi-in device)]
    (taoensso.timbre/info "Opened MIDI device:" device)
    (midi/midi-handle-events opened incoming-message-handler)
    opened))

(defn open-inputs-if-needed!
  "Make sure the MIDI input ports are open and ready to distribute events."
  []
  (let [result (swap! midi-inputs #(if (empty? %)
                                     (map connect-midi-in (midi/midi-sources))
                                     %))]
    (doseq [_ result])  ;; Make it actually happen now
    result))

;; Appears to be unsafe, we need to just keep them open
#_(defn- close-midi-device
  "Closes a MIDI device."
  [device]
  (.close (:device device)))

;; Appears to be unsafe, we need to just keep them open
#_(defn close-inputs
  "Close all the MIDI input ports. They will be reopened when a
  function which needs them is called."
  []
  (swap! midi-inputs #(if (seq %)
                        (do
                          (doseq [_ (map close-midi-device %)])
                          [])
                        %)))

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

(defn- describe-name-filter
  "Returns a description of a name filter used to narrow down MIDI
  sources, if one was supplied, or the empty string if none was."
  [name-filter]
  (if (or (nil? name-filter) (and (string? name-filter) (clojure.string/blank? name-filter)))
    ""
    (str "matching " (with-out-str (clojure.pprint/write-out name-filter)) " ")))

(defn- filter-devices
  [devices name-filter]
  (if (or (nil? name-filter) (and (string? name-filter) (clojure.string/blank? name-filter)))
    devices
    (let [pattern (if (= (class name-filter) Pattern)
                    name-filter
                    (Pattern/compile (Pattern/quote (str name-filter)) Pattern/CASE_INSENSITIVE))]
      (filter #(or (re-find pattern (:name %1))
                   (re-find pattern (:description %1)))
              devices))))

(defn sync-to-midi-clock
  "Returns a sync function that will cause the beats-per-minute
  setting of the supplied metronome to track the MIDI clock messages
  received from the named MIDI source. This is intended for use with
  afterglow.show/sync-to-external-clock. name-filter is only needed if
  there is more than one connected device sending MIDI clock messages
  when this function is invoked; it will be used to filter the
  eligible devices. If it is a string, then the device name or
  description must contain it. If it is a regular expression, the
  device name or description must match it. If it is nil or a blank
  string, no filtering is done. An exception will be thrown if there
  is not exactly one matching eligible MIDI clock source."
  ([]
   (sync-to-midi-clock nil))
  ([name-filter]
   (open-inputs-if-needed!)
   (let [clock-sources (atom #{})
         clock-finder (fn [msg]
                        (when (= (:status msg) :timing-clock)
                          (swap! clock-sources conj (:device msg))))]
     (swap! global-handlers conj clock-finder)
     (Thread/sleep 300)
     (swap! global-handlers disj clock-finder)
     (let [result (filter-devices @clock-sources name-filter)]
       (case (count result)
         0 (throw (IllegalArgumentException. (str "No MIDI clock sources " (describe-name-filter name-filter)
                                                  "were found.")))
         1 (fn [^afterglow.rhythm.Metronome metronome]
             (let [sync-handler (ClockSync. metronome (first result) (atom (ring-buffer max-clock-intervals)))]
               (sync-start sync-handler)
               sync-handler))

         (throw (IllegalArgumentException. (str "More than one MIDI clock source " (describe-name-filter name-filter)
                                                "was found."))))))))

;; TODO function to return next control or note message received, to aid mapping

;; TODO apply to all matching input sources?
(defn add-control-mapping
  "Register a handler to be called whenever a MIDI controller change
  message is received from the specified device, on the specified
  channel and controller number. A unique key must be given, by which
  this mapping can later be removed. Any subsequent MIDI message which
  matches will be passed to the handler as its single argument. The
  first MIDI input source whose name or description matches the string
  or regex pattern supplied for name-filter will be chosen."
  [name-filter channel control-number key handler]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! control-mappings #(assoc-in % [(:name (first result))
                                          (int channel) (int control-number) (keyword key)] handler))))

;; TODO apply to all matching input source?
(defn remove-control-mapping
  "Unregister a handler previously registered with
  add-control-mapping, identified by the unique key. The first MIDI
  input source whose name or description matches the string or regex
  pattern supplied for name-filter will be chosen."
  [name-filter channel control-number key]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! control-mappings #(update-in % [(:name (first result))
                                           (int channel) (int control-number)] dissoc (keyword key)))))
