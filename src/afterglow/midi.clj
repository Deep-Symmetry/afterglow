(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :refer :all]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :refer [info error]])
  (:import [java.util.regex Pattern]
           [java.util.concurrent LinkedBlockingDeque]))

(def ^:private max-clock-intervals
  "How many MIDI clock pulses should be kept around for averaging?"
  12)

(def ^:private max-tempo-taps
  "How many tempo taps should be kept around for averaging?"
  4)

(def ^:private max-tempo-tap-interval
  "How long is too long for a tap to be considered part of
  establishing a tempo?"
  2000)

(defonce ^:private midi-inputs (atom []))

(defonce ^:private midi-outputs (atom []))

(defonce ^:private synced-metronomes (atom {}))

(defonce ^:private control-mappings (atom {}))

(defonce ^:private global-handlers (atom #{}))

(defn add-global-handler!
  "Add a function to be called whenever any MIDI message is received.
  The function will be called with the message, and must return
  quickly, so as to not block delivery to other recipients."
  [handler]
  (swap! global-handlers conj handler))

(defn remove-global-handler!
  "Remove a function that was being called whenever any MIDI message is
  received."
  [handler]
  (swap! global-handlers disj handler))

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

(defonce ^:private ^{:doc "The queue used to hand MIDI events from the
  extension thread to our world, since there seem to be classloader
  compatibility issues, and the Java3d classes are not available to
  threads that are directly dispatching MIDI events. Although we
  allocate a capacity of 100 elements, it is expected that the queue
  will be drained much more quickly than events arrive, and so it will
  almost always be empty."} midi-queue
  (LinkedBlockingDeque. 100))

(defonce ^:private ^{:doc "The thread used to hand MIDI events from
  the extension thread to our world, since there seem to be
  classloader compatibility issues, and the Java3d classes are not
  available to threads that are directly dispatching MIDI events."}
  midi-transfer-thread
  (atom nil))

(defn- delegated-message-handler
  "Takes incoming MIDI events from the incoming queue on a thread with
  access to all Afterglow classes. Fields incoming MIDI messages,
  looks for registered interest in them (metronome sync, variable
  mappings, etc.) and dispatches to the appropriate specific handler."
  []
  (let [running (atom true)]
    (loop [msg (.take midi-queue)]
      (when msg
        ;; First call the global message handlers
        (doseq [handler @global-handlers]
          (try
            (handler msg)
            (catch Throwable t
              (error t "Problem runing global MIDI event handler"))))
        
        ;; Then call any registered port listeners for the port on which
        ;; it arrived

        ;; Finally, call specific message handlers
        (try
          (case (:status msg)
            (:timing-clock :start :stop) (clock-message-handler msg)
            :control-change (cc-message-handler msg)
            nil)

          (catch InterruptedException t
            (info "MIDI event handler thread interrupted, shutting down.")
            (reset! running false)
            (reset! midi-transfer-thread nil))
          (catch Throwable t
            (error t "Problem running MIDI event handler")))
        (when running (recur (.take midi-queue)))))))

(defn- incoming-message-handler
  "Attached to all midi input devices we manage. Puts the message on a
  queue so it can be processed by one of our own threads, which have
  access to all the classes that Afterglow needs."
  [msg]
  (try
    (.add midi-queue msg)
    (catch Throwable t
      (error t "Problem trasferring MIDI event to queue"))))

(defn- connect-midi-in
  "Open a MIDI input device and cause it to send its events to
  our event distribution handler."
  [device]
  (let [opened (midi/midi-in device)]
    (taoensso.timbre/info "Opened MIDI input:" device)
    (midi/midi-handle-events opened incoming-message-handler)
    opened))

(defn- connect-midi-out
  "Open a MIDI output device."
  [device]
  (let [opened (midi/midi-out device)]
    (taoensso.timbre/info "Opened MIDI output:" device)
    opened))

(defn mac?
  "Return true if we seem to be running on a Mac."
  []
  (re-find #"Mac" (System/getProperty "os.name")))

(defn mmj-device?
  "Checks whether a MIDI device was returned by the Humatic mmj
  MIDI extension."
  [device]
  (re-find #"humatic" (str (:device device))))

(defn mmj-installed?
  "Return true if the Humatic mmj Midi extension is present."
  []
  (some mmj-device? (overtone.midi/midi-devices)))

(defn create-midi-port-filter
  "Return a filter which selects midi inputs and outputs we actually
  want to use. If this is a Mac, and the Humatic MIDI extension has
  been installed, create a filter which will accept only its versions
  of ports which it offers in parallel with the standard
  implementation. Otherwise, return a filter which accepts all ports."
  []
  (if (and (mac?) (mmj-installed?))
    (fn [device]
      (let [mmj-descriptions (set (map :description (filter #(mmj-device? %)
                                                            (overtone.midi/midi-sources))))]
        (or (mmj-device? device)
            ;; MMJ returns devices whose descriptions match the names of the
            ;; broken devices returned by the broken Java MIDI implementation.
            (not (mmj-descriptions (:name device))))))
    identity))

(defn- start-handoff-thread
  "Creates the thread used to deliver MIDI events when needed."
  [old-thread]
  (or old-thread
      (doto (Thread. delegated-message-handler)
        (.setDaemon true)
        (.start))))

;; TODO: This and open-outputs will have to change once hot-plugging works.
(defn open-inputs-if-needed!
  "Make sure the MIDI input ports are open and ready to distribute events.
  Returns the opened inputs."
  []
  (swap! midi-transfer-thread start-handoff-thread)
  (let [port-filter (create-midi-port-filter)
        result (swap! midi-inputs #(if (empty? %)
                                     (map connect-midi-in
                                          (filter port-filter (midi/midi-sources)))
                                     %))]
    (doseq [_ result])  ;; Make it actually happen now
    result))

(defn open-outputs-if-needed!
  "Make sure the MIDI output ports are open and ready to receive events.
  Returns the opened outputs."
  []
  (let [port-filter (create-midi-port-filter)
        result (swap! midi-outputs #(if (empty? %)
                                      (map connect-midi-out
                                           (filter port-filter (midi/midi-sinks)))
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

(defn- tap-handler
  "Called when a tap tempo event has occurred for a tap tempo handler
  created for a metronome. Set the beat phase of that metronome to
  zero, and if this occurred close enough in time to the last tap,
  update the ring buffer in which we are collecting timestamps, and if
  we have enough, calculate a BPM value and update the associated
  metronome. If there has been too much of a lag, reset the ring
  buffer."
  [buffer metronome]
  (let [timestamp (now)]
    (metro-beat-phase metronome 0)      ; Regardless, mark the beat
    (if (and (some? (last @buffer))
             (< (- timestamp (last @buffer)) max-tempo-tap-interval))
      ;; We are considering this part of a series of taps.
      (do
        (swap! buffer conj timestamp)
        (when (> (count @buffer) 2)
          (let [passed (- timestamp (peek @buffer))
                intervals (dec (count @buffer))
                mean (/ passed intervals)]
            (metro-bpm metronome (double (/ 60000 mean))))))
      ;; This tap was isolated, but may start a new series.
      (reset! buffer (conj (ring-buffer max-tempo-tap-interval) timestamp))))
  nil)

(defn create-tempo-tap-handler
  "Returns a function which implements a simple tempo-tap algorithm on
  the supplied metronome."
  [metronome]
  (let [buffer (atom (ring-buffer max-tempo-tap-interval))]
    (fn [] (tap-handler buffer metronome))))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
    (defprotocol IClockSync
  "A simple protocol for our clock sync object, allowing it to be
  started and stopped, and the status checked."
      (sync-start [this] "Start synchronizing your metronome.")
      (sync-stop [this] "Stop synchronizing your metronome.")
      (sync-status [this] "Report on how well synchronization is
      working. Returns a map with keys :type (a keyword that uniquely
      identifies the kind of sync in effect, currently chosen
      from :manual, :midi, and :dj-link), :current (true if sync
      appears to be working at the present time), :level (a keyword
      that indicates how strong of a sync is being performed; :bpm
      means basic BPM following, :beat adds tracking of beat
      locations, :bar adds tracking of bar starts (down beats),
      and :phrase would add tracking of phrase starts, if any sync
      mechanism ever offers that), and :status, which is a
      human-oriented summmary of the status."))

    (defprotocol IClockFinder
  "Allows a list of available MIDI clock sources to be gathered, for
  presentation in a user interface, by monitoring for clock pulses on
  all ports."
  (finder-current-sources [this] "Return the set of sources seen so far.")
  (finder-finished [this] "Stop listening for clock pulses and clean up."))))

(defn- sync-handler
  "Called whenever a MIDI message is received for a synced metronome.
  If it is a clock pulse, update the ring buffer in which we are
  collecting timestamps, and if we have enough, calculate a BPM value
  and update the associated metronome."
  [msg buffer metronome]
  (dosync
   (case (:status msg)
     :timing-clock (let [timestamp (now)]
                     (alter buffer conj timestamp)
                     (when (> (count @buffer) 2)
                       (let [passed (- timestamp (peek @buffer))
                             intervals (dec (count @buffer))
                             mean (/ passed intervals)]
                         (metro-bpm metronome (double (/ 60000 (* mean 24)))))))
            (:start :stop) (ref-set buffer (ring-buffer max-clock-intervals)) ; Clock is being reset!
            nil)))

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
      (dosync
       (ensure buffer)
       (let [n (count @buffer)
             lag (when (pos? n) (- (now) (last @buffer)))
             current (and (= n max-clock-intervals)
                        (< lag 100))]
         {:type :midi,
          :current current
          :level :bpm
          :status (cond
                    (empty? @buffer) "Inactive, no clock pulses have been received."
                    (not current) (str "Stalled? " (if (< n max-clock-intervals)
                                                     (str "Clock pulse buffer has " n " of "
                                                          max-clock-intervals " pulses in it.")
                                                     (str "Last clock pulse received " lag "ms ago.")))
                    :else "Running, clock pulse buffer is full and current.")}))))

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

;; A simple object to help provide a user interface for selecting between available MIDI
;; clock sync sources
(defrecord ClockFinder [listener results]
  IClockFinder
  (finder-current-sources [this] @results)
  (finder-finished [this]
    (remove-global-handler! listener)
    (reset! results nil)))

(defn watch-for-clock-sources
  "Returns a clock finder that will watch for sources of MIDI clock
  pulses until you tell it to stop."
  []
  (open-inputs-if-needed!)
  (let [clock-sources (atom #{})
        clock-listener (fn [msg]
                         (when (= (:status msg) :timing-clock)
                           (swap! clock-sources conj (:device msg))))]
    (add-global-handler! clock-listener)
    (ClockFinder. clock-listener clock-sources)))

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
   (let [clock-finder (watch-for-clock-sources)]
     (Thread/sleep 300)
     (let [result (filter-devices (finder-current-sources clock-finder) name-filter)]
       (finder-finished clock-finder)
       (case (count result)
         0 (throw (IllegalArgumentException. (str "No MIDI clock sources " (describe-name-filter name-filter)
                                                  "were found.")))
         1 (fn [^afterglow.rhythm.Metronome metronome]
             (let [sync-handler (ClockSync. metronome (first result) (ref (ring-buffer max-clock-intervals)))]
               (sync-start sync-handler)
               sync-handler))

         (throw (IllegalArgumentException. (str "More than one MIDI clock source " (describe-name-filter name-filter)
                                                "was found."))))))))



(defn identify-mapping
  "Report on the next MIDI control or note message received, to aid in
  setting up a mapping to a button, fader, or knob. Call this, then
  twiddle the knob, press the button, or move the fader, and see what
  Afterglow received. Pass a timeout in ms to control how long it will
  wait for a message (the default is ten seconds). This is an upper
  limit; if a message is received before the timeout, it will be
  reported immediately."
  ([]
   (identify-mapping 10000))
  ([timeout]
   (open-inputs-if-needed!)
   (let [result (promise)
         message-finder (fn [msg]
                          (when (#{:note-on :note-off :control-change} (:status msg))
                            (deliver result msg)))]
     (swap! global-handlers conj message-finder)
     (let [found (deref result timeout nil)]
       (swap! global-handlers disj message-finder)
       (when found
         (assoc (select-keys found [:command :channel :note :velocity])
                :device (select-keys (:device found) [:name :description])))))))

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

;; TODO apply to all matching input sources?
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
