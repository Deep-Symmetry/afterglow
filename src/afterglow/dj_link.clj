(ns afterglow.dj-link
  "Provides synchronization with equipment sending Pioneer Pro DJ Link
  packets on the local network, such as Pioneer Nexus mixers and players.
  This is purely experimental and based on network traffic capture, but
  unless they change the protocol, it works really well, providing rock
  solid BPM, beat, and measure-phase tracking."
  (:require [afterglow.midi :refer [IClockSync sync-start sync-stop sync-status]]
            [afterglow.rhythm :as rhythm]
            [afterglow.util :refer [unsign]]
            [overtone.at-at :refer [now]]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress DatagramPacket DatagramSocket]
           [java.util.regex Pattern]))

(def port
  "The UDP port on which BPM and beat packets are broadcast."
  50001)

(defonce ^{:private true
           :doc "Holds the persistent server socket, map of seen sync
  sources (keyed on the address from which they transmit), set of
  active synced metronomes, and the future that processes packets."}
  state (atom {:socket nil
               :watcher nil
               :sources-seen {}
               :synced-metronomes #{}}))

(defn shut-down
  "Close the UDP server socket and terminate the packet processing
  thread, if they are active."
  []
  (swap! state (fn [current]
                 (-> current
                     (update-in [:socket] #(when %
                                             (try (.close %)
                                                  (catch Exception e
                                                    (timbre/warn e "Problem closing DJ-Link socket.")))
                                             nil))
                     (update-in [:watcher] #(when %
                                              (try (future-cancel %)
                                                   (catch Exception e
                                                     (timbre/warn e "Problem stopping DJ-Link processor.")))
                                              nil))))))

(defn- receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload packet."
  [^DatagramSocket socket]
  (let [buffer (byte-array 512)
        packet (DatagramPacket. buffer 512)]
    (try (.receive socket packet)
         packet
         (catch Exception e
           (timbre/warn e "Problem reading from DJ Link socket, shutting down.")
           (shut-down)))))

(def max-packet-interval
  "The number of milliseconds after which we will consider a source to
  have disappeared if we have not heard any packets from it."
  5000)

(defn- remove-stale-sources
  "Age out any DJ Link sources we have not heard from in too long."
  []
  (doseq [[k v] (:sources-seen @state)]
    (when (> (- (now) (:last-seen v)) max-packet-interval)
      (swap! state update-in [:sources-seen] dissoc k))))

(defn- update-known-sources
  "When a packet has been received, if it looks like a Pro DJ Link
  packet, this function is called to note that the sender is a current
  candidate sync source."
  [packet data]
  ;; Record the newly-seen source
  (swap! state update-in [:sources-seen (.getAddress packet)]
         (fn [source]
           {:packet-count (inc (:packet-count source 0))
            :last-seen (now)
            :source {:name (.trim (String. data 11 20))
                     :player (aget data 33)
                     :address (.getAddress packet)}}))
  (remove-stale-sources))

(defn- update-synced-metronomes
  "When a packet has been received, if it looks like a Pro DJ Link
  packet, this function is called to update any synced metronomes
  attached to the same source as the packet."
  [packet data]
  (let [source (:source (get (:sources-seen @state) (.getAddress packet)))
        bpm (float (/ (+ (* 256 (unsign (aget data 90)))
                         (unsign (aget data 91))) 100))
        beat (aget data 92)]
    (doseq [listener (:synced-metronomes @state)]
      (when (= (:source listener) source)
        (rhythm/metro-bpm (:metronome listener) bpm)
        (rhythm/metro-beat-phase (:metronome listener) 0)
        (dosync
         (alter (:sync-count listener) inc))
        ;; TODO: Had to give up for now on the following line because the mixer
        ;;       does not update its measure position based on that of the master
        ;;       player.
        ;;
        ;;       Consider figuring out how to tell what the master player is from
        ;;       the UDP traffic, and using that. But this should probably be an
        ;;       option anyway because a lot of DJs do not beat grid that carefully,
        ;;       and the light show operator will in those cases need to be able to
        ;;       override the down beat, which will not be possible if this code is
        ;;       resyncing the bar start on each beat from the Pioneer equipment.
        ;;
        ;;       If/when that mode is figured out and made an option, the following
        ;;       line will be invoked INSTEAD of the metro-beat-phase one above.
        #_(rhythm/metro-bar-phase (:metronome (/ (dec beat) 4)))))))

(defn start
  "Make sure the UDP server socket is open and the packet reception
  thread is running, starting fresh if need be."
  []
  (shut-down)
  (try
    (swap! state
           (fn [current]
             (let [socket (DatagramSocket. port (InetAddress/getByName "0.0.0.0"))]
               (-> current
                   (assoc :socket socket)
                   (assoc :watcher
                          (future (loop []
                                    (let [packet (receive socket)
                                          data (.getData packet)]
                                      ;; Check packet length
                                      ;; TODO: Check header bytes, perhaps use Protobuf for this?
                                      (when (= 96 (.getLength packet))
                                        (update-known-sources packet data)
                                        (update-synced-metronomes packet data)))
                                    (recur))))))))
    (catch Exception e
      (timbre/warn e "Failed while trying to set up DJ-Link reception.")
      (shut-down))))

(defn start-if-needed
  "If there is not already a socket receiving DJ Link packets, set it
  up."
  []
  (when-not (:socket @state) (start)))

;; A simple object which supports syncing a metronome to Pro DJ Link packets
;; received from a particular source
(defrecord UDPSync
 [metronome source sync-count]
  IClockSync
  (sync-start [this]
    (start-if-needed)
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
     (let [source-info (get-in @state [:sources-seen (:address source)])
           running (some? @sync-count)
           current (and running (<= (- (now) (:last-seen source-info)) 1000))]
       {:type :dj-link,
        :current current
        :level :beat
        :source (:source source-info)
        :status (cond
                  (not running)
                  "Stopped."

                  (zero? (:packet-count source-info))
                  "Network problems? No DJ Link packets received."
                  
                  (zero? @sync-count)
                  (str "Configuration problem? No DJ Link beat packets received from "
                       (:name source) ".")

                  (not current)
                  (str "Stalled? No sync packets received in " (- (now) (:last-seen source-info)) "ms.")
                  
                  :else
                  (str "Running. " @sync-count " beats received."))}))))

;; Suppress uninformative auto-generated documentation entries.
(alter-meta! #'->UDPSync assoc :no-doc true)
(alter-meta! #'map->UDPSync assoc :no-doc true)

(defn current-dj-link-sources
  "Returns the set of potential Pro DJ Link synchronization sources
  which are currently visible on the network."
  []
  (start-if-needed)
  (remove-stale-sources)
  (set (for [[k v] (:sources-seen @state)] (:source v))))

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
  [[show/sync-to-external-clock]]."
  {:doc/format :markdown}
  [dj-link-source]
  (fn [^afterglow.rhythm.Metronome metronome]
    (let [source (if (map? dj-link-source)
                   (find-source dj-link-source)
                   (find-source-by-name dj-link-source))
          sync-handler (UDPSync. metronome source (ref nil))]
      (sync-start sync-handler)
      sync-handler)))


