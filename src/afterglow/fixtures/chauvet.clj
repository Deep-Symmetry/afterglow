(ns
    ^{:doc "Models for fixtures provided by Chauvet Lighting"}
  afterglow.fixtures.chauvet
  (:require [afterglow.channels :as chan]
            [taoensso.timbre :as timbre :refer [error info debug]]))

;; TODO functions for rotational tranformatons
;; TODO multi-head support, with relative locations

(defn slimpar-hex3-irc
  ([]
   (slimpar-hex3-irc :12-channel))
  ([mode]
   (assoc (case mode
            ;; TODO missing channels once we have definition support for them
            :12-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber) (chan/color 6 :white) (chan/color 7 :uv "UV")]}
            :8-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber) (chan/color 6 :white) (chan/color 7 :uv "UV")]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber) (chan/color 5 :white) (chan/color 6 :uv "UV")]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))
