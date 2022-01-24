(ns afterglow.carabiner
  "Provides synchronization with Ableton Link on the local network,
  using the lib-carabiner library."
  (:require [afterglow.midi :refer [IClockSync sync-start sync-stop sync-status]]
            [afterglow.rhythm :as rhythm]
            [afterglow.util :refer [unsign]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [overtone.at-at :refer [now]]
            [taoensso.timbre :as timbre])
  (:import [java.net Socket InetSocketAddress]
           [org.deepsymmetry.libcarabiner Runner]
           [org.deepsymmetry.electro Metronome Snapshot]))

(defonce ^{:private true
           :doc "Holds the set of active synced metronomes."}
  synced-metronomes (atom #{}))

(def carabiner-runner
  "The [`Runner`](https://deepsymmetry.org/lib-carabiner/apidocs/org/deepsymmetry/libcarabiner/Runner.html)
  singleton that can manage an embedded Carabiner instance for us."
  (Runner/getInstance))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, values which track the peer count and tempo reported
  by the Ableton Link session, and the `:running` flag which can be
  used to gracefully terminate that thread.

  If we used lib-carabiner to start an embedded instance of Carabiner,
  then `:embedded` will be `true` to let us know it should be stopped
  when we disconnect.

  The `:last` entry is used to assign unique integers to each
  `:running` value as we are started and stopped, so a leftover
  background thread from a previous run can know when it is stale and
  should exit.)

  Once we are connected to Carabiner, the current Link session tempo
  will be available under the key `:link-bpm`."}

  client (atom {:port 17000
                :last      0
                :embedded  false}))

(def connect-timeout
  "How long the connection attempt to the Carabiner daemon can take
  before we give up on being able to reach it."
  5000)

(def read-timeout
  "How long reads from the Carabiner daemon should block so we can
  periodically check if we have been instructed to close the
  connection."
  2000)

(defn state
  "Returns the current state of the Carabiner connection as a map whose
  keys include:

  `:port`, the port on which the Carabiner daemon is listening.

  `:running` will have a non-`nil` value if we are connected to
  Carabiner. Once we are connected to Carabiner, the current Link
  session tempo will be available under the key `:link-bpm` and the
  number of Link peers under `:link-peers`."
  []
  (select-keys @client [:port :running :link-bpm :link-peers]))

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

(defn set-carabiner-port
  "Sets the port to be uesd to connect to Carabiner. Can only be called
  when not connected."
  [port]
  (when (active?)
    (throw (IllegalStateException. "Cannot set port when already connected.")))
  (when-not (<= 1 port 65535)
    (throw (IllegalArgumentException. "port must be in range 1-65535")))
  (swap! client assoc :port port))

(defn- ensure-active
  "Throws an exception if there is no active connection."
  []
  (when-not (active?)
    (throw (IllegalStateException. "No active Carabiner connection."))))

(defn- send-message
  "Sends a message to the active Carabiner daemon."
  [message]
  (ensure-active)
  (let [output-stream (.getOutputStream ^Socket (:socket @client))]
    (.write output-stream (.getBytes (str message "\n") "UTF-8"))
    (.flush output-stream)))

(def ^{:private true
       :doc "Functions to be called with the updated client state
  whenever we have processed a status update from Carabiner."}

  status-listeners (atom #{}))

(defn add-status-listener
  "Registers a function to be called with the updated client state
  whenever we have processed a status update from Carabiner. When that
  happens, `listener` will be called with a single argument
  containing the same map that would be returned by calling [[state]]
  at that moment.
  This registration can be reversed by
  calling [[remove-status-listener]]."
  [listener]
  (swap! status-listeners conj listener))

(defn remove-status-listener
  "Removes a function from the set that is called whenever we have
  processed a status update from Carabiner. If `listener` had been
  passed to [[add-status-listener]], it will no longer be called."
  [listener]
  (swap! status-listeners disj listener))

(defn- send-status-updates
  "Calls any registered status listeners with the current client state."
  []
  (when-let [listeners (seq @status-listeners)]
      (let [updated (state)]
        (doseq [listener listeners]
          (try
            (listener updated)
            (catch Throwable t
              (timbre/error t "Problem running status-listener.")))))))

(defn- handle-status
  "Processes a status update from Carabiner. Calls any registered status
  listeners with the resulting state."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers)
    (send-status-updates)))

(defn- handle-phase-at-time
  "Processes a phase probe response from Carabiner."
  [info]
  (let [state                            @client
        [ableton-now ^Snapshot snapshot] (:phase-probe state)
        align-to-bar                     (:bar state)]
    (if (= ableton-now (:when info))
      (let [desired-phase  (if align-to-bar
                             (/ (:phase info) 4.0)
                             (- (:phase info) (long (:phase info))))
            actual-phase   (if align-to-bar
                             (.getBarPhase snapshot)
                             (.getBeatPhase snapshot))
            phase-delta    (Metronome/findClosestDelta (- desired-phase actual-phase))
            phase-interval (if align-to-bar
                             (.getBarInterval snapshot)
                             (.getBeatInterval snapshot))
            ms-delta       (long (* phase-delta phase-interval))]
        ;; TODO: Rewrite this in the context of Afterglow.
        (when (pos? (Math/abs ms-delta))
          ;; We should shift the Pioneer timeline. But if this would cause us to skip or repeat a beat, and we
          ;; are shifting less 1/5 of a beat or less, hold off until a safer moment.
          #_(let [beat-phase (.getBeatPhase (.getPlaybackPosition ^VirtualCdj virtual-cdj))
                beat-delta (if align-to-bar (* phase-delta 4.0) phase-delta)
                beat-delta (if (pos? beat-delta) (+ beat-delta 0.1) beat-delta)]  ; Account for sending lag.
            (when (or (zero? (Math/floor (+ beat-phase beat-delta)))  ; Staying in same beat, we are fine.
                      (> (Math/abs beat-delta) 0.2))  ; We are moving more than 1/5 of a beat, so do it anyway.
              (timbre/info "Adjusting Pioneer timeline, delta-ms:" ms-delta)
              (.adjustPlaybackPosition ^VirtualCdj virtual-cdj ms-delta)))))
      (timbre/warn "Ignoring phase-at-time response for time" (:when info) "since was expecting" ableton-now))))

(defn- handle-version
  "Processes the response to a recognized version command. Warns if
  Carabiner should be upgraded."
  [version]
  (timbre/info "Connected to Carabiner daemon, version:" version)
  (when (= version "1.1.0")
    (timbre/warn "Carabiner needs to be upgraded to at least version 1.1.1 to avoid sync glitches.")))

(defn- handle-unsupported
  "Processes an unsupported command reponse from Carabiner."
  [command]
  (timbre/error "Carabiner complained about not recognizing our command:" command))

(defn- shutdown-embedded-carabiner
  "If the client settings indicate we started the Carabiner server we
  are disconnecting from, shut it down."
  ([]
   (shutdown-embedded-carabiner @client))
  ([settings]
   (when (:embedded settings)
     (.stop ^Runner carabiner-runner))))

(defonce ^{:doc "Cleans up any embedded Carabiner instance we launched when Java is
shutting down."
           :private true}
  shutdown-hook
  (let [hook (Thread. shutdown-embedded-carabiner)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [^Socket socket running]
  (let [unexpected? (atom false)]  ; Tracks whether Carabiner unexpectedly closed the connection from its end.
    (try
      (let [buffer      (byte-array 1024)
            input       (.getInputStream socket)]
        (while (and (= running (:running @client)) (not (.isClosed socket)))
          (try
            (let [n (.read input buffer)]
              (if (and (pos? n) (= running (:running @client)))  ; We got data, and were not shut down while reading
                (let [message (String. buffer 0 n "UTF-8")
                      reader  (java.io.PushbackReader. (io/reader (.getBytes message "UTF-8")))]
                  (timbre/debug "Received:" message)
                  (loop [cmd (edn/read reader)]
                    (case cmd
                      status        (handle-status (clojure.edn/read reader))
                      phase-at-time (handle-phase-at-time (clojure.edn/read reader))
                      version       (handle-version (clojure.edn/read reader))
                      unsupported   (handle-unsupported (clojure.edn/read reader))
                      (timbre/error "Unrecognized message from Carabiner:" message))
                    (let [next-cmd (clojure.edn/read {:eof ::eof} reader)]
                      (when (not= ::eof next-cmd)
                        (recur next-cmd)))))
                (do  ; We read zero, meaning the other side closed, or we have been instructed to terminate.
                  (.close socket)
                  (reset! unexpected? (= running (:running @client))))))
            (catch java.net.SocketTimeoutException _
              (timbre/debug "Read from Carabiner timed out, checking if we should exit loop."))
            (catch Throwable t
              (timbre/error t "Problem reading from Carabiner.")))))
      (timbre/info "Ending read loop from Carabiner.")
      (swap! client (fn [oldval]
                      (if (= running (:running oldval))
                        (do  ; We are causing the ending.
                          (shutdown-embedded-carabiner oldval)
                          (dissoc oldval :running :embedded :socket :link-bpm :link-peers))
                        oldval)))  ; Someone else caused the ending, so leave client alone; may be new connection.
      (.close socket)  ; Either way, close the socket we had been using to communicate, and update the window state.
      (catch Throwable t
        (timbre/error t "Problem managing Carabiner read loop.")))))

(defn disconnect
  "Closes any active Carabiner connection. The run loop will notice that
  its run ID is no longer current, and gracefully terminate, closing
  its socket without processing any more responses. Also shuts down
  the embedded Carabiner process if we started it."
  []
  (swap! client (fn [oldval]
                  (shutdown-embedded-carabiner oldval)
                  (dissoc oldval :running :embedded :socket :link-bpm :link-peers))))

(defn- connect-internal
  "Helper function that attempts to connect to the Carabiner daemon with
  a particular set of client settings, returning them modified to
  reflect the connection if it succeeded. If the settings indicate we
  have just started an embedded Carabiner instance, keep trying to
  connect every ten milliseconds for up to two seconds, to give it a
  chance to come up."
  [settings]
  (let [running (inc (:last settings))
        socket  (atom nil)
        caught  (atom nil)]
    (loop [tries 200]
      (try
        (reset! socket (Socket.))
        (reset! caught nil)
        (.connect ^Socket @socket (InetSocketAddress. "127.0.0.1" (int (:port settings))) connect-timeout)
        (catch java.net.ConnectException e
          (reset! caught e)))
      (when @caught
        (if (and (:embedded settings) (pos? tries))
          (do
            (Thread/sleep 10)
            (recur (dec tries)))
          (throw @caught))))
    ;; We have connected successfully!
    (.setSoTimeout ^Socket @socket read-timeout)
    (future (response-handler @socket running))
    (merge settings {:running running
                     :last    running
                     :socket  @socket})))

(defn connect
  "Try to establish a connection to Carabiner. First checks if there is
  already an independently managed instance of Carabiner running on
  the configured port (see [[set-carabiner-port]]), and if so, simply
  uses that. Otherwise, checks whether we are on a platform where we
  can install and run our own temporary copy of Carabiner. If so,
  tries to do that and connect to it.

  Returns truthy if the initial open succeeded. Sets up a background
  thread to reject the connection if we have not received an initial
  status report from the Carabiner daemon within a second of opening
  it."
  []
  (swap! client (fn [oldval]
                  (if (:running oldval)
                    oldval
                    (try
                      (try
                        (connect-internal oldval)
                        (catch java.net.ConnectException e
                          ;; If we couldn't connect, see if we can run Carabiner ourselves and try again.
                          (if (.canRunCarabiner ^Runner carabiner-runner)
                            (do
                              (.setPort ^Runner carabiner-runner (:port oldval))
                              (.start ^Runner carabiner-runner)
                              (connect-internal (assoc oldval :embedded true)))
                            (throw e))))
                      (catch Exception e
                        (timbre/warn e "Unable to connect to Carabiner")
                        oldval)))))
  (when (active?)
    (future
      (Thread/sleep 1000)
      (if (:link-bpm @client)
        (do ; We are connected! Check version and configure for start/stop sync.
          (send-message "version") ; Probe that a recent enough version is running.
          (send-message "enable-start-stop-sync")) ; Set up support for start/stop triggers.
        (do ; We failed to get a response, maybe we are talking to the wrong process.
          (timbre/warn "Did not receive inital status packet from Carabiner daemon; disconnecting.")
          (disconnect)))))
  (active?))



;; TODO: This section needs to be rewritten to instead align all synced metronomes.
;; Store them and all their snapshots?

#_(defn- align-pioneer-phase-to-ableton
  "Send a probe that will allow us to align the Virtual CDJ timeline to
  Ableton Link's."
  []
  (let [ableton-now (+ (long (/ (System/nanoTime) 1000)) (* (:latency @client) 1000))
        snapshot    (.getPlaybackPosition ^VirtualCdj virtual-cdj)]
    (swap! client assoc :phase-probe [ableton-now snapshot])
    (send-message (str "phase-at-time " ableton-now " 4.0"))))

#_(defonce ^{:private true
           :doc "A daemon thread that periodically aligns the Pioneer phase to the
  Ableton Link session when the sync mode requires it."}

  full-sync-daemon
  (Thread. (fn []
             (loop []
               (try
                 ;; If we are due to send a probe to align the Virtual CDJ timeline to Link's, do so.
                 (when (and (= :full (:sync-mode @client)) (.isTempoMaster ^VirtualCdj virtual-cdj))
                   (align-pioneer-phase-to-ableton))
                 (Thread/sleep 200)
                 (catch Exception e
                   (timbre/error e "Problem aligning DJ Link phase to Ableton Link.")))
               (recur)))
           "Beat Carabiner Phase Alignment"))

#_(let [^Thread daemon full-sync-daemon]
  (when-not (.isAlive daemon)
    (.setPriority daemon Thread/MIN_PRIORITY)
    (.setDaemon daemon true)
    (.start daemon)))



;; TODO: Finish copying relevant sections of dj_link.clj to implement this sort of syncing,
;;       and update show.clj to be able to use it. Once that is working programatically,
;;   [x] update web/routes/show_control.clj to be able to offer Ableton Link in the Sync menu.
;;   [x] Always offer Ableton Link as a sync option if Carabiner connected successfully. (done!)
;;   [ ] Figure out where that menu gets handled and implement the request to sync to Ableton.
;;   [ ] Start Carabiner if possible when starting up a show.

;; TODO: Is there an interface for choosing sync on the Push? If so add support there too.
