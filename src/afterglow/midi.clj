(ns afterglow.midi
  "Handles MIDI communication, including syncing a show metronome to MIDI clock pulses."
  (:require [afterglow.rhythm :as rhythm]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.math.numeric-tower :as math]
            [overtone.at-at :refer [now]]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre])
  (:import [java.io InputStream]
           [java.util.concurrent LinkedBlockingDeque]
           [java.util.regex Pattern]
           [javax.sound.midi MidiDevice MidiDevice$Info MidiUnavailableException]))

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
  the MIDI environment. They will be passed a single argument, the
  `:midi-device` map from `overtone.midi` representing the new
  device."} new-device-handlers
  (atom #{}))

(defn same-device?
  "Checks whether two `:midi-device` maps seem to refer to the same
  device, in a slightly more efficient way than comparing the entire
  map."
  [a b]
  (= (:info a) (:info b)))

(defn add-new-device-handler!
  "Add a function to be called whenever a new device appears in the
  MIDI environment. It will be passed a single argument, the
  `:midi-device` map from `overtone.midi` representing the new device.
  It must return quickly so as not to stall the delay of other MIDI
  events; lengthy operations must be performed on another thread."
  [f]
  {:pre [(ifn? f)]}
  (swap! new-device-handlers conj f))

(defn remove-new-device-handler!
  "Stop calling the specified function to be called whenever a new
  device appears in the MIDI environment."
  [f]
  {:pre [(ifn? f)]}
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
  {:pre [(= (type device) :midi-device) (ifn? f)]}
  (swap! disconnected-device-handlers #(update-in % [(:info device)] clojure.set/union #{f})))

(defn remove-disconnected-device-handler!
  "No longer call the specified function if specified device
  disappears from the MIDI environment. The `device` argument is a
  `:midi-device` map from `overtone.midi` representing device whose
  removal is no longer of interest."
  [device f]
  {:pre [(= (type device) :midi-device) (ifn? f)]}
  (swap! disconnected-device-handlers #(update-in % [(:info device)] disj f)))

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

(defonce ^{:private true
           :doc "The metronomes which are being synced to MIDI clock
  pulses. A map whose keys are the `MidiDevice.Info` on which clock
  pulses are being received, and whose values are in turn maps whose
  keys are the metronomes being synced to pulses from that device, and
  whose values are the sync function to call when each pulse is
  received."}
  synced-metronomes (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Controller Change
  messages arrive from particular input ports. A set of nested maps
  whose keys are the `MidiDevice.Info` on which the message should
  be watched for, the channel to watch, and the controller number to
  watch for. The values are sets of functions to be called with each
  matching message."}
  control-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Note messages arrive
  from particular input ports. A set of nested maps whose keys are the
  `MidiDevice.Info` on which the message should be watched for, the
  channel to watch, and the note number to watch for. The values are
  sets of functions to be called with each matching message."}
  note-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI Aftertouch
  (polyphonic key pressure) messages arrive from particular input
  ports. A set of nested maps whose keys are the `MidiDevice.Info`
  on which the message should be watched for, the channel to watch,
  and the note number to watch for. The values are sets of functions
  to be called with each matching message."}
  aftertouch-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when MIDI System Exclusive
  messages arrive from particular input ports. A map whose keys are
  the `MidiDevice.Info` on which the message should be watched for.
  The values are sets of functions to be called with each matching
  message."} sysex-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when any MIDI message is
  received from a specific device. A map whose keys are the
  `MidiDevice.Info` on which the message should be watched for, and
  whose values are sets of functions to be called with each matching
  message."}
  device-mappings (atom {}))

(defonce ^{:private true
           :doc "Functions to be called when any MIDI message at all
  is received."}
  global-handlers (atom #{}))

(defn add-global-handler!
  "Add a function to be called whenever any MIDI message is received.
  The function will be called with the message, and must return
  quickly, so as to not block delivery to other recipients."
  [f]
  {:pre [(ifn? f)]}
  (swap! global-handlers conj f))

(defn remove-global-handler!
  "Remove a function that was being called whenever any MIDI message is
  received."
  [f]
  (swap! global-handlers disj f))

(defn- run-message-handler
  "Invokes a registered MIDI message handler function with an incoming
  message that has been identified as appropriate to send to it. If an
  exception is thrown during that invocation, responds appropriately,
  including shutting down the message handler thread if it was
  interrupted. This is done by setting the `running` atom to `false`."
  [handler msg running]
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
      (timbre/error t "Problem running MIDI event handler."))))

(defn- clock-message-handler
  "Invoked whenever any midi input device being managed receives a
  clock message. Checks whether there are any metronomes being synced
  to that device, and if so, passes along the event."
  [msg running]
  (doseq [handler (vals (get @synced-metronomes (:info (:device msg))))]
    (when @running
      (run-message-handler handler msg running))))

(defn- cc-message-handler
  "Invoked whenever any midi input device being managed receives a
  control change message. Checks whether there are any handlers (such
  as for launching cues or mapping show variables) attached to it, and
  if so, calls them."
  [msg running]
  (doseq [handler (get-in @control-mappings [(:info (:device msg)) (:channel msg) (:note msg)])]
    (when @running
      (run-message-handler handler msg running))))

(defn- note-message-handler
  "Invoked whenever any midi input device being managed receives a
  note message. Checks whether there are any handlers (such as for
  launching cues or mapping show variables) attached to it, and if so,
  calls them."
  [msg running]
  (doseq [handler (get-in @note-mappings [(:info (:device msg)) (:channel msg) (:note msg)])]
    (when @running
      (run-message-handler handler msg running))))

(defn- aftertouch-message-handler
  "Invoked whenever any midi input device being managed receives an
  aftertouch (polyphonic key pressure) message. Checks whether there
  are any handlers attached to it, and if so, calls them."
  [msg running]
  (doseq [handler (get-in @aftertouch-mappings [(:info (:device msg)) (:channel msg) (:note msg)])]
    (when @running
      (run-message-handler handler msg running))))

(defn- sysex-message-handler
  "Invoked whenever any midi input device being managed receives a
  System Exclusive message. Checks whether there are any handlers
  attached to it, and if so, calls them."
  [msg running]
  (doseq [handler (get-in @sysex-mappings [(:info (:device msg))])]
    (when @running
      (run-message-handler handler msg running))))

(defonce
  ^{:private true
    :doc "Keeps track of devices sending MIDI clock pulses so they can
  be offered as synchronization sources. Also notes when they seem to
  be sending the additional beat phase information provided by the
  Afterglow Traktor controller mapping. Holds a set of nested maps
  whose top-level keys are the `MidiDevice.info` on which clock pulses
  have been detected, and whose second-level keys can include
  `:timing-clock`, which will store the timestamp of the most-recently
  received clock pulse from that device, `:master`, which will store
  the number of the Traktor deck which was most recently identified as
  the Tempo Master if we are getting messages which seem like they
  could come from the Traktor Afterglow mapping, and
  `:traktor-beat-phase` which will contain the timestamp of the most
  recent value we have received which seems to correct to Traktor beat
  phase information coming from the Traktor Afterglow mapping."}
  clock-sources (atom {}))

(defn- check-for-traktor-beat-phase
  "Examines an incoming MIDI message to see if it seems to be coming
  from the Afterglow Traktor controller mapping, providing beat grid
  information. If so, makes a note of that fact so the clock source
  can be reported as offering this extra feature."
  [msg device-key]
  (when (= (:command msg) :control-change)
    (let [controller (:note msg)]
      (cond (and (get @clock-sources device-key) (zero? controller) (< (:velocity msg) 5))
            (swap! clock-sources assoc-in [device-key :master] (:velocity msg))

            (= controller (get-in @clock-sources [device-key :master]))
            (swap! clock-sources assoc-in [device-key :traktor-beat-phase] (now))))))

(defn- watch-for-clock-sources
  "Examines an incoming MIDI message to see if its source is a
  potential source for MIDI clock synchronization."
  [msg]
  (let [device-key (:info (:device msg))]
    (when (= (:status msg) :timing-clock)
      (swap! clock-sources assoc-in [device-key :timing-clock] (now)))
    (check-for-traktor-beat-phase msg device-key)))

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

(def midi-port-filter
  "Contains a filter which selects midi inputs and outputs we actually
  want to use. The first time the value is dereferenced, a filter
  appropriate to the current environment will be created, and that
  filter will be returned whenever the value is dereferenced again.

  * If this is a Mac, and the CoreMIDI4J MIDI extension is present and
    working, the filter will accept only its devices.

  * Otherwise, if the Humatic MMJ extension is operating (also on a
    Mac), the filter which will accept only MMJ's versions of ports
    which it offers in parallel with the standard implementation, but
    will accept the standard SPI's implementation of ports which MMJ
    does not offer.

  * Otherwise, the filter accepts all ports."
  (delay
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
     identity)))

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
    (swap! midi-inputs assoc (:info opened) opened)
    (midi/midi-handle-events opened incoming-message-handler incoming-message-handler)
    opened))

(defn- connect-midi-out
  "Open a MIDI output device and add it to the map of known outputs."
  [device]
  (let [opened (midi/midi-out device)]
    (timbre/info "Opened MIDI output:" opened)
    (swap! midi-outputs assoc (:info opened) opened)
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
  ;; Don't rescan in an MMJ environment because its device objects are different on each scan,
  ;; and the midi environment can't change under MMJ anyway.
  (when (or (empty? @midi-inputs) (not mac?) (not (mmj-installed?)))
    (doseq [input (filter @midi-port-filter (midi/midi-sources))]
      (when-not (get @midi-inputs (:info input))
        (try
          (let [connected (connect-midi-in input)]
            (doseq [handler @new-device-handlers]
              (handler connected)))
          (catch MidiUnavailableException e)  ; If we can't have it, give up gracefully
          (catch Throwable t
            (timbre/error t "Unable to connect MIDI input" input))))))
  (vals @midi-inputs))

(defn open-outputs-if-needed!
  "Make sure the MIDI output ports are open and ready to receive events.
  Returns the `:midi-device` maps returned by `overtone.midi`
  representing the opened outputs."
  []
  (ensure-threads-running)
  ;; Don't rescan in an MMJ environment because its device objects are different on each scan,
  ;; and the midi environment can't change under MMJ anyway.
  (when (or (empty? @midi-outputs) (not mac?) (not (mmj-installed?)))
    (doseq [output (filter @midi-port-filter (midi/midi-sinks))]
      (when-not (get @midi-outputs (:info output))
        (try
          (let [connected (connect-midi-out output)]
               (doseq [handler @new-device-handlers]
                 (handler connected)))
          (catch MidiUnavailableException e)  ; If we can't have it, give up gracefully
          (catch Throwable t
            (timbre/error t "Unable to connect MIDI output" output))))))
  (vals @midi-outputs))

(defn- lost-midi-in
  "Called when a device in our list of inputs no longer exists in the
  MIDI environment. Removes it from all lists of mappings, synced
  metronomes, handlers, etc. and closes it."
  [vanished]
  {:pre [(= (type vanished) :midi-device)]}
  (let [device-key (:info vanished)]
    (swap! midi-inputs dissoc device-key)
    (swap! synced-metronomes dissoc device-key)
    (swap! device-mappings dissoc device-key)
    (swap! control-mappings dissoc device-key)
    (swap! note-mappings dissoc device-key)
    (swap! aftertouch-mappings dissoc device-key)
    (swap! sysex-mappings dissoc device-key)
    (swap! clock-sources dissoc device-key)
    (swap! disconnected-device-handlers dissoc device-key)
    (.close (:device vanished)))
  (timbre/info "Lost contact with MIDI input: " vanished))

(defn- lost-midi-out
  "Called when a device in our list of outputs no longer exists in the
  MIDI environment. Removes it from all lists of outputs, and closes
  it."
  [vanished]
  {:pre [(= (type vanished) :midi-device)]}
  (let [device-key (:info vanished)]
    (swap! midi-outputs dissoc device-key)
    (swap! disconnected-device-handlers dissoc device-key)
    (.close (:device vanished)))
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
  (doseq [device (clojure.set/difference (set (keys @midi-inputs))
                                         (set (map :info (filter @midi-port-filter (midi/midi-sources)))))]
    (doseq [handler (get @disconnected-device-handlers device)]
      (handler))
    (lost-midi-in (get @midi-inputs device)))
  (doseq [device (clojure.set/difference (set (keys @midi-outputs))
                                         (set (map :info (filter @midi-port-filter (midi/midi-sinks)))))]
    (doseq [handler (get @disconnected-device-handlers device)]
      (handler))
    (lost-midi-out (get @midi-outputs device)))

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
                (run-message-handler handler msg running)))

            ;; Then call any registered port listeners for the port on which it arrived
            (doseq [handler (get-in @device-mappings [(:info (:device msg))])]
              (when @running
                (run-message-handler handler msg running)))

            ;; Then call specific message handlers that match
            (when @running
              (cond (#{:timing-clock :start :stop} (:status msg))
                    (clock-message-handler msg running)

                    (= 0xf0 (:status msg))
                    (sysex-message-handler msg running)

                    :else
                    (case (:command msg)
                      :control-change (do (cc-message-handler msg running)
                                          (clock-message-handler msg running)) ; Might be Traktor beat phase message
                      (:note-on :note-off) (note-message-handler msg running)
                      :poly-pressure (aftertouch-message-handler msg running)
                      nil)))

            ;; Finally, keep track of any MIDI clock messages we have seen so the
            ;; user can be informed of them as potential sync sources.
            (run-message-handler watch-for-clock-sources msg running))))

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
  installed and working, and if so, register our notification handler
  to let us know when the MIDI environment changes, and return the
  value `:environment-changed`. If, on the other hand, MMJ is
  installed, we are in a context where the environment cannot change,
  and if we try to close devices, the VM will crash, so we simply
  return the value `:stop`. Otherwise we create, start, and return a
  thread to periodically scan for such changes."
  [old-thread]
  (or old-thread
      (cond
        (and (clojure.reflect/resolve-class (.getContextClassLoader (Thread/currentThread))
                                            'uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification)
             ;; It's safe to load the namespace; see if the library can give us proactive notifications
             (do (require '[afterglow.coremidi4j])
                 ((resolve 'afterglow.coremidi4j/add-environment-change-handler) environment-changed)))
        :environment-changed

        (and (mac?) (mmj-installed?))
        :stop

        :else
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
  {:pre [(= (type midi-clock-source) :midi-device) (satisfies? rhythm/IMetronome metronome) (ifn? f)]}
  (swap! synced-metronomes #(assoc-in % [(:info midi-clock-source) metronome] f)))

(defn- remove-synced-metronome
  [midi-clock-source metronome]
  {:pre [(= (type midi-clock-source) :midi-device)]}
  (swap! synced-metronomes #(update-in % [(:info midi-clock-source)] dissoc metronome)))

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

(defn describe-device-filter
  "Returns a description of a filter used to narrow down MIDI devices,
  if one was supplied, or the empty string if none was."
  [device-filter]
  (cond
    (or (nil? device-filter) (and (string? device-filter) (clojure.string/blank? device-filter)))
    ""

    (instance? Pattern device-filter)
    (str "with a name or description matching " (with-out-str (clojure.pprint/write-out device-filter)) " ")

    (or (instance? javax.sound.midi.MidiDevice device-filter)
        (instance? javax.sound.midi.MidiDevice$Info device-filter))
    (format "provided by %s " device-filter)

    (= (type device-filter) :midi-device)
    (describe-device-filter (:info device-filter))

    (vector? device-filter)
    (clojure.string/join "or " (map describe-device-filter device-filter))

    (ifn? device-filter)
    (format "returning true when passed to %s " device-filter)

    :else
    (str "with a name or description matching \"" device-filter "\" ")))

(defn filter-devices
  "Return only those devices matching the supplied `device-filter`.

  The elements of `devices` must all be `:midi-device` maps as
  returned by `overtone.midi`.

  If `device-filter` is `nil` or an empty
  string, `devices` is returned unfiltered. Otherwise it can be one of
  the following things:

  * A `java.util.regex.Pattern`, which will be matched against each
  device name and description. If either match succeeds, the device
  will be included in the results.

  * A `String`, which will be turned into a `Pattern` which matches in
  a case-insensitive way, as above. So if the device name or filter
  contains the string, ignoring case, the device will be included.

  * A `javax.sound.midi.MidiDevice` instance, which will match only
  the device that it implements.

  * A `javax.sound.midi.MidiDevice.Info` instance, which will match
  only the device it describes.

  * A `:midi-device` map, which will match only itself.

  * A `vector` of device filters, which will match any device that
  matches any filter.

  * A function, which will be called with each device, and the device
  will be included if the function returns a `true` value.

  Anything else will be converted to a string and matched as above."
  [device-filter devices]
  (cond
    (or (nil? device-filter) (and (string? device-filter) (clojure.string/blank? device-filter)))
    devices

    (instance? javax.sound.midi.MidiDevice device-filter)
    (filter #(= (:device %) device-filter) devices)

    (instance? javax.sound.midi.MidiDevice$Info device-filter)
    (filter #(= (:info %) device-filter) devices)

    (= (type device-filter) :midi-device)
    (filter #(= (:info %) (:info device-filter)) devices)

    (vector? device-filter)
    (filter (fn [device] (some seq (map #(filter-devices % [device]) device-filter))) devices)

    (ifn? device-filter)
    (filter device-filter devices)

    :else
    (let [pattern (if (instance? Pattern device-filter)
                    device-filter
                    (Pattern/compile (Pattern/quote (str device-filter)) Pattern/CASE_INSENSITIVE))]
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
  [[afterglow.show/sync-to-external-clock]].

  The `device-filter` argument is only needed if there is more than
  one connected device sending MIDI clock messages when this function
  is invoked; it will be used to filter the eligible devices
  using [[filter-devices]]. An exception will be thrown if there is
  not exactly one matching eligible MIDI clock source."
  ([]
   (sync-to-midi-clock nil))
  ([device-filter]
   (let [result (filter-devices device-filter (current-clock-sources))]
     (case (count result)
       0 (throw (IllegalArgumentException. (str "No MIDI clock sources " (describe-device-filter device-filter)
                                                "were found.")))

       1 (fn [^afterglow.rhythm.Metronome metronome]
           (let [sync-handler (ClockSync. metronome (first result) (ref (ring-buffer max-clock-intervals))
                                          (ref (ring-buffer max-clock-intervals))
                                          (ref {:master (get-in @clock-sources [(:info (first result))
                                                                                :master])}))]
             (sync-start sync-handler)
             sync-handler))

       (throw (IllegalArgumentException. (str "More than one MIDI clock source " (describe-device-filter device-filter)
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

;; TODO: apply to all matching input sources? Along with remove, and control, note & aftertouch messages?
(defn add-device-mapping
  "Register a handler function `f` to be called whenever any MIDI
  message is received from the specified device. Any subsequent MIDI
  message which matches will be passed to `f` as its single argument.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter f]
  {:pre [(ifn? f)]}
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! device-mappings #(update-in % [(:info (first result))] clojure.set/union #{f}))))

(defn remove-device-mapping
  "Unregister a handler previously registered with
  [[add-device-mapping]].

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter f]
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! device-mappings #(update-in % [(:info (first result))] disj f))))

(defn add-control-mapping
  "Register a handler function `f` to be called whenever a MIDI
  controller change message is received from the specified device, on
  the specified `channel` and `controller` number. Any subsequent MIDI
  message which matches will be passed to `f` as its single argument.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel control-number f]
  {:pre [(ifn? f)]}
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! control-mappings #(update-in % [(:info (first result)) (int channel) (int control-number)]
                                        clojure.set/union #{f}))))

(defn remove-control-mapping
  "Unregister a handler previously registered with
  [[add-control-mapping]].

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel control-number f]
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! control-mappings #(update-in % [(:info (first result)) (int channel) (int control-number)]
                                        disj f))))

(defn add-note-mapping
  "Register a handler function `f` to be called whenever a MIDI note
  message is received from the specified device, on the specified
  `channel` and `note` number. Any subsequent MIDI message which
  matches will be passed to `f` as its single argument.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel note f]
  {:pre [(ifn? f)]}
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! note-mappings #(update-in % [(:info (first result)) (int channel) (int note)]
                                     clojure.set/union #{f}))))

(defn remove-note-mapping
  "Unregister a handler previously registered
  with [[add-note-mapping]].

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel note f]
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! note-mappings #(update-in % [(:info (first result)) (int channel) (int note)] disj f))))

(defn add-aftertouch-mapping
  "Register a handler function `f` to be called whenever a MIDI
  aftertouch (polyphonic key pressure) message is received from the
  specified device, on the specified `channel` and `note` number. Any
  subsequent MIDI message which matches will be passed to `f` as its
  single argument.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel note f]
  {:pre [(ifn? f)]}
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! aftertouch-mappings #(update-in % [(:info (first result)) (int channel) (int note)]
                                           clojure.set/union #{f}))))

(defn remove-aftertouch-mapping
  "Unregister a handler previously registered
  with [[add-aftertouch-mapping]].

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter channel note f]
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! aftertouch-mappings #(update-in % [(:info (first result)) (int channel) (int note)] disj f))))

(defn add-sysex-mapping
  "Register a handler function `f` to be called whenever a MIDI System
  Exclusive message is received from the specified device. Any
  subsequent MIDI message which matches will be passed to `f` as its
  single argument.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter f]
  {:pre [(ifn? f)]}
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! sysex-mappings #(update-in % [(:info (first result))] clojure.set/union #{f}))))

(defn remove-sysex-mapping
  "Unregister a handler previously registered
  with [[add-sysex-mapping]].

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen."
  [device-filter f]
  (let [result (filter-devices device-filter (open-inputs-if-needed!))]
    (when (empty? result)
      (throw (IllegalArgumentException. (str "No MIDI sources " (describe-device-filter device-filter) "were found."))))
    (swap! sysex-mappings #(update-in % [(:info (first result))] disj f))))

(defn watch-for
  "Watches for a device that matches
  `device-filter` (using [[filter-devices]]). If it is present when
  this function is called, or whenever a matching device is connected
  in the future (as long as none already was), `found-fn` will be
  called, with no arguments. This is useful for setting up MIDI
  mappings to the device whenever it is present. If the device is
  disconnected and later reconnected, `found-fn` will be called again,
  so those bindings can be counted on to be present whenver the device
  is available.

  If there is any cleanup that you need to perform when the device is
  disconnected, you can pass the optional keyword argument `:lost-fn`
  along with a function to be called (also with no arguments) whenever
  a device reported by `found-fn` has disappeared. You do not need to
  use `:lost-fn` to clean up MIDI bindings created by `found-fn`,
  because Afterglow automatically cleans up any MIDI bindings for
  devices which have been disconnected. But if you have your own data
  structures or state that you want to update, you can use `:lost-fn`
  to do that.

  In order to give the newly-attached device time to stabilize before
  trying to send messages to it, `watch-for` waits for a second after
  it is seen before calling `found-fn`. If your device needs more (or
  less) time to stabilize, you can pass a number of milliseconds after
  the optional keyword argument `:sleep-time` to configure this delay.

  The return value of `watch-for` is a function that you can call to
  cancel the watcher if you no longer need it."
  [device-filter found-fn & {:keys [lost-fn sleep-time] :or {sleep-time 1000}}]
  {:pre [(ifn? found-fn) (or (nil? lost-fn) (ifn? lost-fn)) (number? sleep-time)]}
  (let [found (atom false)]
    (letfn [(disconnection-handler []
              (when lost-fn (lost-fn))
              (reset! found false))
            (connection-handler [device]
              (when (and (seq (filter-devices device-filter [device])) (compare-and-set! found false true))
                (timbre/info "watch-for found device" (describe-device-filter device-filter))
                (future
                  (Thread/sleep sleep-time)
                  (found-fn))
                (add-disconnected-device-handler! device disconnection-handler)))
            (cancel-handler []
              (remove-new-device-handler! connection-handler))]

      ;; See if the specified device seems to already be connected, and if so, bind to it right away.
      (when-let [match (first (filter-devices device-filter (concat (open-inputs-if-needed!)
                                                                    (open-outputs-if-needed!))))]
        (connection-handler match))

      ;; Set up to bind when connected in the future
      (add-new-device-handler! connection-handler)

      ;; Return the function that will cancel this watcher
      cancel-handler)))

(defn find-midi-in
  "Find the first MIDI input port matching the specified
  `device-filter` using [[filter-devices]], or throw an exception if
  no matches can be found. The exception can be suppressed by passing
  a false value for the optional second argument `required`."
  ([device-filter]
   (find-midi-in device-filter true))
  ([device-filter required]
   (let [result (filter-devices device-filter (open-inputs-if-needed!))]
     (if (empty? result)
       (when required
         (throw (IllegalArgumentException. (str "No MIDI inputs " (describe-device-filter device-filter)
                                                "were found."))))
       (first result)))))

(defn find-midi-out
  "Find the first MIDI output port matching the specified
  `device-filter` using [[filter-devices]], or throw an exception if
  no matches can be found. The exception can be suppressed by passing
  a false value for the optional second argument `required`."
  ([device-filter]
   (find-midi-out device-filter true))
  ([device-filter required]
   (let [result (filter-devices device-filter (open-outputs-if-needed!))]
     (if (empty? result)
       (when required
         (throw (IllegalArgumentException. (str "No MIDI outputs " (describe-device-filter device-filter)
                                                "were found."))))
       (first result)))))
