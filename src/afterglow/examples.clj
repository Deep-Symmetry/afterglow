(ns
    ^{:doc "Show some simple ways to use Afterglow, inspire exploration."
      :author "James Elliott"}
  afterglow.examples
  (:require [afterglow.ola-service :as ola]
            [afterglow.ola-messages :refer [DmxData]]
            [afterglow.rhythm :refer :all]
            [overtone.at-at :as at-at]
            [taoensso.timbre :as timbre :refer [info debug]])
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

(defn ramp-one-channel
  "Ramps DMX channel 1 of universe 1 from zero to max and then jumps back to zero,
  for every beat of a 120 bpm metronome (that is, pretty quickly)."
  []
  (at-at/every refresh-rate #(let [new-level (int (* 255 (metro-beat-phase metro)))
                                   data (ByteString/copyFrom (byte-array [new-level]))]
                               (ola/UpdateDmxData {:universe 1 :data data} nil)) scheduler))
