(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by [Chauvet Lighting](http://www.chauvetlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.fixtures.qxf :refer [sanitize-name]]))

(defn- build-gobo-entries
  "Build a list of gobo wheel function entries. If `shake?` is `true`,
  they are a variable range which sets the shake speed. The individual
  gobo names are in `names`."
  {:doc/format :markdown}
  [shake? names]
  (map (fn [entry]
         (let [label (str "Gobo " entry (when shake? " shake"))
               type-key (keyword (sanitize-name label))]
           (merge {:type type-key
                   :label label
                   :range (if shake? :variable :fixed)}
                  (when shake? {:var-label "Shake Speed"}))))
       names))

(defn color-strip
  "[ColorStrip](http://www.chauvetlighting.com/colorstrip.html) LED fixture.
  Also works with the ColorStrip Mini.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by JL Griffin
  using Q Light Controller version 3.1.0.

  QLC+ Fixture Type: Color Changer."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :control 1
                              0 "Blackout"
                              10 "Red"
                              20 "Green"
                              30 "Blue"
                              40 "Yellow"
                              50 "Magenta"
                              60 "Cyan"
                              70 "White"
                              (range 80 139 10) "Auto Change"
                              (range 140 209 10) "Color Chase"
                              210 "RGB Control"
                              220 "Chase Fade"
                              230 "Auto Run")
              (chan/color 2 :red)
              (chan/color 3 :green)
              (chan/color 4 :blue)]
   :name "ColorStrip"})

(defn geyser-rgb
  "[Geyser RGB](http://www.chauvetlighting.com/geyser-rgb.html) illuminated effect fogger."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :fog 1
                              0 nil
                              10 "Fog")
              (chan/color 2 :red)
              (chan/color 3 :green)
              (chan/color 4 :blue)
              (chan/functions :control 5
                              0 nil
                              10 :color-mixing)
              (chan/functions :control 6
                              0 nil
                              10 :auto-speed)
              (chan/functions :strobe 7
                              0 nil
                              10 :strobe)
              (chan/dimmer 8)]
   :name "Geyser RGB"})

(defn hurricane-1800-flex
  "[Hurricane 1800 Flex](http://www.chauvetlighting.com/hurricane-1800-flex.html) fogger."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :fog 1
                              0 nil
                              6 "Fog")]
   :name "Hurricane 1800 Flex"})

(defn intimidator-scan-led-300
  "[Intimidator Scan LED 300](http://www.chauvetlighting.com/intimidator-scan-led-300.html) compact scanner.

  This fixture can be configured to use either 8 or 11 DMX channels.
  If you do not specify a mode when patching it, `:11-channel` is
  assumed; you can pass a value of `:8-channel` for `mode` if you are
  using it that way.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and heavily revised by James Elliott.

  The original fixture defintition was created by Craig Cudmore
  using Q Light Controller Plus version 4.7.0 GIT.

  QLC+ Fixture Type: Scanner."
  {:doc/format :markdown}
  ([]
   (intimidator-scan-led-300 :11-channel))
  ([mode]
   (let [build-color-wheel (fn [channel]
                             (chan/functions :color channel
                                             0 "Color Wheel Open"
                                             7 (chan/color-wheel-hue "yellow")
                                             14 "Color Wheel Pink"
                                             21 (chan/color-wheel-hue "green")
                                             28 "Color Wheel Peachblow"
                                             35 "Color Wheel Light Blue"
                                             42 "Color Wheel Kelly"
                                             49 (chan/color-wheel-hue "red")
                                             56 (chan/color-wheel-hue "blue")
                                             64 "Color Wheel White + Yellow"
                                             71 "Color Wheel Yellow + Pink"
                                             78 "Color Wheel Pink + Green"
                                             85 "Color Wheel Green + Peachblow"
                                             92 "Color Wheel Peachblow + Blue"
                                             99 "Color Wheel Blue + Kelly"
                                             106 "Color Wheel Kelly + Red"
                                             113 "Color Wheel Red + Blue"
                                             120 "Color Wheel Blue + White"
                                             128 {:type :color-clockwise
                                                  :label "Color Wheel Clockwise (slow->fast)"
                                                  :var-label "CW (slow->fast)"
                                                  :range :variable}
                                             192 {:type :color-counterclockwise
                                                  :label "Color Wheel Counterclockwise (slow->fast)"
                                                  :var-label "CCW (slow->fast)"
                                                  :range :variable}))
         build-shutter (fn [channel]
                         (chan/functions :shutter channel
                                         0 "Shutter Closed"
                                         4 "Shutter Open"
                                         8 {:type :strobe
                                            :label "Strobe"
                                            :range :variable}
                                         216 "Shutter Open 2"))
                  gobo-names ["Pink Dots" "Purple Bubbles" "45 Adapter" "Nested Rings" "Rose" "Triangles" "Galaxy"]
         build-gobo-wheel (fn [channel]
                            (chan/functions :gobo channel
                                            (range 0 63 8) (build-gobo-entries false (concat ["Open"] gobo-names))
                                            (range 64 119 8) (build-gobo-entries true (reverse gobo-names))
                                            120 "Gobo Open 2"
                                            128 {:type :gobo-clockwise
                                                 :label "Gobo Clockwise Speed"
                                                 :var-label "CW Speed"
                                                 :range :variable}
                                            192 {:type :gobo-counterclockwise
                                                 :label "Gobo Counterclockwise Speed"
                                                 :var-label "CCW Speed"
                                                 :range :variable}))
         build-gobo-rotation (fn [channel]
                               (chan/functions :gobo-rotation channel
                                               0 nil
                                               16 {:type :gobo-rotation-counterclockwise
                                                   :label "Gobo Rotation Counterlockwise (slow->fast)"
                                                   :var-label "CCW (slow->fast)"
                                                   :range :variable}
                                               128 {:type :gobo-rotation-clockwise
                                                    :label "Gobo Rotation Clockwise (slow->fast)"
                                                    :var-label "CW (slow->fast)"
                                                    :range :variable}
                                               240 :gobo-bounce))]
     (merge {:name "Intimidator Scan LED 300"
             :pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
             :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
             :mode mode}
            (case mode
              :11-channel
              {:channels [(chan/pan 1)
                          (chan/tilt 2)
                          (build-color-wheel 3)
                          (build-shutter 4)
                          (chan/dimmer 5)
                          (build-gobo-wheel 6)
                          (build-gobo-rotation 7)
                          (chan/functions :prism 8
                                          0 "Prism Out"
                                          4 "Prism In")
                          (chan/focus 9)
                          (chan/functions :control 10
                                          8 "Blackout while moving Pan/Tilt"
                                          16 "Disable Blackout while moving Pan/Tilt"
                                          24 "Blackout while moving Color Wheel"
                                          32 "Disable Blackout while moving Color Wheel"
                                          40 "Blackout while moving Gobo Wheel"
                                          48 "Disable Blackout while moving Gobo Wheel"
                                          56 "Fast Movement"
                                          64 "Disable Fast Movement"
                                          88 "Disable all Blackout while moving"
                                          96 "Reset Pan/Tilt"
                                          104 nil
                                          112 "Reset Color Wheel"
                                          120 "Reset Gobo Wheel"
                                          128 nil
                                          136 "Reset Prism"
                                          144 "Reset Focus"
                                          152 "Reset All"
                                          160 nil)
                          (chan/functions :control 11
                                          0 nil
                                          (range 8 135 16) "Program"
                                          (range 136 255 16) :sound-active)]}
              :8-channel
              {:channels [(chan/pan 1)
                          (chan/tilt 2)
                          (build-color-wheel 3)
                          (build-shutter 4)
                          (build-gobo-wheel 5)
                          (build-gobo-rotation 6)
                          (chan/functions :prism 7
                                          0 "Prism Out"
                                          4 "Prism In")
                          (chan/focus 8)]})))))

(defn intimidator-spot-led-150
  "[Intimidator Spot LED 150](http://www.chauvetlighting.com/intimidator-spot-led-150.html) moving yoke.

  This fixture can be configured to use either 6 or 11 DMX channels.
  If you do not specify a mode when patching it, `:11-channel` is
  assumed; you can pass a value of `:6-channel` for `mode` if you are
  using it that way.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and heavily revised by James Elliott.

  The original fixture defintition was created by Tavon Markov
  using Q Light Controller Plus version 4.8.3 GIT.

  QLC+ Fixture Type: Moving Head."
  {:doc/format :markdown}
  ([]
   (intimidator-spot-led-150 :11-channel))
  ([mode]
   (let [build-color-wheel (fn [channel]
                             (chan/functions :color channel
                                             0 "Color Wheel Open"
                                             (range 6 47 6) (chan/color-wheel-hue ["yellow" "magenta" "green" "red"
                                                                                   "cyan" "orange" "blue"])
                                             48 "Color Wheel Light Green"
                                             54 (chan/color-wheel-hue 45 :label "Color Wheel Amber")
                                             64 "Color Wheel White + Yellow"
                                             70 "Color Wheel Yellow + Magenta"
                                             76 "Color Wheel Magenta + Green"
                                             82 "Color Wheel Green + Red"
                                             88 "Color Wheel Red + Cyan"
                                             94 "Color Wheel Cyan + Orange"
                                             100 "Color Wheel Orange + Blue"
                                             106 "Color Wheel Blue + Light Green"
                                             112 "Color Wheel Light Green + Amber"
                                             118 "Color Wheel Amber + White"
                                             128 {:type :color-clockwise
                                                  :label "Color Wheel Clockwise (fast->slow)"
                                                  :var-label "CW (fast->slow)"
                                                  :range :variable}
                                             192 {:type :color-counterclockwise
                                                  :label "Color Wheel Counterclockwise (slow->fast)"
                                                  :var-label "CCW (fast->slow)"
                                                  :range :variable}))
         build-shutter (fn [channel]
                         (chan/functions :shutter channel
                                         0 "Shutter Closed"
                                         4 "Shutter Open"
                                         8 {:type :strobe
                                            :label "Strobe (0-20Hz)"
                                            :scale-fn (partial chan-fx/function-value-scaler 0 200)
                                            :range :variable}
                                         216 "Shutter Open 2"))
         gobo-names ["Quotes" "Warp Spots" "4 Dots" "Sail Swirl" "Starburst" "Star Field" "Optical Tube"
                     "Sun Swirl" "Star Echo"]
         build-gobo-wheel (fn [channel]
                            (chan/functions :gobo channel
                                            (range 0 63 6) (build-gobo-entries false (concat ["Open"] gobo-names))
                                            (range 64 121 6) (build-gobo-entries true (reverse gobo-names))
                                            122 "Gobo Open 2"
                                            128 {:type :gobo-clockwise
                                                 :label "Gobo Clockwise Speed"
                                                 :var-label "CW Speed"
                                                 :range :variable}
                                            192 {:type :gobo-counterclockwise
                                                 :label "Gobo Counterclockwise Speed"
                                                 :var-label "CCW Speed"
                                                 :range :variable}))]
        (merge {:name "Intimidator Spot LED 150"
                :mode mode}
               (case mode
                 :11-channel
                 {:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                  :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                  :channels [(chan/pan 1 3)
                             (chan/tilt 2 4)
                             (chan/fine-channel :pan-tilt-speed 5
                                                :function-name "Pan / Tilt Speed"
                                                :var-label "P/T fast->slow")
                             (build-color-wheel 6)
                             (build-shutter 7)
                             (chan/dimmer 8)
                             (build-gobo-wheel 9)
                             (chan/functions :control-functions 10
                                             0 nil
                                             8 "Blackout while moving Pan/Tilt"
                                             28 "Blackout while moving Gobo Wheel"
                                             48 "Disable blackout while Pan/Tilt / moving Gobo Wheel"
                                             68 "Blackout while moving Color Wheel"
                                             88 "Disable blackout while Pan/Tilt / moving Color Wheel"
                                             108 "Disable blackout while moving Gobo/Color Wheel"
                                             128 "Diable blackout while moving all options"
                                             148 "Reset Pan"
                                             168 "Reset Tilt"
                                             188 "Reset Color Wheel"
                                             208 "Reset Gobo Wheel"
                                             228 "Reset All Channels"
                                             248 nil)
                             (chan/functions :movement-macros 11
                                             0 nil
                                             (range 8 135 16) "Program"
                                             (range 136 255 16) "Sound Active")]}
                 :6-channel
                 {:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                  :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                  :channels [(chan/pan 1)
                             (chan/tilt 2)
                             (build-color-wheel 3)
                             (build-shutter 4)
                             (chan/dimmer 5)
                             (build-gobo-wheel 6)]})))))

(defn kinta-x
  "[Kinta X](http://www.chauvetlighting.com/kinta-x.html) derby effect."
  {:doc/format :markdown
   :author "James Elliott"}
  []
  {:channels [(chan/functions :control 1
                              0 "LEDs off"
                              15 "LEDs on"
                              101 "Auto")
              (chan/functions :rotation 2
                              0 nil
                              1 :counter-clockwise
                              86 :clockwise
                              171 :back-and-forth)]
   :name "Kinta X"})

(defn led-techno-strobe
  "[LED Techno Strobe](http://www.chauvetlighting.com/led-techno-strobe.html)
  strobe light."
  {:doc/format :markdown
   :author "James Elliott"}
  []
  {:channels [(chan/functions :control 1
                              0 "Intensity Control"
                              (range 30 209 30) "Program"
                              210 :sound-active)
              (chan/functions :strobe 2 0 nil
                              1 :strobe)  ; TODO: Measure speed, add scale-fn
              (chan/dimmer 3)]  ; TODO: Manual implies this might be reversed? If so, what a pain.
   :name "LED Techno Strobe"})

(defn led-techno-strobe-rgb
  "[LED Techno Strobe RGB](http://www.chauvetlighting.com/led-techno-strobe-rgb.html)
  color mixing strobe light.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by Davey D
  using Q Light Controller Plus version 4.6.0.

  QLC+ Fixture Type: Color Changer."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :control 1
                              0 "RGB Control"
                              (range 25 224 25) "Program"
                              225 :sound-active)
              (chan/color 2 :red)
              (chan/color 3 :green)
              (chan/color 4 :blue)
              (chan/functions :strobe 5 0 nil
                              1 :strobe)  ; TODO: Measure speed, add scale-fn
              (chan/dimmer 6)]
   :name "LED Techno Strobe RGB"})

(defn scorpion-storm-fx-rgb
  "[Scorpion Storm FX RGB](http://www.chauvetlighting.com/scorpion-storm-fx-rgb.html)
   grid effect laser.

  This fixture can be patched to use either 7 or 2 DMX channels. If
  you do not specify a mode when patching it, `:7-channel` is assumed;
  you can pass a value of `:2-channel` for `mode` if you are using it
  that way. Although there are two different modes in which you can
  patch it, its behavior is controlled by the value you send in
  channel 1: If that value is between `0` and `50`, the laser responds
  to all 7 channels as described by `:7-channel` mode; if channel 1 is
  set to `51` or higher, it looks only at the first two channels, as
  described by `:2-channel` mode.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by Frédéric Combe
  using Q Light Controller Plus version 5.0.0 GIT.

  QLC+ Fixture Type: Laser."
  {:doc/format :markdown}
  ([]
   (scorpion-storm-fx-rgb :7-channel))
  ([mode]
   (merge {:name "Scorpion Storm FX RGB"
           :mode mode}
          (case mode
                :7-channel
                {:channels [(chan/functions :control 1
                                            0 "DMX Mode"
                                            51 nil)
                            (chan/functions :control 2
                                            0 "Blackout"
                                            5 "Beam Red"
                                            16 "Beam Green"
                                            26 "Beam Blue"
                                            36 "Beam Red Green"
                                            46 "Beam Blue Green"
                                            56 "Beam Red Blue"
                                            66 "Beam Red Green Blue"
                                            76 "Beam Red, Strobe Green"
                                            86 "Beam Green, Strobe Blue"
                                            96 "Beam Blue, Strobe Red"
                                            106 "Alternate Red/Green"
                                            116 "Alternate Green/Blue"
                                            126 "Alternate Red/Blue"
                                            136 "Beam Red Green, Strobe Blue"
                                            146 "Beam Green Blue, Strobe Red"
                                            156 "Beam Red Blue, Strobe Green"
                                            166 "Beam Blue, Strobe Red Green"
                                            176 "Beam Red, Strobe Green Blue"
                                            186 "Beam Green, Strobe Red Blue"
                                            196 "Beam Blue, Alternate Red/Green"
                                            206 "Beam Red, Alternate Green/Blue"
                                            216 "Beam Green, Alternate Red/Blue"
                                            226 "Alternate Red/Green/Blue")
                            (chan/functions :strobe 3
                                            0 nil
                                            5 :strobe
                                            255 "Strobe Sound Active")
                            (chan/functions :control 4
                                            0 "Motor 1 Stop"
                                            5 :motor-1-clockwise
                                            128 "Motor 1 Stop 2"
                                            134 :motor-1-counterclockwise)
                            (chan/functions :control 5
                                            0 nil
                                            5 :stutter-motor-1-mode-1
                                            57 :stutter-motor-1-mode-2
                                            113 :stutter-motor-1-mode-3
                                            169 :stutter-motor-1-mode-4)
                            (chan/functions :control 6
                                            0 "Motor 2 Stop"
                                            5 :motor-2-clockwise
                                            128 "Motor 2 Stop 2"
                                            134 :motor-2-counterclockwise)
                            (chan/functions :control 7
                                            0 nil
                                            5 :stutter-motor-2-mode-1
                                            57 :stutter-motor-2-mode-2
                                            113 :stutter-motor-2-mode-3
                                            169 :stutter-motor-2-mode-4)]}
                :2-channel
                {:channels [(chan/functions :control 1
                                            0 nil
                                            51 "Auto fast"
                                            101 "Auto slow"
                                            151 :sound-active
                                            201 "Random program")
                            (chan/functions :control 2
                                            0 "Beam Red"
                                            37 "Beam Green"
                                            73 "Beam Blue"
                                            109 "Beam Red Green"
                                            145 "Beam Green Blue"
                                            181 "Beam Red Blue"
                                            217 "Beam Red Green Blue")]}))))

(defn scorpion-storm-rgx
  "[Scorpion Storm RGX](http://www.chauvetlighting.com/products/manuals/Scorpion_Storm_RGX_UM_Rev03_WO.pdf)
  grid effect laser."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :control 1
                                       0 "DMX Mode"
                                       20 "Auto Fast Red"
                                       40 "Auto Slow Red"
                                       60 "Auto Fast Green"
                                       80 "Auto Slow Green"
                                       100 "Auto Fast Red Green"
                                       120 "Auto Slow Red Green"
                                       140 :sound-active-red
                                       160 :sound-active-green
                                       180 :sound-active
                                       200 "Random")
                       (chan/functions :control 2
                                       0 "Blackout"
                                       5 "Beam Red"
                                       29 "Beam Green"
                                       57 "Beam Red Green"
                                       85 "Strobe Green"
                                       113 "Strobe Red"
                                       141 "Beam Red, Strobe Green"
                                       169 "Beam Green, Strobe Red"
                                       198 "Strobe Red Green"
                                       225 "Alternate Red/Green")
                       (chan/functions :strobe 3
                                       0 nil
                                       5 :strobe
                                       255 "Strobe Sound Active")
                       (chan/functions :control 4
                                       0 "Stop"
                                       5 :beams-clockwise
                                       128 "Stop 2"
                                       134 :beams-counterclockwise)]
   :name "Scorpion Storm RGX"})

(defn q-spot-160
  "Q Spot 160 moving yoke.

  This fixture can be configured to use either 9 or 12 DMX channels.
  If you do not specify a mode when patching it, `:12-channel` is
  assumed; you can pass a value of `:9-channel` for `mode` if you are
  using it that way."
  {:doc/format :markdown}
  ([]
   (q-spot-160 :12-channel))
  ([mode]
   (let [build-color-wheel (fn [channel]
                             (chan/functions :color channel
                                             0 "Color Wheel Open"
                                             (range 15 59 15) (chan/color-wheel-hue ["red" "yellow" "green"])
                                             60 "Color Wheel pink"
                                             (range 75 119 15) (chan/color-wheel-hue ["blue" "orange" "magenta"])
                                             120 "Color Wheel Light Blue"
                                             135 "Color Wheel Light Green"
                                             150 {:type :color-clockwise
                                                  :label "Color Wheel Clockwise (slow->fast)"
                                                  :var-label "CW (slow->fast)"
                                                  :range :variable}))
         build-shutter (fn [channel]
                         (chan/functions :shutter channel
                                         0 "Shutter Closed"
                                         32 "Shutter Open"
                                         64 :strobe
                                         96 "Shutter Open 2"
                                         128 :pulse-strobe
                                         160 "Shutter Open 3"
                                         192 :random-strobe
                                         224 "Shutter Open 4"))
         gobo-names ["Splat" "Spot Sphere" "Fanned Squares" "Box" "Bar" "Blue Starburst" "Perforated Pink"]
         build-gobo-wheel (fn [channel]
                            (chan/functions :gobo channel
                                            (range 0 79 10) (build-gobo-entries false (concat ["Open"] gobo-names))
                                            (range 80 219 20) (build-gobo-entries true (reverse gobo-names))
                                            220 {:type :gobo-scroll
                                                 :label "Gobo Scroll"
                                                 :var-label "Scroll Speed"
                                                 :range :variable}))
         build-gobo-rotation (fn [channel]
                               (chan/functions :gobo-rotation channel
                                               0 nil
                                               3 {:type :gobo-rotation-clockwise
                                                  :label "Gobo Rotation Clockwise (slow->fast)"
                                                  :var-label "CW (slow->fast)"
                                                  :range :variable}
                              129 "Gobo Rotation Stop"
                              133 {:type :gobo-rotation-counterclockwise
                                   :label "Gobo Rotation Counterlockwise (slow->fast)"
                                   :var-label "CCW (slow->fast)"
                                   :range :variable}))
         build-control-channel (fn [channel]
                                 (chan/functions :control channel
                                             0 nil
                                             20 "Blackout while moving Pan/Tilt"
                                             40 "Disable Blackout while moving Pan/Tilt"
                                             60 "Auto 1"
                                             80 "Auto 2"
                                             100 "Sound Active 1"
                                             120 "Sound Active 2"
                                             140 "Custom"
                                             160 "Test"
                                             180 nil
                                             200 "Reset"
                                             220 nil))]
        (merge {:name "Q Spot 160"
                :pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                :mode mode}
               (case mode
                 :12-channel
                 {:channels [(chan/pan 1 2)
                             (chan/tilt 3 4)
                             (chan/fine-channel :pan-tilt-speed 5
                                                :function-name "Pan / Tilt Speed"
                                                :var-label "P/T fast->slow")
                             (build-color-wheel 6)
                             (build-gobo-wheel 7)
                             (build-gobo-rotation 8)
                             (chan/functions :prism 9 0 "Prism Out" 128 "Prism In")
                             (chan/dimmer 10)
                             (build-shutter 11)
                             (build-control-channel 12)]}
                 :9-channel
                 {:channels [(chan/pan 1)
                             (chan/tilt 2)
                             (build-color-wheel 3)
                             (build-gobo-wheel 4)
                             (build-gobo-rotation 5)
                             (chan/functions :prism 6 0 "Prism Out" 128 "Prism In")
                             (chan/dimmer 7)
                             (build-shutter 8)
                             (build-control-channel 9)]})))))

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
  {:doc/format :markdown
   :author "James Elliott"}
  ([]
   (slimpar-hex3-irc :12-channel))
  ([mode & {:keys [mix-amber mix-uv] :or {mix-amber true mix-uv true}}]
   (assoc (case mode
            :12-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                    (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                    (chan/functions :strobe 8 0 nil
                                                    11 {:type :strobe
                                                        :scale-fn (partial chan-fx/function-value-scaler 0.87 25)
                                                        :label "Strobe (0.87Hz->25Hz)"})
                                    (chan/functions :color-macros 9 0 nil 16 :color-macros)
                                    (chan/functions :control 10 0 nil (range 11 200 50) "Program"
                                                    201 :sound-active-6-color 226 :sound-active)
                                    (chan/functions :program-speed 11 0 :program-speed)
                                    (chan/functions :dimmer-mode 12 0 "Dimmer Mode Manual" 52 "Dimmer Mode Off"
                                                    102 "Dimmer Mode Fast" 153 "Dimmer Mode Medium"
                                                    204 "Dimmer Mode Slow")]}
            :8-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                   (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                   (chan/functions :strobe 8 0 nil
                                                   11 {:type :strobe
                                                       :scale-fn (partial chan-fx/function-value-scaler 0.87 25)
                                                       :label "Strobe (0.87Hz->25Hz)"})]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue (when mix-amber 45)) (chan/color 5 :white)
                                   (chan/color 6 :uv :label "UV" :hue (when mix-uv 270))]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))

