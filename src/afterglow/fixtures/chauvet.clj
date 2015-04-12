(ns
    ^{:doc "Models for fixtures provided by Chauvet Lighting"}
  afterglow.fixtures.chauvet
  (:require [afterglow.channels :as chan]
            [taoensso.timbre :as timbre :refer [error info debug]]))

;; TODO add a utility function to sanity-check channel offsets
;; TODO macros to make this more of a DSL?
;; TODO functions for rotational tranformatons
;; TODO multi-head support, with relative locations

(defn slimpar-hex3-irc
  ([channel]
   (slimpar-hex3-irc channel :12-channel))
  ([channel mode]
   (case mode
     ;; TODO missing channels once we have definition support for them
     :12-channel {:fixture {:channels [(chan/dimmer (+ channel 0)) (chan/color (+ channel 1) :red) (chan/color (+ channel 2) :green)
                                       (chan/color (+ channel 3) :blue) (chan/color (+ channel 4) :amber)
                                       (chan/color (+ channel 5) :white) (chan/color (+ channel 6) :uv "UV")]}}
     :8-channel {:fixture {:channels [(chan/dimmer (+ channel 0)) (chan/color (+ channel 1) :red) (chan/color (+ channel 2) :green)
                                       (chan/color (+ channel 3) :blue) (chan/color (+ channel 4) :amber)
                                       (chan/color (+ channel 5) :white) (chan/color (+ channel 6) :uv "UV")]}}
     :6-channel {:fixture {:channels [(chan/color (+ channel 0) :red) (chan/color (+ channel 1) :green) (chan/color (+ channel 2) :blue)
                                      (chan/color (+ channel 3) :amber) (chan/color (+ channel 4) :white)
                                      (chan/color (+ channel 5) :uv "UV")]}}))
  )
