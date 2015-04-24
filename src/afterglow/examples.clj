(ns
    ^{:doc "Show some simple ways to use Afterglow, inspire exploration."
      :author "James Elliott"}
  afterglow.examples
  (:require [afterglow.ola-service :as ola]
            [afterglow.ola-messages :refer [DmxData]]
            [afterglow.rhythm :refer :all]
            [afterglow.util :refer [ubyte]]
            [afterglow.channels :refer [patch-fixture]]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.effects.color :refer [color-cue]]
            [afterglow.effects.dimmer :refer [dimmer-cue]]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :as at-at]
            [taoensso.timbre :as timbre :refer [error info debug spy]])
  (:import [com.google.protobuf ByteString]))

(def refresh-rate
  "How often should frames of DMX data be sent out; this should match the frame rate of your interface.
  The default here is 30 Hz, thirty frames per second."
  (/ 1000 30))

(defonce ^{:doc "Provides thread scheduling."} scheduler
  (at-at/mk-pool))

(def metro
  "Shared metronome used by the examples; you can change its speed by, for example (metro-bpm metro 80)."
  (metronome 120))

(defn stop!
  "Stops all scheduled tasks."
  []
  (at-at/stop-and-reset-pool! scheduler))

(def sample-rig
  [(patch-fixture (chauvet/slimpar-hex3-irc) 1 129) (patch-fixture (blizzard/blade-rgbw) 1 270)])

(def blue-cue (afterglow.effects.color/color-cue (com.evocomputing.colors/create-color :slateblue) sample-rig))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample rig to a fixed value."
  [level]
  (afterglow.effects.dimmer/dimmer-cue level sample-rig))

(defonce active-functions (atom {}))

(defn run-cues
  "Implements whatever cues and effects are present in the active functions map each time the
  DMX values need refreshing."
  [& universes]
  (let [buffers (into {} (for [universe universes] [universe (byte-array 512)]))]
    (at-at/every refresh-rate
                 #(try
                    (doseq [levels (vals buffers)] (java.util.Arrays/fill levels (byte 0)))
                    (doseq [f (vals @active-functions)]
                      (doseq [channel (f)]
                        (when-let [levels (get buffers (:universe channel))]
                          (aset levels (dec (:address channel)) (ubyte (:value channel)))))) ;; This is always LTP, need to support HTP too
                    (doseq [universe (keys buffers)]
                      ;;(info "Updating " universe)
                      (let [levels (get buffers universe)]
                        (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil)))
                    (catch Exception e
                      (error e "Problem trying to run cues")))
                 scheduler)))

(defn ramp-channels
  "OBSOLETE. Should no longer be used now that cue mixing via run-cues has
  been implemented.
  
  Ramps specified DMX channels from zero to max and then jumps back to zero,
  for every beat of the shared example metronome (which will probably be fairly
  quickly).

  universe must be a valid Universe ID for your OLA server, and
  channels is a list of DMX channel numbers (1-512)."
  [universe & channels]
  (let [levels (byte-array (apply max channels))
        indices (map dec channels)]
    (at-at/every refresh-rate
                 #(try
                    (let [new-level (int (* 255 (metro-beat-phase metro)))]
                      (doseq [index indices]
                        (aset levels index (ubyte new-level)))
                      (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil))
                    (catch Exception e
                      (error e "Problem trying to ramp channels")))
                 scheduler)))

(defn blackout-universe
  "Sends zero to every channel of the specified universe"
  [universe]
  (let [levels (byte-array 512)]
    (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil)))
