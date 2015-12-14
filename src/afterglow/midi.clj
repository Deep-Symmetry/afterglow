(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :as rhythm]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.math.numeric-tower :as math]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre])
  (:import [java.util.concurrent LinkedBlockingDeque]
           [java.util.regex Pattern]))

(def ^:private max-clock-intervals
  "How many MIDI clock pulses and interval averages should be kept
  around for BPM calculation?"
  30)

(def ^:private min-clock-intervals
  "How many MIDI clock pulses are needed in order to start attempting
  BPM calculation?"
  12)

(def ^:private max-clock-lag
  "How many ms is too long to wait between MIDI clock pulses?"
  150)

(def ^:private max-tempo-taps
  "How many tempo taps should be kept around for averaging?"
  4)

(def ^:private max-tempo-tap-lag
  "How long is too long for a tap to be considered part of
  establishing a tempo?"
  2000)

(defonce ^{:private true
           :doc "All known MIDI input ports."}
  midi-inputs (atom []))

(defonce ^{:private true
           :doc "All known MIDI output ports."}
  midi-outputs (atom []))

(defonce ^{:private true
           :doc "The metronomes which are being synced to MIDI clock pulses."}
  synced-metronomes (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Controller Change
  messages arrive from particular input ports."}
  control-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Controller Change
  messages arrive from particular input ports."}
  note-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when any MIDI message at all
  is received."}
  global-handlers (atom #{}))

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
  clock message. Checks whether there are any metronomes being synced
  to that device, and if so, passes along the event. Also makes note
  of the sender as a potential source of MIDI synchronization."
  [msg]
  (doseq [handler (vals (get @synced-metronomes (:device msg)))]
    (handler msg)))

(defn- note-message-handler
  "Invoked whenever any midi input device being managed receives a
  note message. Checks whether there are any handlers (such as for
  launching cues or mapping show variables) attached to it, and if so,
  calls them."
  [msg]
  (doseq [handler (vals (get-in @note-mappings [(:name (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defn- cc-message-handler
  "Invoked whenever any midi input device being managed receives a
  control change message. Checks whether there are any handlers (such
  as for launching cues or mapping show variables) attached to it, and
  if so, calls them."
  [msg]
  (doseq [handler (vals (get-in @control-mappings [(:name (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defonce
  ^{:private true
    :doc "Keeps track of devices sending MIDI clock pulses so they can
  be offered as synchronization sources. Also notes when they seem to
  be sending the additional beat phase information provided by the
  Afterglow Traktor controller mapping."}
  clock-sources (atom {}))

(defn- check-for-traktor-beat-phase
  "Examines an incoming MIDI message to see if it seems to be coming
  from the Afterglow Traktor controller mapping, providing beat grid
  information. If so, makes a note of that fact so the clock source
  can be reported as offering this extra feature."
  [msg]
  (when (= (:command msg) :control-change)
    (let [controller (:note msg)]
      (cond (and (zero? controller) (< (:velocity msg) 5))
            (swap! clock-sources assoc-in [(:device msg) :master] (:velocity msg))

            (= controller (get-in @clock-sources [(:device msg) :master]))
            (swap! clock-sources assoc-in [(:device msg) :traktor-beat-phase] (now))))))

(defn- watch-for-clock-sources
  "Examines an incoming MIDI message to see if its source is a
  potential source for MIDI clock synchronization."
  [msg]
  (when (= (:status msg) :timing-clock)
    (swap! clock-sources assoc-in [(:device msg) :timing-clock] (now)))
  (check-for-traktor-beat-phase msg))

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
          (when @running
            (try
              ;; TODO: This is not really safe because if the handler blocks it ties up all future
              ;;       MIDI dispatch. Should do in a future with a timeout? Don't want to use a million
              ;;       threads for this either, so a core.async channel approach is probably best.
              ;;
              (handler msg)
              (catch InterruptedException e
                (timbre/info "MIDI event handler thread interrupted, shutting down.")
                (reset! running false)
                (reset! midi-transfer-thread nil))
              (catch Throwable t
                (timbre/error t "Problem runing global MIDI event handler")))))
        
        ;; Then call any registered port listeners for the port on which
        ;; it arrived

        ;; Then call specific message handlers that match
        (when @running
          (try
            (if (#{:timing-clock :start :stop} (:status msg))
              (clock-message-handler msg)
              (case (:command msg)
                :control-change (do (cc-message-handler msg)
                                    (clock-message-handler msg)) ; In case it is a Traktor beat phase message
                (:note-on :note-off) (note-message-handler msg)
                nil))
            (catch InterruptedException e
              (timbre/info "MIDI event handler thread interrupted, shutting down.")
              (reset! running false)
              (reset! midi-transfer-thread nil))
            (catch Throwable t
              (timbre/error t "Problem running MIDI event handler"))))

        ;; Finally, keep track of any MIDI clock messages we have seen so the
        ;; user can be informed of them as potential sync sources.
        (try
          (watch-for-clock-sources msg)
          (catch InterruptedException e
            (timbre/info "MIDI event handler thread interrupted, shutting down.")
            (reset! running false)
            (reset! midi-transfer-thread nil))
          (catch Throwable t
            (timbre/error t "Problem looking for MIDI clock sources"))))

      ;; If we have not ben shut down, do it all again for the next message.
      (when @running (recur (.take midi-queue))))))

(defn- incoming-message-handler
  "Attached to all midi input devices we manage. Puts the message on a
  queue so it can be processed by one of our own threads, which have
  access to all the classes that Afterglow needs."
  [msg]
  (try
    (.add midi-queue msg)
    (catch Throwable t
      (timbre/error t "Problem trasferring MIDI event to queue"))))

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
      (let [mmj-descriptions (set (map :description (filter mmj-device? (overtone.midi/midi-sources))))]
        (or (mmj-device? device)
            ;; MMJ returns devices whose descriptions match the names of the
            ;; broken devices returned by the broken Java MIDI implementation.
            (not (mmj-descriptions (:name device))))))
    identity))

(defn- start-handoff-thread
  "Creates the thread used to deliver MIDI events when needed."
  [old-thread]
  (or old-thread
      (doto (Thread. delegated-message-handler "MIDI event handoff")
        (.setDaemon true)
        (.start))))

;; TODO: This and open-outputs will have to change once hot-plugging works.
(defn open-inputs-if-needed!
  "Make sure the MIDI input ports are open and ready to distribute events.
  Returns the opened inputs."
  []
  (swap! midi-transfer-thread start-handoff-thread)
  (let [old-inputs @midi-inputs
        port-filter (create-midi-port-filter)
        result (swap! midi-inputs #(if (empty? %)
                                     (map connect-midi-in
                                          (filter port-filter (midi/midi-sources)))
                                     %))]
    (when (empty? old-inputs)
      (doseq [_ result]))  ;; Walk the mapped sequence to make the calls actually happen now
    result))

(defn open-outputs-if-needed!
  "Make sure the MIDI output ports are open and ready to receive events.
  Returns the opened outputs."
  []
  (let [old-outputs @midi-outputs
        port-filter (create-midi-port-filter)
        result (swap! midi-outputs #(if (empty? %)
                                      (map connect-midi-out
                                           (filter port-filter (midi/midi-sinks)))
                                      %))]
    (when (empty? old-outputs)
      (doseq [_ result])) ;; Walk the mapped sequence to make the calls actually happen now
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
    (rhythm/metro-beat-phase metronome 0)      ; Regardless, mark the beat
    (if (and (some? (last @buffer))
             (< (- timestamp (last @buffer)) max-tempo-tap-lag))
      ;; We are considering this part of a series of taps.
      (do
        (swap! buffer conj timestamp)
        (when (> (count @buffer) 2)
          (let [passed (- timestamp (peek @buffer))
                intervals (dec (count @buffer))
                mean (/ passed intervals)]
            (rhythm/metro-bpm metronome (double (/ 60000 mean))))))
      ;; This tap was isolated, but may start a new series.
      (reset! buffer (conj (ring-buffer max-tempo-taps) timestamp))))
  nil)

(defn create-tempo-tap-handler
  "Returns a function which implements a simple tempo-tap algorithm on
  the supplied metronome."
  [metronome]
  (let [buffer (atom (ring-buffer max-tempo-taps))]
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
  (sync-status [this]
  "Report on how well synchronization is working. Returns a map with
  keys `:type` (a keyword that uniquely identifies the kind of sync in
  effect, currently chosen from `:manual`, `:midi`, and `:dj-link`),
  `:current` (true if sync appears to be working at the present time),
  `:level` (a keyword that indicates how strong of a sync is being
  performed; `:bpm` means basic BPM following, `:beat` adds tracking
  of beat locations, `:bar` adds tracking of bar starts (down beats),
  and `:phrase` would add tracking of phrase starts, if any sync
  mechanism ever offers that), and `:status`, which is a human-oriented
  summmary of the status."))))

(defn interval-to-bpm
  "Given an interval between MIDI clock pulses in milliseconds,
  calculate the implied beats per minute value, to the nearest
  hundredth of a beat."
  [interval]
  (/ (math/round (double (/ 6000000 (* interval 24)))) 100.0))

(defn bpm-to-interval
  "Given a BPM, calculate the interval between MIDI clock pulses in
  milliseconds."
  [bpm]
  (/ 2500.0 bpm))

(defn std-dev
  "Calculate the standard deviation of a set of samples."
  ([samples]
   (let [n (count samples)
         mean (/ (reduce + samples) n)]
     (std-dev samples n mean)))
  ([samples n mean]
   (let [intermediate (map #(Math/pow (- %1 mean) 2) samples)]
     (Math/sqrt (/ (reduce + intermediate) n))))) 

(def abs-tolerance
  "If we are going to adjust the BPM, the adjustment we are going to
  make needs to represent at least this many milliseconds per MIDI
  clock tick (to reduce jitter)."
  0.005)

(def dev-tolerance
  "If we are going to adjust the BPM, the adjustment must be at least
  this many times the standard deviation in observed clock pulse
  timings, so we can avoid jitter due to unstable timing."
  2.2)

(defn- sync-handler
  "Called whenever a MIDI message is received for a synced metronome.
  If it is a clock pulse, update the ring buffer in which we are
  collecting timestamps, and if we have enough, calculate a BPM value
  and update the associated metronome."
  [msg timestamps means metronome traktor-info]
  (dosync
   (ensure traktor-info)
   (case (:status msg)
     :timing-clock (let [timestamp (now)]  ; Ordinary clock pulse
                     (alter timestamps conj timestamp)
                     (when (> (count @timestamps) 1)
                       (let [passed (- timestamp (peek @timestamps))
                             intervals (dec (count @timestamps))
                             mean (/ passed intervals)]
                         (alter means conj mean)
                         (when (>= (count @means) min-clock-intervals)
                           (let [num-means (count @means)
                                 mean-mean (/ (apply + @means) num-means)
                                 dev (std-dev @means num-means mean-mean)
                                 implied (bpm-to-interval (rhythm/metro-bpm metronome))
                                 adjustment (math/abs (- implied mean-mean))]
                             (when (> adjustment (max abs-tolerance (* dev-tolerance dev)))
                               (rhythm/metro-bpm metronome (interval-to-bpm mean-mean))))))))
     (:start :stop) (do (ref-set timestamps (ring-buffer max-clock-intervals)) ; Clock is being reset
                        (ref-set means (ring-buffer max-clock-intervals)))
     :control-change (when (and (some? @traktor-info) (< (:note msg) 5))  ; Traktor beat phase update
                       (when (zero? (:note msg))  ; Switching the current master deck
                         (alter traktor-info assoc :master (:velocity msg)))
                       (when (= (:master @traktor-info) (:note msg))  ; Beat phase for master deck, use it
                         (let [target-phase (/ (- (:velocity msg) 64) 127)]
                           ;; Only move when we are towards the middle of a beat, to make it more subtle
                           (when (< 0.2 target-phase 0.8) 
                             (rhythm/metro-beat-phase metronome target-phase)))
                         (alter traktor-info assoc :last-sync (now))))
     nil)))

(defn- add-synced-metronome
  [midi-clock-source metronome sync-fn]
  (swap! synced-metronomes #(assoc-in % [midi-clock-source metronome] sync-fn)))

(defn- remove-synced-metronome
  [midi-clock-source metronome]
  (swap! synced-metronomes #(update-in % [midi-clock-source] dissoc metronome)))

(defn- traktor-beat-phase-current
  "Checks whether our clock is being synced to ordinary MIDI clock, or
  enhanced Traktor beat phase using the custom Afterglow Traktor
  controller mapping."
  [traktor-info]
  (dosync
   (and (some? traktor-info)
        (< (- (now) (:last-sync traktor-info 0)) 100))))

;; A simple object which holds the values necessary to establish a link between an external
;; source of MIDI clock messages and the metronome driving the timing of a light show.
(defrecord ClockSync [metronome midi-clock-source timestamps means traktor-info] 
    IClockSync
    (sync-start [this]
      (add-synced-metronome midi-clock-source metronome
                            (fn [msg] (sync-handler msg timestamps means metronome traktor-info))))
    (sync-stop [this]
      (remove-synced-metronome midi-clock-source metronome))
    (sync-status [this]
      (dosync
       (ensure timestamps)
       (ensure traktor-info)
       (let [n (count @timestamps)
             lag (when (pos? n) (- (now) (last @timestamps)))
             current (and (= n max-clock-intervals)
                          (< lag 100))
             traktor-current (traktor-beat-phase-current @traktor-info)]
         {:type (if traktor-current :traktor-beat-phase :midi)
          :current current
          :level (if traktor-current :beat :bpm)
          :source midi-clock-source
          :status (cond
                    (empty? @timestamps) "Inactive, no clock pulses have been received."
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
  "Return only those devices whose name and/or description match the
  specified pattern. name-filter can either be a Pattern, or a string
  which will be turned into a pattern which matches in a
  case-insensitive way anywhere in the name or description."
  [devices name-filter]
  (if (or (nil? name-filter) (and (string? name-filter) (clojure.string/blank? name-filter)))
    devices
    (let [pattern (if (= (class name-filter) Pattern)
                    name-filter
                    (Pattern/compile (Pattern/quote (str name-filter)) Pattern/CASE_INSENSITIVE))]
      (filter #(or (re-find pattern (:name %1))
                   (re-find pattern (:description %1)))
              devices))))

(defn current-clock-sources
  "Returns the set of MIDI input ports which are currently delivering
  MIDI clock messages."
  []
  (set (for [[k v] @clock-sources]
         (when (< (- (now) (:timing-clock v)) 300) k))))

(defn current-traktor-beat-phase-sources
  "Returns the set of MIDI input ports which are currently delivering
  beat phase information in the format provided by the Afterglow
  Traktor controller mapping."
  []
  (set (for [[k v] @clock-sources]
         (when (< (- (now) (:traktor-beat-phase v 0)) 300) k))))

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
   (let [old-inputs @midi-inputs]
     (open-inputs-if-needed!)
     (when (empty? old-inputs) (Thread/sleep 300)))  ; Give the clock watcher a chance to spot messages
   (let [result (filter-devices (current-clock-sources) name-filter)]
     (case (count result)
       0 (throw (IllegalArgumentException. (str "No MIDI clock sources " (describe-name-filter name-filter)
                                                "were found.")))

       1 (fn [^afterglow.rhythm.Metronome metronome]
           (let [sync-handler (ClockSync. metronome (first result) (ref (ring-buffer max-clock-intervals))
                                          (ref (ring-buffer max-clock-intervals))
                                          (ref {:master (get-in @clock-sources [(first result) :master])}))]
             (sync-start sync-handler)
             sync-handler))

       (throw (IllegalArgumentException. (str "More than one MIDI clock source " (describe-name-filter name-filter)
                                              "was found.")))))))

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
                          (when (#{:note-on :note-off :control-change} (:command msg))
                            (deliver result msg)))]
     (try
       (add-global-handler! message-finder)
       (let [found (deref result timeout nil)]
         (when found
           (assoc (select-keys found [:command :channel :note :velocity])
                  :device (select-keys (:device found) [:name :description]))))
       (finally (remove-global-handler! message-finder))))))

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

(defn add-note-mapping
  "Register a handler to be called whenever a MIDI note message is
  received from the specified device, on the specified channel and
  note number. A unique key must be given, by which this mapping
  can later be removed. Any subsequent MIDI message which matches will
  be passed to the handler as its single argument. The first MIDI
  input source whose name or description matches the string or regex
  pattern supplied for name-filter will be chosen."
  [name-filter channel note key handler]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! note-mappings #(assoc-in % [(:name (first result))
                                       (int channel) (int note) (keyword key)] handler))))

(defn remove-note-mapping
  "Unregister a handler previously registered with add-note-mapping,
  identified by the unique key. The first MIDI input source whose name
  or description matches the string or regex pattern supplied for
  name-filter will be chosen."
  [name-filter channel note key]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! note-mappings #(update-in % [(:name (first result))
                                        (int channel) (int note)] dissoc (keyword key)))))

(defn find-midi-out
  "Find a MIDI output whose name matches the specified string or regex
  pattern."
  [name-filter]
  (let [result (filter-devices (open-outputs-if-needed!) name-filter)]
    (if (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI outputs " (describe-name-filter name-filter) "were found.")))
      (first result))))
