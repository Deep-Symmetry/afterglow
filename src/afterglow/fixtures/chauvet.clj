(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by [Chauvet Lighting](http://www.chauvetlighting.com)."
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.fixtures.qxf :refer [sanitize-name]]))

(defn- build-gobo-entries
  "Build a list of gobo wheel function entries. If `shake?` is `true`,
  they are a variable range which sets the shake speed. The individual
  gobo names are in `names`."
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
   :name "Chauvet ColorStrip"})

(defn geyser-rgb
  "[Geyser RGB](http://www.chauvetlighting.com/geyser-rgb.html) illuminated effect fogger."
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
   :name "Chauvet Geyser RGB"})

(defn gig-bar-2
  "[GigBAR 2](https://www.chauvetdj.com/products/gigbar-2/) mobile DJ
  lighting system. Even though this fixture does not move, if you want
  to have its RGB pars participate in spatial effects, it is important
  to patch it at the correct location and orientation, so Afterglow
  can properly reason about the spatial relationships between the
  heads. If you have rearranged the pods from their default
  configuration, you will want to edit the head definitions
  appropriately.

  The fixture origin is in between the two center (par) pods, at the
  center of the support pole, and centered vertically with the lenses
  of the Par lights. The default orientation is with the pod mounting
  bar parallel to the X axis, and the LED display and sockets all
  facing away from the audience.

  This fixture can be patched to use 3, 11, or 23 DMX channels. If you
  do not specify a mode when patching it, `:23-channel` is assumed;
  you can pass a value of `:3-channel` or `:11-channel` for `mode` if
  you are using it that way.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by Freasy using Q Light
  Controller Plus version 4.11.0. QLC+ Fixture Type: Effect.

  When you pass a mode, you can also control whether the UV channels
  are mixed in when creating colors by passing a boolean value with
  `:mix-uv`. The default is `true`."
  ([]
   (gig-bar-2 :23-channels))
  ([mode & {:keys [mix-uv] :or {mix-uv true}}]
   (merge {:name "GigBAR 2"
           :mode mode}
          (case mode
            :3-channels
            {:channels [(chan/functions :led-operation 1
                                        0 nil
                                        10 {:type  :led-auto-mixed-mode-1
                                            :label "Auto 1 fast>slow"
                                            :range :variable}
                                        120 {:type  :led-auto-mixed-mode-2
                                             :label "Auto 2 fast>slow"
                                             :range :variable}
                                        230 {:type  :led-sound-mixed-mode-1
                                             :label "Sound 1"
                                             :range :fixed}
                                        235 {:type  :led-sound-active
                                             :label "Sound 2"
                                             :range :fixed}
                                        240 {:type  :led-show-setting
                                             :label "Show, chan 2+3"
                                             :range :fixed})
                        (chan/functions :operation 2
                                        0 {:type  :blackout
                                           :label "Blackout"
                                           :range :fixed}
                                        10 {:type  :pars-only
                                            :label "Pars only"
                                            :range :fixed}
                                        20 {:type  :derby-only
                                            :label "Derby only"
                                            :range :fixed}
                                        30 {:type  :laser-only
                                            :label "Laser only"
                                            :range :fixed}
                                        40 {:type  :strobes-only
                                            :label "Strobes only"
                                            :range :fixed}
                                        50 {:type  :auto-pars-derby
                                            :label "Auto pars derby"
                                            :range :fixed}
                                        60 {:type  :operation-pars-laser
                                            :label "Auto pars laser"
                                            :range :fixed}
                                        70 {:type  :operation-auto-pars-and-strobes-only
                                            :label "Auto pars strobes"
                                            :range :fixed}
                                        80 {:type  :auto-derby-laser
                                            :label "Auto derby laser only"
                                            :range :fixed}
                                        90 {:type  :auto-strobes-derby
                                            :label "Auto strobes derby only"
                                            :range :fixed}
                                        100 {:type  :pars-derby-laser
                                             :label "Pars, derby, laser"
                                             :range :fixed}
                                        110 {:type  :pars-derby-strobes
                                             :label "Pars, derby, strobes"
                                             :range :fixed}
                                        120 {:type  :pars-laser-strobes
                                             :label "Pars, laser, strobes"
                                             :range :fixed}
                                        130 {:type  :derby-laser-strobes
                                             :label "Derby, laser, strobes"
                                             :range :fixed}
                                        140 {:type  :par-sound-active
                                             :label "Par Sound"
                                             :range :fixed}
                                        150 {:type  :derby-sound-active
                                             :label "Derby Sound"
                                             :range :fixed}
                                        160 {:type  :laser-sound-active
                                             :label "Laser Sound"
                                             :range :fixed}
                                        170 {:type  :strobe-sound-active
                                             :label "Strobe Sound"
                                             :range :fixed}
                                        180 {:type  :par-derby-sound-active
                                             :label "Par, Derby Sound"
                                             :range :fixed}
                                        190 {:type  :par-laser-sound-active
                                             :label "Par, Laser Sound"
                                             :range :fixed}
                                        200 {:type  :par-strobe-sound-active
                                             :label "Par, Strobe Sound"
                                             :range :fixed}
                                        210 {:type  :derby-laser-sound-active
                                             :label "Derby, Laser Sound"
                                             :range :fixed}
                                        220 {:type  :derby-strobe-sound-active
                                             :label "Derby, Strobe Sound"
                                             :range :fixed}
                                        230 {:type  :pars-derby-laser-sound-active
                                             :label "Par, Derby, Laser Sound"
                                             :range :fixed}
                                        240 {:type  :pars-derby-strobes-sound-active
                                             :label "Par, Derby, Strobe Sound"
                                             :range :fixed}
                                        245 {:type  :pars-laser-strobes-sound-active
                                             :label "Par, Laser, Strobe Sound"
                                             :range :fixed}
                                        250 {:type  :derby-laser-strobes-sound-active
                                             :label "Derby, Laser, Strobe Sound"
                                             :range :fixed})
                        (chan/fine-channel :auto-speed 3
                                           :function-name "Auto Speed"
                                           :var-label "Auto Speed")]}
            :11-channels
            {:channels [(chan/color 1 :red)
                        (chan/color 2 :green)
                        (chan/color 3 :blue)
                        (chan/color 4 :uv :label "UV" :hue (when mix-uv 270))
                        (chan/functions :pars-and-derby-strobe-controls 5
                                        0 {:type  :dimmer
                                           :label "Pars+Derbys Dimmer"
                                           :range :variable}
                                        128 {:type  :pars-derby-strobe-speed
                                             :label "Pars+Derbys Strobe Speed"
                                             :range :variable}
                                        240 {:type  :pars-derby-sound-strobe
                                             :label "Pars+Derbys Sound Strobe"
                                             :range :variable}
                                        250 {:type  :pars-derby-full-strobe
                                             :label "Pars+Derbys Full Strobe"
                                             :range :fixed})
                        (chan/functions :derby-motor-rotation 6
                                        0 nil
                                        5 {:type  :derby-clockwise
                                           :label "Derby CW, slow>fast"
                                           :range :variable}
                                        128 nil
                                        134 {:type  :derby-counter-clockwise
                                             :label "Derby CCW, slow>fast"
                                             :range :variable})
                        (chan/functions :red-laser-controls 7
                                        0 nil
                                        5 {:type  :red-laser-on
                                           :label "Red laser on"
                                           :range :fixed}
                                        55 {:type  :red-laser-strobe-speed
                                            :label "Red laser strobe speed"
                                            :range :variable})
                        (chan/functions :green-laser-controls 8
                                        0 nil
                                        5 {:type  :green-laser-on
                                           :label "Green laser on"
                                           :range :fixed}
                                        55 {:type  :green-laser-strobe-speed
                                            :label "Green laser strobe speed"
                                            :range :variable})
                        (chan/functions :laser-movement-speed 9
                                        0 nil
                                        5 {:type  :laser-clockwise
                                           :label "Laser CW, slow>fast"
                                           :range :variable}
                                        128 nil
                                        134 {:type  :laser-counter-clockwise
                                             :label "Laser CCW, slow>fast"
                                             :range :variable})
                        (chan/functions :white-strobe 10  ; Cannot be used at the same time as 11
                                        0 {:type  :dimmer
                                           :label "White strobe dimmer"
                                           :range :variable}
                                        55 {:type  :white-strobe-speed
                                            :label "White strobe speed"
                                            :range :variable})
                        (chan/functions :uv-strobe 11  ; Cannot be used at the same time as 10
                                        0 {:type  :dimmer
                                           :label "UV strobe dimmer"
                                           :range :variable}
                                        55 {:type  :uv-strobe-speed
                                            :label "UV strobe speed"
                                            :range :variable})]}
            ;; TODO: Split 23-channels into individual heads, to be patched separately, for better semantic fit.
            :23-channels
            {:channels [(chan/functions :strobe-patterns 20
                                        0 {:type  :strobe-auto-1
                                           :label "Strobe Auto 1"
                                           :range :fixed}
                                        10 {:type  :strobe-auto-2
                                            :label "Strobe Auto 2"
                                            :range :fixed}
                                        30 {:type  :strobe-auto-3
                                            :label "Strobe Auto 3"
                                            :range :fixed}
                                        50 {:type  :strobe-auto-4
                                            :label "Strobe Auto 4"
                                            :range :fixed}
                                        70 {:type  :strobe-auto-5
                                            :label "Strobe Auto 5"
                                            :range :fixed}
                                        90 {:type  :strobe-auto-6
                                            :label "Strobe Auto 6"
                                            :range :fixed}
                                        110 {:type  :strobe-auto-7
                                             :label "Strobe Auto 7"
                                             :range :fixed}
                                        130 {:type  :strobe-auto-8
                                             :label "Strobe Auto 8"
                                             :range :fixed}
                                        150 {:type  :strobe-auto-9
                                             :label "Strobe Auto 9"
                                             :range :fixed}
                                        170 {:type  :strobe-auto-10
                                             :label "Strobe Auto 10"
                                             :range :fixed}
                                        190 {:type  :strobe-speed
                                             :label "Strobe Speed"
                                             :range :variable}
                                        210 {:type  :strobe-sound-active
                                             :label "Strobe Sound"
                                             :range :variable})
                        (chan/fine-channel :strobe-speed 23
                                           :function-name "Strobe Speed Slow to Fast"
                                           :var-label "Strobe Slow>Fast")]
             :heads    [{:x 0.5 :y -0.05 :z 0 ; TODO: Measure and fine-tune location
                         :channels [(chan/functions :derby-control 11
                                                    0 nil
                                                    25 {:type  :derby-red
                                                        :label "Derby 1 Red"
                                                        :range :variable}
                                                    50 {:type  :derby-green
                                                        :label "Derby 1 Green"
                                                        :range :variable}
                                                    75 {:type  :derby-blue
                                                        :label "Derby 1 Blue"
                                                        :range :variable}
                                                    100 {:type  :derby-red-green
                                                         :label "Derby 1 RG"
                                                         :range :variable}
                                                    125 {:type  :derby-red-blue
                                                         :label "Derby 1 RB"
                                                         :range :variable}
                                                    150 {:type  :derby-green-blue
                                                         :label "Derby 1 GB"
                                                         :range :variable}
                                                    175 {:type  :derby-red-green-blue
                                                         :label "Derby 1 RGB"
                                                         :range :variable}
                                                    200 {:type  :derby-auto-single-colors
                                                         :label "Derby 1 Auto (1 color)"
                                                         :range :variable}
                                                    225 {:type  :derby--auto-two-colors
                                                         :label "Derby 1 Auto (2 colors)"
                                                         :range :variable})
                                    (chan/functions :derby-strobe-speed 12
                                                    0 nil
                                                    10 {:type  :strobe
                                                        :label "Derby 1 Strobe, 0-30Hz"
                                                        :range :variable}  ; TODO: add scale-fn?
                                                    240 {:type  :strobe-sound
                                                         :label "Derby 1 Strobe Sound"
                                                         :range :variable})
                                    (chan/functions :derby-rotation 13
                                                    0 nil
                                                    5 {:type  :derby-clockwise-slow-to-fast
                                                       :label "Derby 1 CW Slow>Fast"
                                                       :range :variable}
                                                    128 nil
                                                    134 {:type  :derby-counter-clockwise-slow-to-fast
                                                         :label "Derby 1 CCW Slow>Fast"
                                                         :range :variable})]}
                        {:x 0.2 :y 0 :z 0 ; TODO: Measure and fine-tune location
                         :channels [(chan/color 1 :red)
                                    (chan/color 2 :green)
                                    (chan/color 3 :blue)
                                    (chan/color 4 :uv :label "UV" :hue (when mix-uv 270))
                                    (chan/functions :par-dimmer-strobe 5
                                                    0 {:type  :dimmer
                                                       :label "Par 1 Dimmer"
                                                       :range :variable}
                                                    128 {:type  :strobe
                                                         :label "Par 1 Strobe Slow>Fast"
                                                         :range :variable}
                                                    240 {:type  :strobe-sound
                                                         :label "Par 1 Strobe Sound"
                                                         :range :variable}
                                                    250 {:type  :full-on
                                                         :label "Par 1 RGB 100%"
                                                         :range :fixed})]}
                        {:x        -0.2 :y 0 :z 0 ; TODO: Measure and fine-tune location
                         :channels [(chan/color 6 :red)
                                    (chan/color 7 :green)
                                    (chan/color 8 :blue)
                                    (chan/color 9 :uv :label "UV" :hue (when mix-uv 270))
                                    (chan/functions :par-dimmer-strobe 10
                                                    0 {:type  :dimmer
                                                       :label "Par 2 Dimmer"
                                                       :range :variable}
                                                    128 {:type  :strobe
                                                         :label "Par 2 Strobe Slow>Fast"
                                                         :range :variable}
                                                    240 {:type  :strobe-sound
                                                         :label "Par 2 Strobe sound"
                                                         :range :variable}
                                                    250 {:type  :full-on
                                                         :label "Par 2 RGB 100%"
                                                         :range :fixed})]}
                        {:x -0.5 :y -0.05 :z 0 ; TODO: Measure and fine-tune location
                         :channels [(chan/functions :derby-control 14
                                                    0 nil
                                                    25 {:type  :derby-red
                                                        :label "Derby 2 Red"
                                                        :range :variable}
                                                    50 {:type  :derby-green
                                                        :label "Derby 2 Green"
                                                        :range :variable}
                                                    75 {:type  :derby-blue
                                                        :label "Derby 2 Blue"
                                                        :range :variable}
                                                    100 {:type  :derby-red-green
                                                         :label "Derby 2 RG"
                                                         :range :variable}
                                                    125 {:type  :derby-red-blue
                                                         :label "Derby 2 RB"
                                                         :range :variable}
                                                    150 {:type  :derby-green-blue
                                                         :label "Derby 2 GB"
                                                         :range :variable}
                                                    175 {:type  :derby-red-green-blue
                                                         :label "Derby 2 RGB"
                                                         :range :variable}
                                                    200 {:type  :derby-auto-single-colors
                                                         :label "Derby 2 Auto (1 color)"
                                                         :range :variable}
                                                    225 {:type  :derby-auto-tfwo-colors
                                                         :label "Derby 2 Auto (2 colors)"
                                                         :range :variable})
                                    (chan/functions :derby-strobe-speed 15
                                                    0 nil
                                                    10 {:type  :derby-strobe-rate-strobe-0-30-hz
                                                        :label "Derby 2 Strobe, 0-30Hz"
                                                        :range :variablepar-2-rgb-100}
                                                    240 {:type  :derby-strobe-rate-strobe-to-sound
                                                         :label "Derby 2 Strobe Sound"
                                                         :range :variable})
                                    (chan/functions :derby-rotation 16
                                                    0 nil
                                                    5 {:type  :derby-rotation-rotate-clockwise-slow-to-fast
                                                       :label "Derby 2 CW Slow>Fast"
                                                       :range :variable}
                                                    128 nil
                                                    134 {:type  :derby-rotation-rotate-counter-clockwise-slow-to-fast
                                                         :label "Derby 2 CCW Slow>Fast"
                                                         :range :variable})]}
                        {:x 0.0 :y 0.25 :z 0 ; TODO: Measure and fine-tune location
                         :channels [(chan/functions :laser-color 17
                                                    0 nil
                                                    40 {:type  :laser-red-on
                                                        :label "Laser Red"
                                                        :range :variable}
                                                    80 {:type  :laser-green-on
                                                        :label "Laser Green"
                                                        :range :variable}
                                                    120 {:type  :laser-red-green-on
                                                         :label "Laser RG"
                                                         :range :variable}
                                                    160 {:type  :laser-red-on-green-strobe
                                                         :label "Laser RGs"
                                                         :range :variable}
                                                    200 {:type  :laser-green-on-red-strobe
                                                         :label "Laser RsG"
                                                         :range :variable}
                                                    240 {:type  :laser-red-green-alternate-strobe
                                                         :label "Laser RsGs"
                                                         :range :variable})
                                    (chan/functions :laser-strobe 18
                                                    0 nil
                                                    10 {:type  :laser-strobe-strobe-speed-slow-to-fast
                                                        :label "Laser Strobe Slow>Fast"
                                                        :range :variable}
                                                    240 {:type  :laser-strobe-strobe-to-sound
                                                         :label "Laser Strobe Sound"
                                                         :range :variable})
                                    (chan/functions :laser-pattern 19
                                                    0 nil
                                                    5 {:type  :laser-clockwise-slow-to-fast
                                                       :label "Laser CW Slow>Fast"
                                                       :range :variable}
                                                    128 nil
                                                    134 {:type  :laser-counter-clockwise-slow-to-fast
                                                         :label "Laser CCW Slow>Fast"
                                                         :range :variable})]}
                        {:x        0 :y 0.2 :z 0 ; TODO: Measure and fine-tune height?
                         :channels [(chan/dimmer 21)]}  ; White strobe, cannot be used with channel 22
                        {:x        0 :y 0.0 :z 0 ; TODO: Measure and fine-tune height?
                         :channels [(chan/dimmer 22)]}]}))))  ; UV strobe, cannot be used with channel 21``

(defn hurricane-1800-flex
  "[Hurricane 1800 Flex](http://www.chauvetlighting.com/hurricane-1800-flex.html) fogger."
  []
  {:channels [(chan/functions :fog 1
                              0 nil
                              6 "Fog")]
   :name "Chauvet Hurricane 1800 Flex"})

(defn intimidator-scan-led-300
  "[Intimidator Scan LED 300](http://www.chauvetlighting.com/intimidator-scan-led-300.html) compact scanner.

  This fixture can be configured to use either 8 or 11 DMX channels.
  If you do not specify a mode when patching it, `:11-channel` is
  assumed; you can pass a value of `:8-channel` for `mode` if you are
  using it that way.

  The standard orientation to hang this fixture is with the Chauvet
  logo and LED upright and facing the audience, but tilted up and away
  from them at a 45 degree angle.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and heavily revised by James Elliott.

  The original fixture defintition was created by Craig Cudmore
  using Q Light Controller Plus version 4.7.0 GIT.

  QLC+ Fixture Type: Scanner."
  ([]
   (intimidator-scan-led-300 :11-channel))
  ([mode]
   (let [gobo-names ["Pink Dots" "Purple Bubbles" "45 Adapter" "Nested Rings" "Rose" "Triangles" "Galaxy"]]
     (letfn [(build-color-wheel [channel]
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
             (build-shutter [channel]
               (chan/functions :shutter channel
                               0 "Shutter Closed"
                               4 "Shutter Open"
                               8 {:type :strobe
                                  :label "Strobe"
                                  :range :variable}
                               216 "Shutter Open 2"))
             (build-gobo-wheel [channel]
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
             (build-gobo-rotation [channel]
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
       (merge {:name "Chauvet Intimidator Scan LED 300"
               :pan-center 128 :pan-half-circle -256
               :tilt-center 0 :tilt-half-circle -1024
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
                            (chan/focus 8)]}))))))

(defn intimidator-spot-led-150
  "[Intimidator Spot LED 150](http://www.chauvetlighting.com/intimidator-spot-led-150.html) moving yoke.

  This fixture can be configured to use either 6 or 11 DMX channels.
  If you do not specify a mode when patching it, `:11-channel` is
  assumed; you can pass a value of `:6-channel` for `mode` if you are
  using it that way.

  The standard orientation for this fixture is hung with the Chauvet
  label and LED indicator upside-down and facing towards the audience.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and heavily revised by James Elliott.

  The original fixture defintition was created by Tavon Markov
  using Q Light Controller Plus version 4.8.3 GIT.

  QLC+ Fixture Type: Moving Head."
  ([]
   (intimidator-spot-led-150 :11-channel))
  ([mode]
   (let [gobo-names ["Quotes" "Warp Spots" "4 Dots" "Sail Swirl" "Starburst" "Star Field" "Optical Tube"
                     "Sun Swirl" "Star Echo"]]
     (letfn [(build-color-wheel [channel]
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
             (build-shutter [channel]
               (chan/functions :shutter channel
                               0 "Shutter Closed"
                               4 "Shutter Open"
                               8 {:type :strobe
                                  :label "Strobe (0-20Hz)"
                                  :scale-fn (partial chan-fx/function-value-scaler 0 200)
                                  :range :variable}
                               216 "Shutter Open 2"))
             (build-gobo-wheel [channel]
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
       (merge {:name "Chauvet Intimidator Spot LED 150"
               :pan-center 170 :pan-half-circle -85
               :tilt-center 42 :tilt-half-circle 192
               :mode mode}
              (case mode
                :11-channel
                {:channels [(chan/pan 1 3)
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
                {:channels [(chan/pan 1)
                            (chan/tilt 2)
                            (build-color-wheel 3)
                            (build-shutter 4)
                            (chan/dimmer 5)
                            (build-gobo-wheel 6)]}))))))

(defn kinta-x
  "[Kinta X](http://www.chauvetlighting.com/kinta-x.html) derby effect."
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
   :name "Chauvet Kinta X"})

(defn led-techno-strobe
  "[LED Techno Strobe](http://www.chauvetlighting.com/led-techno-strobe.html)
  strobe light."
  []
  {:channels [(chan/functions :control 1
                              0 "Intensity Control"
                              (range 30 209 30) "Program"
                              210 :sound-active)
              (chan/functions :strobe 2 0 nil
                              1 :strobe)  ; TODO: Measure speed, add scale-fn
              (chan/dimmer 3 :inverted-from 1)]
   :name "Chauvet LED Techno Strobe"})

(defn led-techno-strobe-rgb
  "[LED Techno Strobe RGB](http://www.chauvetlighting.com/led-techno-strobe-rgb.html)
  color mixing strobe light.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by Davey D
  using Q Light Controller Plus version 4.6.0.

  QLC+ Fixture Type: Color Changer."
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
              (chan/dimmer 6 :inverted-from 0)]
   :name "Chauvet LED Techno Strobe RGB"})

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
  ([]
   (scorpion-storm-fx-rgb :7-channel))
  ([mode]
   (merge {:name "Chauvet Scorpion Storm FX RGB"
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
   :name "Chauvet Scorpion Storm RGX"})

(defn q-spot-160
  "Q Spot 160 moving yoke.

  This fixture can be configured to use either 9 or 12 DMX channels.
  If you do not specify a mode when patching it, `:12-channel` is
  assumed; you can pass a value of `:9-channel` for `mode` if you are
  using it that way.

  The standard hanging orientation is with the Chauvet label and LED
  panel upside-down and facing the house-right wall (the positive X
  axis direction)."
  ([]
   (q-spot-160 :12-channel))
  ([mode]
   (let [gobo-names ["Splat" "Spot Sphere" "Fanned Squares" "Box" "Bar" "Blue Starburst" "Perforated Pink"]]
     (letfn [(build-color-wheel [channel]
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
             (build-shutter [channel]
               (chan/functions :shutter channel
                               0 "Shutter Closed"
                               32 "Shutter Open"
                               64 :strobe
                               96 "Shutter Open 2"
                               128 :pulse-strobe
                               160 "Shutter Open 3"
                               192 :random-strobe
                               224 "Shutter Open 4"))
             (build-gobo-wheel [channel]
               (chan/functions :gobo channel
                               (range 0 79 10) (build-gobo-entries false (concat ["Open"] gobo-names))
                               (range 80 219 20) (build-gobo-entries true (reverse gobo-names))
                               220 {:type :gobo-scroll
                                    :label "Gobo Scroll"
                                    :var-label "Scroll Speed"
                                    :range :variable}))
             (build-gobo-rotation [channel]
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
             (build-control-channel [channel]
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
       (merge {:name "Chauvet Q Spot 160"
               :pan-center 85 :pan-half-circle 85
               :tilt-center 218 :tilt-half-circle -180
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
                            (build-control-channel 9)]}))))))

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

