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
           :doc "All currently available MIDI input ports, as a map
  whose keys are the implementing `javax.sound.midi.MidiDevice` and
  whose values are the corresponding `:midi-device` map returned by
  `overtone.midi`."}
  midi-inputs (atom {}))

(defonce ^{:private true
           :doc "All currently available MIDI output ports, as a map
  whose keys are the implementing `javax.sound.midi.MidiDevice` and
  whose values are the corresponding `:midi-device` map returned by
  `overtone.midi`."}
  midi-outputs (atom {}))

(defonce ^{:private true
          :doc "Functions to be called when a new device appears in
          the MIDI environment. They will be passed a single argument,
          the `:midi-device` map from `overtone.midi` representing the
          new device."}
  new-device-handlers (atom #{}))

(defn add-new-device-handler!
  "Add a function to be called whenever a new device appears in the
  MIDI environment. It will be passed a single argument, the
  `:midi-device` map from `overtone.midi` representing the new device.
  It must return quickly so as not to stall the delay of other MIDI
  events; lengthy operations must be performed on another thread."
  [f]
  {:pre [(fn? f)]}
  (swap! new-device-handlers conj f))

(defn remove-new-device-handler!
  "Stop calling the specified function to be called whenever a new
  device appears in the MIDI environment."
  [f]
  {:pre [(fn? f)]}
  (swap! new-device-handlers disj f))

(defonce ^{:private true
          :doc "Functions to be called when a device disappears from
  the MIDI environment. Keys are the `javax.sound.midi.MidiDevice`
  whose departure is of interest, and values are a set of functions to
  be called if and when that device ceases to exist. They will be
  called with no arguments."}
  disconnected-device-handlers (atom {}))

(defn add-disconnected-device-handler!
  "Add a function to be called whenever the specified device
  disappears from the MIDI environment. The `device` argument is a
  `:midi-device` map from `overtone.midi` representing device whose
  removal is of interest. The function must return quickly so as not
  to stall the delay of other MIDI events; lengthy operations must be
  performed on another thread."
  [device f]
  {:pre [(= (type device) :midi-device) (fn? f)]}
  (swap! disconnected-device-handlers #(update-in % [(:device device)] clojure.set/union #{f})))

(defn remove-disconnected-device-handler!
  "No longer call the specified function if specified device
  disappears from the MIDI environment. The `device` argument is a
  `:midi-device` map from `overtone.midi` representing device whose
  removal is no longer of interest."
  [device f]
  {:pre [(= (type device) :midi-device) (fn? f)]}
  (swap! disconnected-device-handlers #(update-in % [(:device device) disj f])))

(defonce ^{:private true
           :doc "The metronomes which are being synced to MIDI clock
  pulses. A map whose keys are the `javax.sound.midi.MidiDevice` on
  which clock pulses are being received, and whose values are in turn
  maps whose keys are the metronomes being synced to pulses from that
  device, and whose values are the sync function to call when each
  pulse is received."} synced-metronomes (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Controller Change
  messages arrive from particular input ports. A set of nested maps
  whose keys are the `javax.sound.midi.MidiDevice` on which the
  message should be watched for, the channel to watch, the controller
  number to watch for, and a unique keyword to identify this
  particular mapping for removal later. The values are the function to
  be called with each matching message."}
  control-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Note messages arrive
  from particular input ports. A set of nested maps whose keys are the
  `javax.sound.midi.MidiDevice` on which the message should be watched
  for, the channel to watch, the note number to watch for, and a
  unique keyword to identify this particular mapping for removal
  later. The values are the function to be called with each matching
  message."}
  note-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Aftertouch
  (polyphonic key pressure) messages arrive from particular input
  ports. A set of nested maps whose keys are the
  `javax.sound.midi.MidiDevice` on which the message should be watched
  for, the channel to watch, the note number to watch for, and a
  unique keyword to identify this particular mapping for removal
  later. The values are the function to be called with each matching
  message."}
  aftertouch-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when any MIDI message at all
  is received."}
  global-handlers (atom #{}))

(defn add-global-handler!
  "Add a function to be called whenever any MIDI message is received.
  The function will be called with the message, and must return
  quickly, so as to not block delivery to other recipients."
  [f]
  {:pre [(fn? f)]}
  (swap! global-handlers conj f))

(defn remove-global-handler!
  "Remove a function that was being called whenever any MIDI message is
  received."
  [f]
  (swap! global-handlers disj f))


(defn- clock-message-handler
  "Invoked whenever any midi input device being managed receives a
  clock message. Checks whether there are any metronomes being synced
  to that device, and if so, passes along the event."
  [msg]
  (doseq [handler (vals (get @synced-metronomes (:device (:device msg))))]
    (handler msg)))

(defn- cc-message-handler
  "Invoked whenever any midi input device being managed receives a
  control change message. Checks whether there are any handlers (such
  as for launching cues or mapping show variables) attached to it, and
  if so, calls them."
  [msg]
  (doseq [handler (vals (get-in @control-mappings [(:device (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defn- note-message-handler
  "Invoked whenever any midi input device being managed receives a
  note message. Checks whether there are any handlers (such as for
  launching cues or mapping show variables) attached to it, and if so,
  calls them."
  [msg]
  (doseq [handler (vals (get-in @note-mappings [(:device (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defn- aftertouch-message-handler
  "Invoked whenever any midi input device being managed receives an
  aftertouch (polyphonic key pressure) message. Checks whether there
  are any handlers attached to it, and if so, calls them."
  [msg]
  (doseq [handler (vals (get-in @aftertouch-mappings [(:device (:device msg)) (:channel msg) (:note msg)]))]
    (handler msg)))

(defonce
  ^{:private true
    :doc "Keeps track of devices sending MIDI clock pulses so they can
  be offered as synchronization sources. Also notes when they seem to
  be sending the additional beat phase information provided by the
  Afterglow Traktor controller mapping. Holds a set of nested maps
  whose top-level keys are the `javax.sound.midi.MidiDevice` on which
  clock pulses have been detected, and whose second-level keys can
  include `:timing-clock`, which will store the timestamp of the
  most-recently received clock pulse from that device, `:master`,
  which will store the number of the Traktor deck which was most
  recently identified as the Tempo Master if we are getting messages
  which seem like they could come from the Traktor Afterglow mapping,
  and `:traktor-beat-phase` which will contain the timestamp of the
  most recent value we have received which seems to correct to Traktor
  beat phase information coming from the Traktor Afterglow mapping."}
  clock-sources (atom {}))

(defn- check-for-traktor-beat-phase
  "Examines an incoming MIDI message to see if it seems to be coming
  from the Afterglow Traktor controller mapping, providing beat grid
  information. If so, makes a note of that fact so the clock source
  can be reported as offering this extra feature."
  [msg device]
  (when (= (:command msg) :control-change)
    (let [controller (:note msg)]
      (cond (and (zero? controller) (< (:velocity msg) 5))
            (swap! clock-sources assoc-in [device :master] (:velocity msg))

            (= controller (get-in @clock-sources [device :master]))
            (swap! clock-sources assoc-in [device :traktor-beat-phase] (now))))))

(defn- watch-for-clock-sources
  "Examines an incoming MIDI message to see if its source is a
  potential source for MIDI clock synchronization."
  [msg]
  (let [device (:device (:device msg))]
    (when (= (:status msg) :timing-clock)
      (swap! clock-sources assoc-in [device :timing-clock] (now)))
    (check-for-traktor-beat-phase msg device)))

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
  "Return true if the Humatic mmj MIDI extension is present."
  []
  (some mmj-device? (overtone.midi/midi-devices)))

(defn cm4j-device?
  "Checks whether a MIDI device was returned by the CoreMIDI4J MIDI
  extension."
  [device]
  (re-find #"uk\.co\.xfactorylibrarians\.coremidi4j" (str (:device device))))

(defn cm4j-installed?
  "Return true if the CoreMIDI4J MIDI extension is present."
  []
  (some cm4j-device? (overtone.midi/midi-devices)))

(defn create-midi-port-filter
  "Return a filter which selects midi inputs and outputs we actually
  want to use. If this is a Mac, and the CoreMIDI4J MIDI extension is
  present, create a filter which will accept only its devices.
  Otherwise, if the Humatic MIDI extension has been installed, create
  a filter which will accept only its versions of ports which it
  offers in parallel with the standard implementation. Otherwise,
  return a filter which accepts all ports."
  []
  (cond
    (and (mac?) (cm4j-installed?))
    cm4j-device?  ;; Only use devices provided by CoreMIDI4J if it is installed.

    (and (mac?) (mmj-installed?))
    (fn [device]
      (let [mmj-descriptions (set (map :description (filter mmj-device? (overtone.midi/midi-sources))))]
        (or (mmj-device? device)
            ;; MMJ returns devices whose descriptions match the names of the
            ;; broken devices returned by the broken Java MIDI implementation.
            ;; But it does not wrap all devices, e.g. the Traktor Virtual Output,
            ;; so we need to only screen out devices which are supported.
            ;; MMJ support is only left in place to support Java 6 environments,
            ;; in particular Max/MSP, where CoreMIDI4J is not available.
            (not (mmj-descriptions (:name device))))))

    :else
    identity))

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
  "Open a MIDI input device, add it to the map of known inputs, and
  cause it to send its events to our event distribution handler."
  [device]
  (let [opened (midi/midi-in device)]
    (timbre/info "Opened MIDI input:" opened)
    (swap! midi-inputs assoc (:device opened) opened)
    (midi/midi-handle-events opened incoming-message-handler)
    opened))

(defn- connect-midi-out
  "Open a MIDI output device and add it to the map of known outputs."
  [device]
  (let [opened (midi/midi-out device)]
    (timbre/info "Opened MIDI output:" opened)
    (swap! midi-outputs assoc (:device opened) opened)
    opened))

(defn- environment-changed
  "Called when the MIDI environment has changed, as long
  as [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) is
  installed. Processes any devices which have appeared or
  disappeared."
  []
  (try
    (.add midi-queue :environment-changed)
    (catch Throwable t
      (timbre/error t "Problem adding MIDI environment change to queue"))))

(declare ensure-threads-running)

(defn open-inputs-if-needed!
  "Make sure the MIDI input ports are open and ready to distribute events.
  Returns the `:midi-device` maps returned by `overtone.midi`
  representing the opened inputs."
  []
  (ensure-threads-running)
  (doseq [input (filter (create-midi-port-filter) (midi/midi-sources))]
    (when-not (get @midi-inputs (:device input))
      (let [connected (connect-midi-in input)]
        (doseq [handler @new-device-handlers]
          (handler connected)))))
  (vals @midi-inputs))

(defn open-outputs-if-needed!
  "Make sure the MIDI output ports are open and ready to receive events.
  Returns the `:midi-device` maps returned by `overtone.midi`
  representing the opened outputs."
  []
  (ensure-threads-running)
  (doseq [output (filter (create-midi-port-filter) (midi/midi-sinks))]
    (when-not (get @midi-outputs (:device output))
      (let [connected (connect-midi-out output)]
        (doseq [handler @new-device-handlers]
          (handler connected)))))
  (vals @midi-outputs))

(defn- lost-midi-in
  "Called when a device in our list of inputs no longer exists in the
  MIDI environment. Removes it from all lists of mappings, synced
  metronomes, handlers, etc. and closes it."
  [vanished]
  {:pre [(= (type vanished) :midi-device)]}
  (let [device (:device vanished)]
    (swap! midi-inputs dissoc device)
    (swap! synced-metronomes dissoc device)
    (swap! control-mappings dissoc device)
    (swap! note-mappings dissoc device)
    (swap! aftertouch-mappings dissoc device)
    (swap! clock-sources dissoc device)
    (swap! disconnected-device-handlers dissoc device)
    (.close device))
  (timbre/info "Lost contact with MIDI input: " vanished))

(defn- lost-midi-out
  "Called when a device in our list of outputs no longer exists in the
  MIDI environment. Removes it from all lists of outputs, and closes
  it."
  [vanished]
  {:pre [(= (type vanished) :midi-device)]}
  (let [device (:device vanished)]
    (swap! midi-outputs dissoc device)
    (swap! disconnected-device-handlers dissoc device)
    (.close device))
  (timbre/info "Lost contact with MIDI output: " vanished))

(defn scan-midi-environment
  "Called either periodically to check for changes, or, if CoreMidi4J
  is installed, proactively in response to a reported change in the
  MIDI environment. Updates our notion of what MIDI devices are
  available, and notifies registered listeners of any changes.

  Also sets up the thread used to process incoming MIDI events if that
  is not already running, and arranges for this function to be called
  as needed to keep up with future changes in the MIDI environment."
  []
  ;; Clean up devices which are no longer present, notifying registered listeners of their departure.
  (let [f (create-midi-port-filter)]
    (doseq [device (clojure.set/difference (set (keys @midi-inputs))
                                           (set (map :device (filter f (midi/midi-sources)))))]
      (doseq [handler (get @disconnected-device-handlers device)]
        (handler))
      (lost-midi-in (get @midi-inputs device)))
    (doseq [device (clojure.set/difference (set (keys @midi-outputs))
                                           (set (map :device (filter f (midi/midi-sinks)))))]
      (doseq [handler (get @disconnected-device-handlers device)]
        (handler))
      (lost-midi-out (get @midi-outputs device))))

  ;; Open and configure any newly available devices.
  (open-inputs-if-needed!)
  (open-outputs-if-needed!))

(defn- handle-environment-changed
  "Called when we had an event posted to the MIDI event queue telling
  us that the environment has changed, i.e. a device has been added or
  removed. Update our notion of the environment state accordingly."
  []
  (timbre/info "MIDI Environment changed!")
  (scan-midi-environment))

(defn- delegated-message-handler
  "Takes incoming MIDI events from the incoming queue on a thread with
  access to all Afterglow classes. Fields incoming MIDI messages,
  looks for registered interest in them (metronome sync, variable
  mappings, etc.) and dispatches to the appropriate specific handler."
  []
  (let [running (atom true)]
    (loop [msg (.take midi-queue)]
      (when msg
        (if (= msg :environment-changed)
          (handle-environment-changed)  ; Not an actual MIDI message, handle specially
          (do  ; Process a normal MIDI message
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
                    (timbre/error t "Problem running global MIDI event handler.")))))
            
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
                    :poly-pressure (aftertouch-message-handler msg)
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
                (timbre/error t "Problem looking for MIDI clock sources"))))))

      ;; If we have not been shut down, do it all again for the next message.
      (when @running (recur (.take midi-queue))))))

(defn- start-handoff-thread
  "Creates the thread used to deliver MIDI events when needed."
  [old-thread]
  (or old-thread
      (doto (Thread. delegated-message-handler "MIDI event handoff")
        (.setDaemon true)
        (.start))))

(defonce ^{:doc "How often should we scan for changes in the MIDI
  environment (devices that have been disconnected, or new devices
  which have connected). This is used only
  when [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) is not
  installed, sine CoreMIDI4J gives us proactive notification of
  changes to the MIDI environment. The value is in milliseconds, so
  the default means to check every two seconds. Changes to this value
  will take effect after the next scan completes."}
  scan-interval (atom  2000))

(defonce ^:private ^{:doc "The thread, if any, which periodically
  checks for changes in the MIDI environment when CoreMIDI4J is not
  installed to let us know about them proactively. If CoreMIDI4J is in
  use, this will be set to `:environment-changed` rather than an
  actual thread, since none need be created."}
  scan-thread (atom nil))

(defn- periodic-scan-handler
  "Rescans the MIDI environment at intervals defined
  by [[scan-interval]]. Used when CoreMIDI4J is not installed to
  arrange for such scans to happen when the environment actually has
  changed."
  []
  (let [running (atom true)]
    (loop []
      (try
        (Thread/sleep @scan-interval)
        (scan-midi-environment)
        (catch InterruptedException e
          (timbre/info "MIDI periodic MIDI environment scan thread interrupted, shutting down.")
          (reset! running false)
          (reset! scan-thread nil))
        (catch Throwable t
          (timbre/error t "Problem running periodic MIDI scan thread.")))
      ;; If we have not been shut down, do it all again.
      (when @running (recur)))))

(defn- start-scan-thread
  "If there is no existing value in `old-thread`, Checks
  whether [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) is
  installed, and if so, registers our notification handler to let us
  know when the MIDI environment changes, and returns the value
  `:environment-changed`. Otherwise creates, starts, and returns a
  thread to periodically scan for such changes."
  [old-thread]
  (or old-thread
      (if (clojure.reflect/resolve-class (.getContextClassLoader (Thread/currentThread))
                                         'uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification)
        (do (require '[afterglow.coremidi4j])  ; Can use proactive change notification
            ((resolve 'afterglow.coremidi4j/add-environment-change-handler) environment-changed)
            :environment-changed)
        (doto (Thread. periodic-scan-handler "MIDI environment change scanner")  ; Need to poll
          (.setDaemon true)
          (.start)))))

(defn- ensure-threads-running
  "Starts the threads needed by the afterglow MIDI implementation, if
  they are not already running."
  []
  (swap! midi-transfer-thread start-handoff-thread)
  (swap! scan-thread start-scan-thread))

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
  [midi-clock-source metronome f]
  {:pre [(= (type midi-clock-source) :midi-device) (satisfies? rhythm/IMetronome metronome) (fn? f)]}
  (swap! synced-metronomes #(assoc-in % [(:device midi-clock-source) metronome] f)))

(defn- remove-synced-metronome
  [midi-clock-source metronome]
  {:pre [(= (type midi-clock-source) :midi-device)]}
  (swap! synced-metronomes #(update-in % [(:device midi-clock-source)] dissoc metronome)))

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

(defn filter-devices
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
  (when (empty? @midi-inputs)
    (open-inputs-if-needed!)
    (Thread/sleep 300)) ; Give the clock watcher a chance to spot messages
  (set (for [[k v] @clock-sources]
         (when (< (- (now) (:timing-clock v)) 300) (get @midi-inputs k)))))

(defn current-traktor-beat-phase-sources
  "Returns the set of MIDI input ports which are currently delivering
  beat phase information in the format provided by the Afterglow
  Traktor controller mapping."
  []
  (set (for [[k v] @clock-sources]
         (when (< (- (now) (:traktor-beat-phase v 0)) 300) (get @midi-inputs k)))))

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
   (let [result (filter-devices (current-clock-sources) name-filter)]
     (case (count result)
       0 (throw (IllegalArgumentException. (str "No MIDI clock sources " (describe-name-filter name-filter)
                                                "were found.")))

       1 (fn [^afterglow.rhythm.Metronome metronome]
           (let [sync-handler (ClockSync. metronome (first result) (ref (ring-buffer max-clock-intervals))
                                          (ref (ring-buffer max-clock-intervals))
                                          (ref {:master (get-in @clock-sources [(:device (first result)) :master])}))]
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

;; TODO: apply to all matching input sources? Along with remove, and note & aftertouch messages?
(defn add-control-mapping
  "Register a handler to be called whenever a MIDI controller change
  message is received from the specified device, on the specified
  channel and controller number. A unique key must be given, by which
  this mapping can later be removed. Any subsequent MIDI message which
  matches will be passed to the handler as its single argument. The
  first MIDI input source whose name or description matches the string
  or regex pattern supplied for name-filter will be chosen."
  [name-filter channel control-number k f]
  {:pre [(fn? f)]}
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! control-mappings #(assoc-in % [(:device (first result))
                                          (int channel) (int control-number) (keyword k)] f))))

(defn remove-control-mapping
  "Unregister a handler previously registered with
  [[add-control-mapping]], identified by the unique key. The first MIDI
  input source whose name or description matches the string or regex
  pattern supplied for name-filter will be chosen."
  [name-filter channel control-number k]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! control-mappings #(update-in % [(:device (first result))
                                           (int channel) (int control-number)] dissoc (keyword k)))))

(defn add-note-mapping
  "Register a handler to be called whenever a MIDI note message is
  received from the specified device, on the specified channel and
  note number. A unique key must be given, by which this mapping
  can later be removed. Any subsequent MIDI message which matches will
  be passed to the handler as its single argument. The first MIDI
  input source whose name or description matches the string or regex
  pattern supplied for name-filter will be chosen."
  [name-filter channel note k f]
  {:pre [(fn? f)]}
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! note-mappings #(assoc-in % [(:device (first result))
                                       (int channel) (int note) (keyword k)] f))))

(defn remove-note-mapping
  "Unregister a handler previously registered with [[add-note-mapping]],
  identified by the unique key. The first MIDI input source whose name
  or description matches the string or regex pattern supplied for
  name-filter will be chosen."
  [name-filter channel note k]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! note-mappings #(update-in % [(:device (first result))
                                        (int channel) (int note)] dissoc (keyword k)))))

(defn add-aftertouch-mapping
  "Register a handler to be called whenever a MIDI
  aftertouch (polyphonic key pressure) message is received from the
  specified device, on the specified channel and note number. A unique
  key must be given, by which this mapping can later be removed. Any
  subsequent MIDI message which matches will be passed to the handler
  as its single argument. The first MIDI input source whose name or
  description matches the string or regex pattern supplied for
  name-filter will be chosen."
  [name-filter channel note k f]
  {:pre [(fn? f)]}
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! aftertouch-mappings #(assoc-in % [(:device (first result))
                                             (int channel) (int note) (keyword k)] f))))

(defn remove-aftertouch-mapping
  "Unregister a handler previously registered with [[add-aftertouch-mapping]],
  identified by the unique key. The first MIDI input source whose name
  or description matches the string or regex pattern supplied for
  name-filter will be chosen."
  [name-filter channel note k]
  (let [result (filter-devices (open-inputs-if-needed!) name-filter)]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-name-filter name-filter) "were found."))))
    (swap! aftertouch-mappings #(update-in % [(:device (first result))
                                              (int channel) (int note)] dissoc (keyword k)))))

(defn find-midi-out
  "Find a MIDI output whose name matches the specified string or regex
  pattern."
  [name-filter]
  (let [result (filter-devices (open-outputs-if-needed!) name-filter)]
    (if (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI outputs " (describe-name-filter name-filter) "were found.")))
      (first result))))
