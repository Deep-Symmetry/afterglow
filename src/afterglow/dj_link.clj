(ns afterglow.dj-link
  "Provides synchronization with equipment sending Pioneer Pro DJ Link
  packets on the local network, such as Pioneer Nexus mixers and players.
  This is purely experimental and based on network traffic capture, but
  unless they change the protocol, it works really well, providing rock
  solid BPM and measure-phase tracking."
  (:require [afterglow.midi :refer [IClockSync sync-start sync-stop sync-status]]
            [afterglow.rhythm :refer :all]
            [afterglow.util :refer [unsign]]
            [overtone.at-at :refer [now]])
  (:import [java.net InetAddress DatagramPacket DatagramSocket]))

;; The UDP port on which BPM and beat packets are broadcast
(def port 50001)

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
(defrecord UDPSync [metronome target-name socket watcher packet-count sync-count last-sync]
  IClockSync
  (sync-start [this]
    (swap! socket (fn [s]
                    (when s (.close s))
                    (DatagramSocket. port (InetAddress/getByName "0.0.0.0"))))
    (reset! packet-count 0)
    (reset! sync-count 0)
    (reset! last-sync nil)
    (swap! watcher (fn [w]
                    (when w (future-cancel w))
                    (future (while true
                              (let [packet (receive @socket)
                                    data (.getData packet)]
                                ;; Check packet length and target name.
                                ;; TODO: Check header bytes, perhaps use Protobuf for this?
                                (swap! packet-count inc)
                                (when (and (= 96 (.getLength packet))
                                           (= target-name (String. data 11 (count target-name))))
                                  ;; Record that we received a packet from the target, and when
                                  (swap! sync-count inc)
                                  (reset! last-sync (now))
                                  (let [bpm (float (/ (+ (* 256 (unsign (aget data 90))) (unsign (aget data 91))) 100))
                                        beat (aget data 92)]
                                    (metro-bpm metronome bpm)
                                    (metro-beat-phase metronome 0)
                                    ;; TODO had to give up for now on the following line because the mixer
                                    ;; does not update its measure position based on that of the master player.
                                    ;; Consider figuring out how to tell what the master player is from the UDP
                                    ;; traffic, and using that. But this should probably be an option anyway
                                    ;; because a lot of DJs do not beat grid that carefully, and the light show
                                    ;; operator will in those cases need to be able to override the down beat,
                                    ;; which will not be possible if this code is resyncing the bar start on each
                                    ;; beat from the Pioneer equipment. If/when that mode is figured out and made
                                    ;; an option, the following line will be invoked INSTEAD of the one above.
                                    #_(metro-bar-phase metronome (/ (dec beat) 4))))))))))
  (sync-stop [this]
    (swap! watcher (fn [w]
                     (when w (future-cancel w))
                     nil))
    (swap! socket (fn [s]
                    (when s (.close s))
                    nil)))
  (sync-status [this]
    {:type :dj-link,
     :status (cond
               (nil? watcher)                "Stopped."
               (zero? @packet-count)         "Network problems? No DJ Link packets received."
               (zero? @sync-count)           (str "Configuration problem? No DJ Link beat packets received from " target-name ".")
               (> (- (now) @last-sync) 1000) (str "Stalled? No sync packets received in " (- (now) @last-sync) "ms.")
               :else                         (str "Running. " @sync-count " beats received."))}))

(defn sync-to-dj-link
  "Returns a sync function that will cause the beats-per-minute
  setting of the supplied metronome to track the values received from
  the named DJ Link transmitter on the local network. This is intended
  for use with afterglow.show/sync-to-external-clock."
  [dj-link-device-name]
  (fn [^afterglow.rhythm.Metronome metronome]
    (let [sync-handler (UDPSync. metronome dj-link-device-name (atom nil) (atom nil) (atom 0) (atom 0) (atom nil))]
      (sync-start sync-handler)
      sync-handler)))
