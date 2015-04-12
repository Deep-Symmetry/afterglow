(ns
    ^{:doc "Show some simple ways to use Afterglow, inspire exploration."
      :author "James Elliott"}
  afterglow.examples
  (:require [afterglow.ola-service :as ola]
            [afterglow.ola-messages :refer [DmxData]]
            [afterglow.rhythm :refer :all]
            [afterglow.util :refer [ubyte]]
            [overtone.at-at :as at-at]
            [taoensso.timbre :as timbre :refer [error info debug]])
  (:import [com.google.protobuf ByteString]))

(def refresh-rate
  "How often should frames of DMX data be sent out; this should match the frame rate of your interface.
  The default here is 30 Hz, thirty frames per second."
  (/ 1000 30))

(def scheduler
  "Provides thread scheduling."
  (at-at/mk-pool))

(def metro
  "Shared metronome used by the examples; you can change its speed by, for example (metro-bpm metro 80)."
  (metronome 120))

(defn stop!
  "Stops all scheduled tasks."
  []
  (at-at/stop-and-reset-pool! scheduler))

(defn ramp-channels
  "Ramps specified DMX channels from zero to max and then jumps back to zero,
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
