(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by [Chauvet Lighting](http://www.chauvetlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :refer [function-value-scaler]]))

(defn slimpar-hex3-irc
  "[SlimPAR HEX 3 IRC](http://www.chauvetlighting.com/slimpar-hex3irc.html)
  six-color low-profile LED PAR.

  This fixture can be configured to use either 6, 8 or 12 DMX
  channels. If you do not specify a mode when patching it,
  `:12-channel` is assumed; you can pass a value of `:6-channel` or
  `:8-channel` for `mode` if you are using it that way.

  When you pass a mode, you can also control whether the amber and UV
  channels are mixed in when creating colors by passing a boolean
  value with `:mix-amber` and `:mix-uv`. The default for each is
  `true`."
  {:doc/format :markdown}
  ([]
   (slimpar-hex3-irc :12-channel))
  ([mode & {:keys [mix-amber mix-uv] :or {mix-amber true mix-uv true}}]
   (assoc (case mode
            :12-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                    (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                    (chan/functions :strobe 8 0 nil
                                                    11 {:type :strobe
                                                        :scale-fn (partial function-value-scaler 0.87 25)
                                                        :label "Strobe (0.87Hz->25Hz)"})
                                    (chan/functions :color-macros 9 0 nil 16 :color-macros)
                                    (chan/functions :control 10 0 nil (range 11 200 50) "program"
                                                    201 :sound-active-6-color 226 :sound-active)
                                    (chan/functions :program-speed 11 0 :program-speed)
                                    (chan/functions :dimmer-mode 12 0 "dimmer-mode-manual" 52 "dimmer-mode-off"
                                                    102 "dimmer-mode-fast" 153 "dimmer-mode-medium"
                                                    204 "dimmer-mode-slow")]}
            :8-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                   (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                   (chan/functions :strobe 8 0 nil
                                                   11 {:type :strobe
                                                       :scale-fn (partial function-value-scaler 0.87 25)
                                                       :label "Strobe (0.87Hz->25Hz)"})]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue (when mix-amber 45)) (chan/color 5 :white)
                                   (chan/color 6 :uv :label "UV" :hue (when mix-uv 270))]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))
