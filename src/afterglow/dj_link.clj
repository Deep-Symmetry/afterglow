(ns afterglow.dj-link
  "Provides synchronization with equipment sending Pioneer Pro DJ Link
  packets on the local network, such as Pioneer Nexus mixers and players.
  This is purely experimental and based on network traffic capture, but
  unless they change the protocol, it works really well, providing rock
  solid BPM, beat, and measure-phase tracking."
  (:require [afterglow.midi :refer [IClockSync sync-start sync-stop sync-status
                                    IClockFinder finder-current-sources finder-finished]]
            [afterglow.rhythm :refer :all]
            [afterglow.util :refer [unsign]]
            [overtone.at-at :refer [now]]
            [taoensso.timbre :refer [info]])
  (:import [java.net InetAddress DatagramPacket DatagramSocket]))

(def port
  "The UDP port on which BPM and beat packets are broadcast."
  50001)

(def sources-seen
  "Tracks the names of devices from which we are receiving Pro DJ Link
  packets."
  (atom #{}))

;; TODO: Refactor to use a single global socket and watcher, so we can sync multiple
;;       sources at once.

(defn- receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload packet."
  [^DatagramSocket socket]
  (let [buffer (byte-array 512)
        packet (DatagramPacket. buffer 512)]
    (.receive socket packet)
    packet))

;; A simple object which holds the UDP server for receiving DJ Link packets,
;; and tying them to the metronome driving the timing of a light show.
(defrecord UDPSync
 [metronome target-name socket watcher packet-count sync-count last-sync]
  IClockSync
  (sync-start [this]
    (dosync
     (alter socket (fn [s]
                     (when s (.close s))
                     (DatagramSocket. port (InetAddress/getByName "0.0.0.0"))))
     (ref-set packet-count 0)
     (ref-set sync-count 0)
     (ref-set last-sync nil)
     (alter watcher (fn [w]
                      (when w (future-cancel w))
                      (future (while true
                                (let [packet (receive @socket)
                                      data (.getData packet)]
                                  ;; Check packet length and target name.
                                  ;; TODO: Check header bytes, perhaps use Protobuf for this?
                                  (dosync
                                   (alter packet-count inc)
                                   (when (= 96 (.getLength packet))
                                     (swap! sources-seen conj (.trim (String. data 11 20)))
                                     (when (= target-name (String. data 11 (count target-name)))
                                       ;; Record that we received a packet from the target, and when
                                       (alter sync-count inc)
                                       (ref-set last-sync (now))
                                       (let [bpm (float (/ (+ (* 256 (unsign (aget data 90)))
                                                              (unsign (aget data 91))) 100))
                                             beat (aget data 92)]
                                         (metro-bpm metronome bpm)
                                         (metro-beat-phase metronome 0)
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
                                         ;;       line will be invoked INSTEAD of the one above.
                                         #_(metro-bar-phase metronome (/ (dec beat) 4)))))))))))))
  (sync-stop [this]
    (dosync
     (alter watcher (fn [w]
                      (when w (future-cancel w))
                      nil))
     (alter socket (fn [s]
                     (when s (.close s))
                     nil))))
  (sync-status [this]
    (dosync
     (ensure watcher)
     (ensure packet-count)
     (ensure sync-count)
     (ensure last-sync)
     {:type :dj-link,
      :current (<= (- (now) @last-sync) 1000)
      :level :beat
      :source target-name
      :status (cond
                (nil? @watcher)               "Stopped."
                (zero? @packet-count)         "Network problems? No DJ Link packets received."
                (zero? @sync-count)           (str "Configuration problem? No DJ Link beat packets received from "
                                                   target-name ".")
                (> (- (now) @last-sync) 1000) (str "Stalled? No sync packets received in " (- (now) @last-sync) "ms.")
                :else                         (str "Running. " @sync-count " beats received."))})))

;; Suppress uninformative auto-generated documentation entries.
(alter-meta! #'->UDPSync assoc :no-doc true)
(alter-meta! #'map->UDPSync assoc :no-doc true)

(defn sync-to-dj-link
  "Returns a sync function that will cause the beats-per-minute
  setting of the supplied metronome to track the values received from
  the named DJ Link transmitter on the local network. This is intended
  for use with afterglow.show/sync-to-external-clock."
  [dj-link-device-name]
  (fn [^afterglow.rhythm.Metronome metronome]
    (let [sync-handler (UDPSync. metronome dj-link-device-name (ref nil) (ref nil) (ref 0) (ref 0) (ref nil))]
      (sync-start sync-handler)
      sync-handler)))

;; A simple object to help provide a user interface for selecting between available MIDI
;; clock sync sources
(defrecord LinkFinder [socket watcher]
  IClockFinder
  (finder-current-sources [this] @sources-seen)
  (finder-finished [this]
    (swap! watcher (fn [w]
                     (when w (future-cancel w))
                     nil))
    (swap! socket (fn [s]
                    (when s (.close s))
                    nil))))

(defn watch-for-dj-link-sources
  "Returns a \"clock finder\" that will watch for sources of Pro DJ
  Link packets until you tell it to stop."
  []
  (let [socket (atom nil)
        watcher (atom nil)]
    
    (try (reset! socket (DatagramSocket. port (InetAddress/getByName "0.0.0.0")))
         (reset! watcher (future (while true
                                   (let [packet (receive @socket)
                                         data (.getData packet)]
                                     ;; TODO: Check header bytes, perhaps use Protobuf for this?
                                     (when (= 96 (.getLength packet))
                                       (swap! sources-seen conj (.trim (String. data 11 20))))))))
         (catch Exception e
             (info "Unable to create socket to watch for DJ Link packets, must already be syncing.")))
    (LinkFinder. socket watcher)))
