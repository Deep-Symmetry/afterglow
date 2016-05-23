(ns afterglow.dj-link
  "Provides synchronization with equipment sending Pioneer Pro DJ Link
  packets on the local network, such as Pioneer Nexus mixers and players,
  using the beat-link library."
  (:require [afterglow.midi :refer [IClockSync sync-start sync-stop sync-status]]
            [afterglow.rhythm :as rhythm]
            [afterglow.util :refer [unsign]]
            [overtone.at-at :refer [now]]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress DatagramPacket DatagramSocket]
           [java.util.regex Pattern]
           [org.deepsymmetry.beatlink DeviceFinder BeatFinder VirtualCdj Beat]))

(defonce ^{:private true
           :doc "Holds the set of active synced metronomes, and a map
  that tracks the most recent beat received from a given IP address."}
  state (atom {:synced-metronomes #{}
               :beats-seen {}}))

(defn shut-down
  "Shut down the beat-link library."
  []
  (BeatFinder/stop)
  (VirtualCdj/stop)
  (DeviceFinder/stop))

(defn- apply-beat-to-synced-metronomes
  "When a beat notification is received, this function is called to
  update any synced metronomes attached to the device announcing the
  beat."
  [beat]
  (swap! state assoc-in [:beats-seen (.getAddress beat)] beat)
  (doseq [listener (:synced-metronomes @state)]
    ;; TODO: Handle :master as a special virtual source
    (when (= (:address (:source listener)) (.getAddress beat))
      (rhythm/metro-bpm (:metronome listener) (.getEffectiveTempo beat))
      (if (= (:level listener) :bar)
        (rhythm/metro-bar-phase (:metronome (/ (dec (.getBeatWithinBar beat)) 4)))
        (rhythm/metro-beat-phase (:metronome listener) 0))
      (dosync
       (alter (:sync-count listener) inc)))))

;; Register our beat handler with the beat-link library
(defonce ^:private beat-listener
  (reify org.deepsymmetry.beatlink.BeatListener
    (newBeat [this beat]
      (apply-beat-to-synced-metronomes beat))))
(BeatFinder/addBeatListener beat-listener)

(defn start
  "Activate all the components of the beat-link library.
  Runs in the background, since it can take a while to give up if
  there are no DJ Link devices on the network."
  []
  (future
    (try
      (DeviceFinder/start)
      (VirtualCdj/start)
      (BeatFinder/start)
      (catch Exception e
        (timbre/warn e "Failed while trying to set up beat-finder DJ-Link integration.")
        (shut-down)))))

;; A simple object which supports syncing a metronome to Pro DJ Link beats
;; received from a particular source, optionally honoring the beat-within-bar
(defrecord UDPSync
 [metronome source sync-count level]
  IClockSync
  (sync-start [this]
    (start)
    (dosync
     (ref-set sync-count 0))
    (swap! state update-in [:synced-metronomes] conj this))
  (sync-stop [this]
    (swap! state update-in [:synced-metronomes] disj this)
    (dosync
     (ref-set sync-count nil)))
  (sync-status [this]
    (dosync
     (ensure sync-count)
     (let [latest-beat (get-in @state [:beats-seen (:address source)])
           latest-time (if (some? latest-beat) (.getTimestamp latest-beat) 0)
           running (some? @sync-count)
           current (and running (<= (- (now) latest-time) 1500))]
       {:type :dj-link,
        :current current
        :level level
        :source source
        :status (cond
                  (not running)
                  "Stopped."

                  (or (nil? latest-beat) (zero? @sync-count))
                  (str "Not playing? No DJ Link beats received from " (:name source) ".")

                  (not current)
                  (str "Stalled? No beats received in " (- (now) latest-time) "ms.")
                  
                  :else
                  (str "Running. " @sync-count " beats received."))}))))

;; Suppress uninformative auto-generated documentation entries.
(alter-meta! #'->UDPSync assoc :no-doc true)
(alter-meta! #'map->UDPSync assoc :no-doc true)

(defn current-dj-link-sources
  "Returns the set of potential Pro DJ Link synchronization sources
  which are currently visible on the network, summarized as maps
  containing the device name, player number, and IP address."
  []
  (when-not (DeviceFinder/isActive)
    (start))
  (set (for [device (DeviceFinder/currentDevices)]
         {:name (.getName device)
          :player (.getNumber device)
          :address (.getAddress device)})))

(defn filter-sources
  "Return a set of only those sources whose name matches the specified
  pattern. name-filter can either be a Pattern, or a string which will
  be turned into a pattern which matches in a case-insensitive way
  anywhere in the name."
  [name-filter]
  (if (or (nil? name-filter) (and (string? name-filter) (clojure.string/blank? name-filter)))
    (current-dj-link-sources)
    (let [pattern (if (= (class name-filter) Pattern)
                    name-filter
                    (Pattern/compile (Pattern/quote (str name-filter)) Pattern/CASE_INSENSITIVE))]
      (filter #(re-find pattern (:name %)) (current-dj-link-sources)))))

(defn find-source-by-name
  "Looks up a source with a name that matches the specified pattern.
  name-filter can either be a Pattern, or a string which will be
  turned into a pattern which matches in a case-insensitive way
  anywhere in the name. Returns the single matching source found, or
  throws an exception."
  [name-filter]
  (let [result (filter-sources name-filter)]
    (case (count result)
      1 (first result)
      0 (throw (Exception. (str "No DJ Link source found matching " name-filter)))
      (throw (Exception. (str "More than one DJ Link source matches " name-filter))))))

(defn find-source
  "Makes sure the supplied DJ Link source is current and valid.
  Returns it, or throws an exception."
  [source]
  (or ((current-dj-link-sources) source)
      (throw (Exception. "Not a valid DJ Link source:" source))))

(defn sync-to-dj-link
  "Returns a sync function that will cause the beats-per-minute
  setting of the supplied metronome to track the values received from
  the specified DJ Link transmitter on the local network. The sync
  source can be specified either as a map that would be returned from
  [[current-dj-link-sources]], or a regex `Pattern`, or a simple
  string, which will be converted into a case-insensitve pattern
  matching anywhere in the source name. This method is intended for
  use with
  [[show/sync-to-external-clock]].

  If `sync-bars?` is passed with a `true` value, then synchronization
  will honor the beat-within-bar information coming from the DJ Link
  device. Otherwise, sync will be at the beat level only."
  ([dj-link-source]
   (sync-to-dj-link dj-link-source false))
  ([dj-link-source sync-bars?]
   (fn [^afterglow.rhythm.Metronome metronome]
     (let [source (if (map? dj-link-source)
                    (find-source dj-link-source)
                    (find-source-by-name dj-link-source))
           sync-handler (UDPSync. metronome source (ref nil) (if sync-bars? :bar :beat))]
       (sync-start sync-handler)
       sync-handler))))


