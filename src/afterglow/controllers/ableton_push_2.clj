(ns afterglow.controllers.ableton-push-2
  "Allows the Ableton Push 2 to be used as a control surface for
  Afterglow. Its features are described in the [online
  documentation](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#using-ableton-push)."
  {:author "James Elliott"}
  (:require [afterglow.controllers.ableton-push :as push :refer [set-pad-velocity]]
            [afterglow.controllers :as controllers]
            [afterglow.controllers.tempo :as tempo]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [master-get-level master-set-level]]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.util :as util]
            [afterglow.version :as version]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre])
  (:import [afterglow.effects Effect]
           [org.deepsymmetry Wayang]
           [java.util Arrays]
           [java.awt GraphicsEnvironment Graphics2D Font AlphaComposite RenderingHints]
           [javax.sound.midi ShortMessage]))

(defonce fonts-loaded
  (atom false))

(defn load-fonts
  "Load and register the fonts we will use to draw on the display, if
  they have not already been."
  []
  (or @fonts-loaded
      (let [ge (GraphicsEnvironment/getLocalGraphicsEnvironment)]
        (doseq [font-file ["/public/fonts/Open_Sans_Condensed/OpenSans-CondLight.ttf"
                           "/public/fonts/Open_Sans_Condensed/OpenSans-CondLightItalic.ttf"
                           "/public/fonts/Open_Sans_Condensed/OpenSans-CondBold.ttf"
                           "/public/fonts/Roboto/Roboto-Medium.ttf"
                           "/public/fonts/Roboto/Roboto-MediumItalic.ttf"
                           "/public/fonts/Roboto/Roboto-Regular.ttf"
                           "/public/fonts/Roboto/Roboto-Bold.ttf"
                           "/public/fonts/Roboto/Roboto-Italic.ttf"
                           "/public/fonts/Roboto/Roboto-BoldItalic.ttf"
                           "/public/fonts/Lekton/Lekton-Regular.ttf"]]
            (.registerFont ge (java.awt.Font/createFont
                               java.awt.Font/TRUETYPE_FONT
                               (.getResourceAsStream Effect font-file))))
        (reset! fonts-loaded true))))

(defn get-display-font
  "Find one of the fonts configured for use on the display by keyword,
  which should be one of `:condensed`, `:condensed-light`, `:roboto`,
  or `:roboto-medium`. The `style` argument is a `java.awt.Font` style
  constant, and `size` is point size.

  Roboto is available in all style variations, Roboto Medium in plain
  and italic, Condensed only in bold, and condensed light in plain and
  italic."
  [k style size]
  (case k
    :condensed       (Font. "Open Sans Condensed" Font/BOLD size)
    :condensed-light (Font. "Open Sans Condensed Light" style size)
    :monospace       (Font. "Lekton" style size)
    :roboto          (Font. "Roboto" style size)
    :roboto-medium   (Font. "Roboto Medium" style size)))

(defn dim
  "Return a dimmed version of a color."
  [color]
  (colors/darken color 35))

(def off-color
  "The color of buttons that are completely off."
  (colors/create-color :black))

(def amber-color
  "The color for bright amber buttons."
  (colors/create-color :h 45 :s 100 :l 50))

(def dim-amber-color
  "The color for dim amber buttons."
  (dim amber-color))

(def red-color
  "The color for bright red buttons."
  (colors/create-color :red))

(def dim-red-color
  "The color for dim red buttons."
  (dim red-color))

(def green-color
  "The color for bright green buttons."
  (colors/create-color :green))

(def dim-green-color
  "The color for dim green buttons."
  (dim green-color))

(def white-color
  "The color for bright white buttons."
  (colors/create-color :white))

(def dim-white-color
  "The color for dim white buttons."
  (colors/darken white-color 90))

(def default-track-color
  "The color gauge tracks will use unless otherwise specified."
  (colors/darken white-color 50))


(defn send-sysex
  "Send a MIDI System Exclusive command to the Push 2 with the proper
  prefix. The `command` argument begins with the Command ID of the
  desired command, as listed in Table 2.4.2 of the Ableton Push 2 MIDI
  and Display Interface Manual, followed by its parameter bytes. The
  `SOX` byte, Ableton Sysex ID, device ID, and model ID will be
  prepended, and the `EOX` byte appended, before sending the command."
  [controller command]
  (midi/midi-sysex (:port-out controller)
                   (concat [0xf0 0x00 0x21 0x1d (:device-id controller) 0x01] command [0xf7])))

(defn- request-led-palette-entry
  "Ask the Push 2 for the LED palette entry with the specified index."
  [controller index]
  (send-sysex controller [0x04 index]))

(defn- save-led-palette-entry
  "Record an LED palette entry we have received in response to our
  startup query, so we can preserve the white palette when setting RGB
  colors, and restore all LED palettes when we suspend or exit our
  mapping.

  Make a note of the fact that we received a palette response at the
  current moment in time in `gather-timestamp`, and if this was not
  the final palette entry, request the next one. If it was the final
  one, deliver a true value to `gather-timestamp`."
  [controller data gather-timestamp gather-promise]
  (let [index (first data)]
    (swap! (:led-palettes controller) assoc index (vec (rest data)))
    (reset! gather-timestamp (at-at/now))
    (if (< index 127)  ; Ask for the next entry unless we have received them all
      (request-led-palette-entry controller (inc index))
      (deliver gather-promise true))))

(defn- sysex-received
  "Process a MIDI System Exclusive reply from the Push 2. The `msg`
  argument is the raw data we have just received. If
  `gather-timestamp` and `gather-promise` were supplied, this reply
  was received during startup when we are gathering LED palette
  entries, so we should use them to record any palette response."
  ([controller msg]
   (sysex-received controller msg nil nil))
  ([controller msg gather-timestamp gather-promise]
   (if (= (vec (take 5 (:data msg))) [0x00 0x21 0x1d (:device-id controller) 0x01])
     (let [data (map int (butlast (drop 5 (:data msg))))
           command (first data)
           args (rest data)]
       (case command
         0x04 (if (some? gather-timestamp)
                (save-led-palette-entry controller args gather-timestamp gather-promise)
                (timbre/warn "Ignoring Push 2 LED palette response when not gathering palette."))

         (timbre/warn "Ignoring SysEx message from Push 2 with command ID" command)))
     (timbre/warn "Received unrecognized SysEx message from Push 2 port." (vec (:data msg))))))

(defn- gather-led-palettes
  "Ask the Push 2 for all of its LED palettes. We ask for the first,
  then when we receive that, ask for the next, until we have got them
  all, to avoid overflowing its buffers. We will wait for up to half a
  second for this process to complete. If that elapses, and it has
  been more than 100ms since we sent our last request, we give up.

  Return a truthy value to indicate success."
  [controller]
  (let [gather-timestamp (atom (at-at/now))
        gather-promise (promise)
        startup-handler (fn [message]
                          (if (= 0xf0 (:status message))
                            (sysex-received controller message gather-timestamp gather-promise)
                            (timbre/info "Ignoring non-sysex message received during Push 2 startup.")))]
    (try
      (amidi/add-device-mapping (:port-in controller) startup-handler)
      (loop []
        (request-led-palette-entry controller 0)
        (or (deref gather-promise 500 false)
            (if (> (- (at-at/now) @gather-timestamp) 100)
              (timbre/error "Failed to gather LED Palette entries for Push 2; giving up.")
              (do
                (timbre/warn "Gathering LED Palette entries for Push 2 is taking more than half a second.")
                (recur)))))
      (finally (amidi/remove-device-mapping (:port-in controller) startup-handler)))))

(defn- restore-led-palettes
  "Set the LED palettes back to the way we found them during our
  initial binding. This is called when clearing the interface when
  exiting user mode or deactivating the binding, so we can gracefully
  coexist with Live."
  [controller]
  (let [sent (volatile! 0)]
    (doseq [[index palette] @(:led-palettes controller)]
      (send-sysex controller (concat [0x03 index] palette))
      (when (zero? (rem (vswap! sent inc) 10))
        (Thread/sleep 5)))))

(defn set-pad-color
  "Set the color of one of the 64 touch pads to a specific RGB color.
  If the color is black, we send a note off to the pad. Otherwise, we
  take over the color palette entry whose velocity matches the note
  number of the pad, and set it to the desired RGB value, then send it
  a note with the velocity corresponding to the palette entry we just
  adjusted.

  Since we also have to set a white value, we pass along the white
  value that was present in the palette we found for this velocity
  when initially binding to the Push 2."
  [controller x y color]
  {:pre [(<= 0 x 7) (<= 0 y 7)]}
  (let [note (+ 36 x (* y 8))
        palette (get @(:led-palettes controller) note)]
    (if (util/float= (colors/lightness color) 0.0)
      (midi/midi-note-off (:port-out controller) note)
      (let [r (colors/red color)
            g (colors/green color)
            b (colors/blue color)]
        (send-sysex controller [0x03 note (bit-and r 0x7f) (quot r 0x80) (bit-and g 0x7f) (quot g 0x80)
                                (bit-and b 0x7f) (quot b 0x80) (get palette 6) (get palette 7)])
        (midi/midi-note-on (:port-out controller) note note)))))

(def control-buttons
  "The labeled buttons which send and respond to Control Change
  events."
  {:tap-tempo             {:control 3 :kind :monochrome}
   :metronome             {:control 9 :kind :monochrome}

   :master                {:control 28 :kind :monochrome}

   :quarter               {:control 36 :kind :color :index 8}
   :quarter-triplet       {:control 37 :kind :color :index 9}
   :eighth                {:control 38 :kind :color :index 10}
   :eighth-triplet        {:control 39 :kind :color :index 11}
   :sixteenth             {:control 40 :kind :color :index 12}
   :sixteenth-triplet     {:control 41 :kind :color :index 13}
   :thirty-second         {:control 42 :kind :color :index 14}
   :thirty-second-triplet {:control 43 :kind :color :index 15}

   :left-arrow            {:control 44 :kind :monochrome}
   :right-arrow           {:control 45 :kind :monochrome}
   :up-arrow              {:control 46 :kind :monochrome}
   :down-arrow            {:control 47 :kind :monochrome}

   :select                {:control 48 :kind :monochrome}
   :shift                 {:control 49 :kind :monochrome}

   :note                  {:control 50 :kind :monochrome}
   :session               {:control 51 :kind :monochrome}

   :add-device            {:control 52 :kind :monochrome}
   :add-track             {:control 53 :kind :monochrome}

   :device-mode           {:control 110 :kind :monochrome}
   :browse-mode           {:control 111 :kind :monochrome}
   :mix-mode              {:control 112 :kind :monochrome}
   :clip-mode             {:control 113 :kind :monochrome}

   :repeat                {:control 56 :kind :monochrome}
   :accent                {:control 57 :kind :monochrome}
   :scales                {:control 58 :kind :monochrome}
   :layout                {:control 31 :kind :monochrome}

   :setup                 {:control 30 :kind :monochrome}
   :user-mode             {:control 59 :kind :monochrome}

   :page-left             {:control 62 :kind :monochrome}
   :page-right            {:control 63 :kind :monochrome}
   :octave-down           {:control 54 :kind :monochrome}
   :octave-up             {:control 55 :kind :monochrome}

   :stop                  {:control 85 :kind :color :index 2}  ; The play button, but stop for stop mode.
   :record                {:control 86 :kind :color :index 3 :dim-color dim-red-color :bright-color red-color}
   :automate              {:control 89 :kind :color :index 4}
   :fixed-length          {:control 90 :kind :monochrome}

   :new                   {:control 87 :kind :monochrome}
   :duplicate             {:control 88 :kind :monochrome}

   :quantize              {:control 116 :kind :monochrome}
   :double                {:control 117 :kind :monochrome}
   :convert               {:control 35 :kind :monochrome}

   :mute                  {:control 60 :kind :color :index 5}
   :solo                  {:control 61 :kind :color :index 6}
   :stop-clip             {:control 29 :kind :color :index 7}

   :delete                {:control 118 :kind :monochrome}
   :undo                  {:control 119 :kind :monochrome}

   :top-pad-0             {:control 20 :kind :color}
   :top-pad-1             {:control 21 :kind :color}
   :top-pad-2             {:control 22 :kind :color}
   :top-pad-3             {:control 23 :kind :color}
   :top-pad-4             {:control 24 :kind :color}
   :top-pad-5             {:control 25 :kind :color}
   :top-pad-6             {:control 26 :kind :color}
   :top-pad-7             {:control 27 :kind :color}

   :encoder-pad-0         {:control 102 :kind :color}
   :encoder-pad-1         {:control 103 :kind :color}
   :encoder-pad-2         {:control 104 :kind :color}
   :encoder-pad-3         {:control 105 :kind :color}
   :encoder-pad-4         {:control 106 :kind :color}
   :encoder-pad-5         {:control 107 :kind :color}
   :encoder-pad-6         {:control 108 :kind :color}
   :encoder-pad-7         {:control 109 :kind :color}})

(defn set-cc-led-color
  "Set one of the color LEDs that respond to control change values to
  a particular color. If the color is black, we send a control value
  of zero. Otherwise, we take over the color palette entry assigned to
  the LED, and set it to the desired RGB value, then send it a control
  change with the velocity corresponding to the palette entry we just
  adjusted.

  Since we also have to set a white value, we pass along the white
  value that was present in the palette we found for this entry
  when initially binding to the Push 2."
  [controller control palette-index color]
  (let [palette (get @(:led-palettes controller) palette-index)]
    (if (util/float= (colors/lightness color) 0.0)
      (midi/midi-control (:port-out controller) control 0)
        (let [r (colors/red color)
              g (colors/green color)
              b (colors/blue color)]
          (send-sysex controller [0x03 palette-index (bit-and r 0x7f) (quot r 0x80) (bit-and g 0x7f) (quot g 0x80)
                                  (bit-and b 0x7f) (quot b 0x80) (get palette 6) (get palette 7)])
        (midi/midi-control (:port-out controller) control palette-index)))))

(defn set-button-color
  "Set one of the labeled buttons to a particular color (if it is a
  monochrome button, the lightness of the color is translated to a
  control value; otherwise, the palette entry assigned to the button
  is set to the specified color, and the corresponding velocity is
  sent."
  [controller button color]
  (if (= :monochrome (:kind button))
    (midi/midi-control (:port-out controller) (:control button)
                       (math/round (* (/ (colors/lightness color) 100) 127)))
    (set-cc-led-color controller (:control button) (or (:index button) (:control button)) color)))

(defn set-top-pad-color
  "Set one of the top-row pads (between the grid and the display) to a
  particular color."
  [controller x color]
  (set-button-color controller (get control-buttons (keyword (str "top-pad-" x))) color))

(defn set-encoder-pad-color
  "Set one of the pads below the row of display encoders to a
  particular color."
  [controller x color]
  (set-button-color controller (get control-buttons (keyword (str "encoder-pad-" x))) color))

(defn ^:deprecated set-display-line
  "Sets a line of the text display."
  [controller line bytes]
  {:pre [(<= 0 line 3)]}
  (let [message (concat [240 71 127 21 (+ line 24) 0 69 0]
                        (take 68 (concat (map int bytes) (repeat 32)))
                        [247])]
    #_(midi/midi-sysex (:port-out controller) message)))

(def touch-strip-mode-flags
  "The values which are combined to set the touch strip into
  particular modes."
  {:touch-strip-controlled-by-push        0
   :touch-strip-controlled-by-host        1
   :touch-strip-host-sends-values         0
   :touch-strip-host-sends-sysex          2
   :touch-strip-values-sent-as-pitch-bend 0
   :touch-strip-values-sent-as-mod-wheel  4
   :touch-strip-leds-show-bar             0
   :touch-strip-leds-show-point           8
   :touch-strip-bar-starts-at-bottom      0
   :touch-strip-bar-starts-at-center      16
   :touch-strip-auto-return-inactive      0
   :touch-strip-auto-return-active        32
   :touch-strip-auto-return-to-bottom     0
   :touch-strip-auto-return-to-center     64})

(defn build-touch-strip-mode
  "Calculate a touch strip mode byte based on a list of flags (keys in
  `touch-strip-mode-flags`)."
  [& flags]
  (apply + (map touch-strip-mode-flags (set flags))))

(def touch-strip-mode-default
  "The mode to which we should return the touch strip when we are
  shutting down."
  (build-touch-strip-mode :touch-strip-controlled-by-push :touch-strip-host-sends-values
                          :touch-strip-values-sent-as-pitch-bend :touch-strip-leds-show-point
                          :touch-strip-bar-starts-at-bottom :touch-strip-auto-return-active
                          :touch-strip-auto-return-to-center))

(def touch-strip-mode-level
  "The mode to which we should set the touch strip when the user is
  editing a pan-style control."
  (build-touch-strip-mode :touch-strip-controlled-by-host :touch-strip-host-sends-values
                          :touch-strip-values-sent-as-pitch-bend :touch-strip-leds-show-bar
                          :touch-strip-bar-starts-at-bottom :touch-strip-auto-return-inactive
                          :touch-strip-auto-return-to-center))

(def touch-strip-mode-pan
  "The mode to which we should set the touch strip when the user is
  editing a level-style control."
  (build-touch-strip-mode :touch-strip-controlled-by-host :touch-strip-host-sends-values
                          :touch-strip-values-sent-as-pitch-bend :touch-strip-leds-show-bar
                          :touch-strip-bar-starts-at-center :touch-strip-auto-return-inactive
                          :touch-strip-auto-return-to-center))

(def touch-strip-mode-hue
  "The mode to which we should set the touch strip when the user is
  editing a hue."
  (build-touch-strip-mode :touch-strip-controlled-by-host :touch-strip-host-sends-values
                          :touch-strip-values-sent-as-pitch-bend :touch-strip-leds-show-point
                          :touch-strip-bar-starts-at-bottom :touch-strip-auto-return-inactive
                          :touch-strip-auto-return-to-center))

(defn- clear-display-buffer
  "Clear the graphical display buffer in preparation for drawing an
  interface frame."
  [controller]
  (let [graphics (.createGraphics (:display-buffer controller))]
    (.setPaint graphics java.awt.Color/BLACK)
    (.fillRect graphics 0 0 Wayang/DISPLAY_WIDTH Wayang/DISPLAY_HEIGHT)))

(defn clear-display
  "Clear the graphical display."
  [controller]
  (clear-display-buffer controller)
  (Wayang/sendFrame))

(defn create-graphics
  "Create the graphics object we will use to draw in the display, and
  configure its rendering hints properly."
  [controller]
  (let [graphics (.createGraphics (:display-buffer controller))]
    (.setRenderingHint graphics RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    graphics))

(defn show-labels
  "Illuminates all buttons with text labels, for development assistance."
  ([controller]
   (show-labels controller (colors/create-color :white)))
  ([controller color]
   (doseq [[_ button] control-buttons]
     (set-button-color controller button color))))

(defn- update-top-pads
  "Sees if any of the top row of pads have changed state since
  the interface was updated, and if so, sends the necessary MIDI
  messages to update them on the Push."
  [controller]
  (doseq [x (range 8)]
    (let [next-color (get @(:next-top-pads controller) x)]
      (when (not= next-color
                  (get @(:last-top-pads controller) x))
        (set-top-pad-color controller x next-color)
        (swap! (:last-top-pads controller) assoc x next-color)))))

(defn set-touch-strip-mode
  "Set the touch strip operating mode."
  [controller mode]
  (send-sysex controller [0x17 mode]))

(defn- update-touch-strip
  "Sees if the state of the touch strip has changed since the
  interface was updated, and if so, sends the necessary MIDI control
  values to update it on the Push."
  [controller]
  (let [next-strip @(:next-touch-strip controller)
        [_ last-mode] @(:last-touch-strip controller)]
    (when (not= next-strip @(:last-touch-strip controller))
      (if next-strip
        (let [[value mode] next-strip
              message (ShortMessage.)]
          (if (not= mode last-mode)
            (do  ; When changing mode, we have to wait until the next frame to update the value.
              (set-touch-strip-mode controller mode)
              (reset! (:last-touch-strip controller) [nil mode]))
            (do
              (.setMessage message ShortMessage/PITCH_BEND 0 (rem value 128) (quot value 128))
              (midi/midi-send-msg (get-in controller [:port-out :receiver]) message -1)
              (reset! (:last-touch-strip controller) next-strip))))
        (do
          (set-touch-strip-mode controller touch-strip-mode-default)
          (reset! (:last-touch-strip controller) nil))))))

(defn- update-text-buttons
  "Sees if any labeled buttons have changed state since the last time
  the interface was updated, and if so, sends the necessary MIDI
  commands to update them on the Push."
  [controller]
  ;; First turn off any which were on before but no longer are
  (doseq [[button old-color] @(:last-text-buttons controller)]
    (when-not (get @(:next-text-buttons controller) button)
      (when-not (= off-color old-color)
        (set-button-color controller button off-color))))

  ;; Then, set any currently requested states
  (doseq [[button color] @(:next-text-buttons controller)]
    (when-not (= (get @(:last-text-buttons controller) button) color)
      (set-button-color controller button color)))

  ;; And record the new state for next time
  (reset! (:last-text-buttons controller) @(:next-text-buttons controller)))

(defn- ^:deprecated get-safe-text-byte
  "Converts a character to be displayed to a byte, but if it is
  outside the range that can be displayed by the Push, like Unicode,
  then change it to a byte representing an ellipsis."
  [c]
  (let [i (int c)]
    (if (> i 127)
      0
      i)))

(defn ^:deprecated write-display-text
  "Update a batch of characters within the display to be rendered on
  the next update."
  [controller row start text]
  {:pre [(<= 0 row 3) (<= 0 start 67)]}
  #_(let [bytes (map get-safe-text-byte text)]
    (doseq [[i val] (map-indexed vector bytes)]
      (aset (get (:next-display controller) row) (+ start i) (util/ubyte val)))))

(defn- ^:deprecated write-display-cell
  "Update a single text cell (of which there are four per row) in the
  display to be rendered on the next update."
  [controller row cell text]
  {:pre [(<= 0 row 3) (<= 0 cell 3)]}
  #_(let [bytes (take 17 (concat (map get-safe-text-byte text) (repeat 32)))]
    (doseq [[i val] (map-indexed vector bytes)]
      (aset (get (:next-display controller) row) (+ (* cell 17) i) (util/ubyte val)))))

(def button-cell-width
  "The number of pixels allocated to each button above or below the
  graphical display."
  (/ Wayang/DISPLAY_WIDTH 8))

(def button-cell-margin
  "The number of pixels to keep blank between labels of adjacent buttons."
  4)

(defn string-width
  "Determines how many pixels wide a string will be in a given font
  and render context."
  [text font render-context]
  (.getWidth (.getStringBounds font text render-context)))

(defn fit-string
  "Truncates a string (appending an ellipsis) enough to fit within a
  given pixel width."
  [text font render-context max-width]
  (if (or (clojure.string/blank? text) (<= (string-width text font render-context) max-width))
    text
    (loop [truncated (subs text 0 (dec (count text)))]
      (let [result (str truncated "…")]
        (if (or (clojure.string/blank? truncated) (<= (string-width result font render-context) max-width))
          result
          (recur (subs truncated 0 (dec (count truncated)))))))))

(defn set-graphics-color
  "Set the paint of the supplied graphics context to use the specified
  color."
  [graphics color]
  (.setPaint graphics (java.awt.Color. (colors/red color) (colors/green color) (colors/blue color)
                                       (colors/alpha color))))

(defn calculate-text-width
  "Figure out how many pixels wide some text will be in a given font."
  [graphics font text]
  (let [context (.getFontRenderContext graphics)]
    (.getWidth (.getStringBounds font text context))))

(defn draw-bottom-button-label
  "Draw a label for a button below the graphical display."
  [controller index text color]
  (let [graphics (create-graphics controller)
        font (get-display-font :roboto-medium Font/BOLD 14)
        context (.getFontRenderContext graphics)
        label (fit-string text font context (- button-cell-width button-cell-margin))
        width (string-width label font context)]
    (set-graphics-color graphics color)
    (.setFont graphics font)
    (.drawString graphics label (int (math/round (- (* (+ index 0.5) button-cell-width) (/ width 2))))
                 (- Wayang/DISPLAY_HEIGHT 4))))

(defn- space-for-encoder-button-label
  "Calculate how much room there is to draw a label under an encoder,
  based on how many encoders the label applies to."
  [encoder-count]
  (- (* button-cell-width encoder-count) button-cell-margin))

(def font-for-encoder-button-label
  "The font used when drawing labels under encoders."
  (get-display-font :condensed Font/PLAIN 14))

(def encoder-label-underline-height
  "The height at which to draw the line under an encoder label."
  20.0)

(defn draw-encoder-button-label
  "Draw a label under an encoder at the top of the graphical display."
  [controller index encoder-count text color]
  (let [graphics (create-graphics controller)
        space (space-for-encoder-button-label encoder-count)
        context (.getFontRenderContext graphics)
        label (fit-string text font-for-encoder-button-label context space)
        width (string-width label font-for-encoder-button-label context)]
    (set-graphics-color graphics color)
    (.setFont graphics font-for-encoder-button-label)
    (.drawString graphics label
                 (int (math/round (- (* (+ index (/ encoder-count 2)) button-cell-width) (/ width 2)))) 16)
    (.draw graphics (java.awt.geom.Line2D$Double.
                     (+ (* index button-cell-width) (/ button-cell-margin 2.0)) encoder-label-underline-height
                     (- (* (+ index encoder-count) button-cell-width) (/ button-cell-margin 2.0) 1.0)
                     encoder-label-underline-height))))

(def font-for-cue-variable-values
  "The font used when drawing cue variable values."
  (get-display-font :condensed-light Font/PLAIN 22))

(def font-for-cue-variable-emphasis
  "The font used when drawing cue variable values."
  (get-display-font :condensed Font/PLAIN 22))

(defn draw-cue-variable-value
  "Draw a label under an encoder at the top of the graphical display."
  ([controller index encoder-count text color]
   (draw-cue-variable-value controller index encoder-count text color font-for-cue-variable-values))
  ([controller index encoder-count text color font]
   (let [graphics (create-graphics controller)
         space (space-for-encoder-button-label encoder-count)
         context (.getFontRenderContext graphics)
         label (fit-string text font context space)
         width (string-width label font context)]
     (set-graphics-color graphics color)
     (.setFont graphics font)
     (.drawString graphics label
                  (int (math/round (- (* (+ index (/ encoder-count 2)) button-cell-width) (/ width 2)))) 40))))

(defn draw-attributed-variable-value
  "Draw a label under an encoder at the top of the graphical display,
  with an attributed string so the label can have mixed fonts, colors,
  etc. Assumes the value will fit in the allocated space."
  [controller index encoder-count attributed-string color]
  (let [graphics (create-graphics controller)
        space (space-for-encoder-button-label encoder-count)
        context (.getFontRenderContext graphics)
        iterator (.getIterator attributed-string)
        measurer (java.awt.font.LineBreakMeasurer. iterator context)
        layout (.nextLayout measurer Integer/MAX_VALUE)
        width (.getWidth (.getBounds layout))]
    ;; Establish a default color for characters that don't change it
    (set-graphics-color graphics color)
    (.drawString graphics iterator
                 (int (math/round (- (* (+ index (/ encoder-count 2)) button-cell-width) (/ width 2)))) 40)))

(defn ^:deprecated make-gauge
  "Create a graphical gauge with an indicator that fills a line.
  The default range is from zero to a hundred, and the default size is
  17 characters, or a full display cell."
  [value & {:keys [lowest highest width] :or {lowest 0 highest 100 width 17}}]
  (let [range (- highest lowest)
        scaled (int (* 2 width (/ (- value lowest) range)))
        marker 0
        leader (take (int (/ scaled 2)) (repeat 0))]
    (take width (concat leader [marker] (repeat 0)))))

(defn draw-null-gauge
 "Draw a mostly meaningless gauge simply to indicate that the encoder
 is doing something. Used for beat adjustments, for example, which
 have no reasonable range or location to show."
  [controller index encoder-count color]
  (let [graphics (create-graphics controller)
        x-center (+ (* index button-cell-width) (* encoder-count 0.5 button-cell-width))
        arc (java.awt.geom.Arc2D$Double. (- x-center 20.0) 50.0 40.0 40.0 240.0 -300.0 java.awt.geom.Arc2D/OPEN)]
    (set-graphics-color graphics color)
    (.draw graphics (java.awt.geom.Ellipse2D$Double. (- x-center 20.0) 50.0 40.0 40.0))))

(defn draw-gauge
  "Draw a graphical gauge with an indicator that fills an arc under a
  variable value. The default range is from zero to a hundred, and the
  default color for both the track and active area is dim white."
  [controller index encoder-count value & {:keys [lowest highest track-color active-color]
                                           :or {lowest 0 highest 100
                                                track-color default-track-color active-color track-color}}]
  (let [graphics (create-graphics controller)
        range (- highest lowest)
        fraction (/ (- value lowest)  range)
        x-center (+ (* index button-cell-width) (* encoder-count 0.5 button-cell-width))
        arc (java.awt.geom.Arc2D$Double. (- x-center 20.0) 50.0 40.0 40.0 240.0 -300.0 java.awt.geom.Arc2D/OPEN)]
    (set-graphics-color graphics track-color)
    (.draw graphics arc)
    (.setStroke graphics (java.awt.BasicStroke. 4.0 java.awt.BasicStroke/CAP_ROUND java.awt.BasicStroke/JOIN_ROUND))
    (set-graphics-color graphics active-color)
    (.setAngleExtent arc (* -300.0 fraction))
    (.draw graphics arc)))

(defn draw-pan-gauge
  "Draw a graphical gauge with an indicator that rides around an arc
  under a variable value. The default range is from zero to a hundred,
  and the default color for both the track and active area is dim
  white."
  [controller index encoder-count value & {:keys [lowest highest track-color active-color]
                                           :or {lowest 0 highest 100
                                                track-color default-track-color active-color track-color}}]
  (let [graphics (create-graphics controller)
        range (- highest lowest)
        fraction (/ (- value lowest)  range)
        x-center (+ (* index button-cell-width) (* encoder-count 0.5 button-cell-width))
        arc (java.awt.geom.Arc2D$Double. (- x-center 20.0) 50.0 40.0 40.0 240.0 -300.0 java.awt.geom.Arc2D/OPEN)]
    (set-graphics-color graphics track-color)
    (.draw graphics arc)
    (.setStroke graphics (java.awt.BasicStroke. 6.0 java.awt.BasicStroke/CAP_ROUND java.awt.BasicStroke/JOIN_ROUND))
    (set-graphics-color graphics active-color)
    (.setAngleStart arc (+ 240.0 (* -300.0 fraction)))
    (.setAngleExtent arc 0.0)
    (.draw graphics arc)))

(defn ^:deprecated make-pan-gauge
  "Create a graphical gauge with an indicator that moves along a line.
  The default range is from zero to a hundred, and the default size is
  17 characters, or a full display cell."
  [value & {:keys [lowest highest width] :or {lowest 0 highest 100 width 17}}]
  (let [range (* 1.01 (- highest lowest))
        midpoint (/ (- highest lowest) 2)
        scaled (int (* 2 width (/ (- value lowest) range)))
        filler (repeat 0)
        centered (< (math/abs (- (- value lowest) midpoint)) (/ range 256))
        marker 0
        leader (if (and centered (even? width) (even? scaled))
                 (concat (take (dec (int (/ scaled 2))) filler) [0])
                 (take (int (/ scaled 2)) filler))]
    (take width (concat leader [marker]
                        (when (and centered (even? width) (odd? scaled)) [0])
                        filler))))

(defn- metronome-sync-label
  "Determine the sync type label to display under the BPM section."
  [controller]
  (with-show (:show controller)
    (case (:type (show/sync-status))
      :manual " Manual"
      :midi "  MIDI"
      :dj-link "DJ Link"
      :traktor-beat-phase "Traktor"
      "Unknown")))

(defn- metronome-sync-color
  "Determine the color to light the sync pad under the BPM section."
  [controller]
  (with-show (:show controller)
    (if (= (:type (show/sync-status)) :manual)
      amber-color
      (if (:current (show/sync-status))
        green-color
        red-color))))

(defn- update-mode!
  "Turn a controller mode on or off, identified by the associated
  control button number or keyword."
  [controller button state]
  (let [button (if (keyword? button) (get-in control-buttons [button :control]) button)]
    (swap! (:modes controller) #(if state (conj % button) (disj % button)))))

(defn in-mode?
  "Check whether the controller is in a particular mode, identified by
  a control button number or keyword."
  [controller button]
  (let [button (if (keyword? button) (get-in control-buttons [button :control]) button)]
    (get @(:modes controller) button)))

(def metronome-background
  "The background for the metronome section, to mark it as such."
  (colors/darken (colors/desaturate (colors/create-color :blue) 55) 45))

(def metronome-content
  "The color for content in the metronome section, to mark it as such."
  (colors/desaturate (colors/create-color :aqua) 30))

(def font-for-metronome-values
  "The font used when drawing metronome values."
  (get-display-font :monospace Font/PLAIN 22))

(defn- bpm-adjusting-interface
  "Brighten the section of the BPM that is being adjusted, and draw
  the gauge in a brighter color, or indicate that it is being synced
  and can't be adjusted."
  [controller snapshot]
  (if (= (:type (show/sync-status)) :manual)
    (let [graphics (create-graphics controller)
          bpm (double (:bpm snapshot))
          bpm-string (format "%.1f" bpm)
          label (java.text.AttributedString. bpm-string)]
      (set-graphics-color graphics metronome-background)
      (.fillRect graphics button-cell-width 21 button-cell-width 20)
      (.addAttribute label java.awt.font.TextAttribute/FONT font-for-metronome-values)
      (if (in-mode? controller :shift)
        (.addAttribute label java.awt.font.TextAttribute/FOREGROUND (java.awt.Color/WHITE)
                       0 (- (count bpm-string) 2))
        (.addAttribute label java.awt.font.TextAttribute/FOREGROUND (java.awt.Color/WHITE)
                       (dec (count bpm-string)) (count bpm-string)))
      (draw-attributed-variable-value controller 1 1 label metronome-content)
      (draw-gauge controller 1 1 bpm :lowest controllers/minimum-bpm :highest controllers/maximum-bpm
                  :track-color metronome-content :active-color white-color))

    ;; Display the sync mode in red to explain why we are not adjusting it.
    (let [graphics (create-graphics controller)]
      (set-graphics-color graphics metronome-background)
      (.fillRect graphics button-cell-width (- Wayang/DISPLAY_HEIGHT 20) button-cell-width 20)
      (draw-bottom-button-label controller 1 (metronome-sync-label controller) red-color))))

(defn sign-velocity
  "Convert a midi velocity to its signed equivalent, to translate
  encoder rotations, which are twos-complement seven bit numbers."
  [val]
   (if (>= val 64)
     (- val 128)
     val))

(defn- adjust-bpm-from-encoder
  "Adjust the current BPM based on how the encoder was twisted, unless
  the metronome is synced."
  [controller message]
  (with-show (:show controller)
    (when (= (:type (show/sync-status)) :manual)
      (let [scale (if (in-mode? controller :shift) 1 10)
            delta (/ (sign-velocity (:velocity message)) scale)
            bpm (rhythm/metro-bpm (:metronome (:show controller)))]
        (rhythm/metro-bpm (:metronome (:show controller)) (min controllers/maximum-bpm
                                                               (max controllers/minimum-bpm (+ bpm delta))))))))

(defn- encoder-above-bpm-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the BPM display when metronome was already active,
  since it is easy to grab that one rather than the actual BPM
  encoder, being right above the display."
  [controller]
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{72})
                 (captured-notes [this] #{1 9})
                 (adjust-interface [this snapshot]
                   (bpm-adjusting-interface controller snapshot)
                   true)
                 (handle-control-change [this message]
                   (adjust-bpm-from-encoder controller message))
                 (handle-note-on [this  message]
                   ;; Suppress the actual BPM encoder while we are active.
                   true)
                 (handle-note-off [this message]
                   (when (= (:note message) 1)
                     ;; They released us, end the overlay.
                     :done))
                 (handle-aftertouch [this message])
                 (handle-pitch-bend [this message]))))

(defn- beat-adjusting-interface
  "Brighten the section of the beat which is being adjusted."
  [controller snapshot]
  (let [graphics (create-graphics controller)
        marker (rhythm/snapshot-marker snapshot)
        label (java.text.AttributedString. marker)
        first-dot (.indexOf marker ".")
        second-dot (.indexOf marker "." (inc first-dot))]
      (set-graphics-color graphics metronome-background)
      (.fillRect graphics 0 21 button-cell-width 20)
      (.addAttribute label java.awt.font.TextAttribute/FONT font-for-metronome-values)
      (if (in-mode? controller :shift)
        (.addAttribute label java.awt.font.TextAttribute/FOREGROUND (java.awt.Color/WHITE)
                       (inc first-dot) second-dot)
        (.addAttribute label java.awt.font.TextAttribute/FOREGROUND (java.awt.Color/WHITE)
                       (inc second-dot) (count marker)))
      (draw-attributed-variable-value controller 0 1 label metronome-content)
      (draw-null-gauge controller 0 1 white-color))
  true)

(defn- adjust-beat-from-encoder
  "Adjust the current beat based on how the encoder was twisted."
  [controller message]
  (let [delta (sign-velocity (:velocity message))
        metronome (:metronome (:show controller))
        units (if (in-mode? controller :shift)
                ;; User is adjusting the current bar
                (rhythm/metro-tock metronome)
                ;; User is adjusting the current beat
                (rhythm/metro-tick metronome))
        ms-delta (- (* delta units))]
    (rhythm/metro-adjust metronome ms-delta)))

(defn- encoder-above-beat-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the beat display when metronome was already active,
  since it is easy to grab that one rather than the actual beat
  encoder, being right above the display."
  [controller]
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{71})
                 (captured-notes [this] #{0 10})
                 (adjust-interface [this snapshot]
                   (beat-adjusting-interface controller snapshot))
                 (handle-control-change [this message]
                   (adjust-beat-from-encoder controller message))
                 (handle-note-on [this message]
                   ;; Suppress the actual beat encoder while we are active.
                   true)
                 (handle-note-off [this message]
                   (when (zero? (:note message))
                     ;; They released us, end the overlay.
                     :done))
                 (handle-aftertouch [this message])
                 (handle-pitch-bend [this message]))))

(defn- enter-metronome-showing
  "Activate the persistent metronome display, with sync and reset pads
  illuminated."
  [controller]
  (swap! (:metronome-mode controller) assoc :showing true)
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{3 9 20 21})
                 (captured-notes [this] #{0 1})
                 (adjust-interface [this _]
                   ;; Make the metronome button bright, since its information is active
                   (swap! (:next-text-buttons controller)
                          assoc (:metronome control-buttons)
                          white-color)

                   ;; Add the labels for reset and sync, and light the pads
                   (draw-bottom-button-label controller 0 "Reset" red-color)
                   (draw-bottom-button-label controller 1 (metronome-sync-label controller)
                                             (metronome-sync-color controller))
                   (swap! (:next-top-pads controller) assoc 0 dim-red-color)
                   (swap! (:next-top-pads controller) assoc 1 (dim (metronome-sync-color controller))))
                 (handle-control-change [this message]
                   (case (:note message)
                     3 ; Tap tempo button
                     (when (pos? (:velocity message))
                       ((:tempo-tap-handler controller))
                       true)

                     9 ; Metronome button
                     (when (pos? (:velocity message))
                       (swap! (:metronome-mode controller) dissoc :showing)
                       ;; Exit the overlay
                       :done)

                     20 ; Reset pad
                     (when (pos? (:velocity message))
                       (rhythm/metro-phrase-start (:metronome (:show controller)) 1)
                       (controllers/add-control-held-feedback-overlay (:overlays controller) 20
                                                                      (fn [_] (swap! (:next-top-pads controller)
                                                                                     assoc 0 red-color)))
                       true)
                     21 ; Sync pad
                     (when (pos? (:velocity message))
                       ;; TODO: Actually implement a new overlay
                       (controllers/add-control-held-feedback-overlay
                        (:overlays controller) 21 (fn [_] (swap! (:next-top-pads controller)
                                                                 assoc 1 (metronome-sync-color controller))))
                       true)))
                 (handle-note-on [this message]
                   ;; Whoops, user grabbed encoder closest to beat or BPM display
                   (case (:note message)
                     0 (encoder-above-beat-touched controller)
                     1 (encoder-above-bpm-touched controller))
                   true)
                 (handle-note-off [this message]
                   false)
                 (handle-aftertouch [this message])
                 (handle-pitch-bend [this message]))))

(defn- new-beat?
  "Returns true if the metronome is reporting a different marker
  position than the last time this function was called."
  [controller marker]
  (when (not= marker @(:last-marker controller))
    (reset! (:last-marker controller) marker)))

(defn- update-metronome-section
  "Updates the sections of the interface related to metronome
  control."
  [controller snapshot]
  (let [marker (rhythm/snapshot-marker snapshot)
        metronome-button (:metronome control-buttons)
        tap-tempo-button (:tap-tempo control-buttons)
        metronome-mode @(:metronome-mode controller)]
    ;; Is the first cell reserved for metronome information?
    (if (seq metronome-mode)
      (let [graphics (create-graphics controller)
            bpm (:bpm snapshot)
            bpm-label (java.text.AttributedString. (format "%.1f" (double bpm)))]

        ;; Draw the background that makes the metronome section distinctive.
        (set-graphics-color graphics metronome-background)
        (.fillRect graphics 0 0 (* 2 button-cell-width) Wayang/DISPLAY_HEIGHT)

        ;; Draw the beat and BPM information
        (set-graphics-color graphics metronome-content)
        (draw-encoder-button-label controller 0 1 "Beat" metronome-content)
        (draw-cue-variable-value controller 0 1 marker metronome-content font-for-metronome-values)
        (draw-encoder-button-label controller 1 1 "BPM" metronome-content)
        (.addAttribute bpm-label java.awt.font.TextAttribute/FONT font-for-metronome-values)
        (draw-attributed-variable-value controller 1 1 bpm-label metronome-content)
        (draw-gauge controller 1 1 bpm :lowest controllers/minimum-bpm :highest controllers/maximum-bpm
                    :track-color metronome-content)

        ;; Make the metronome button bright, since some overlay is present
        (swap! (:next-text-buttons controller) assoc metronome-button white-color))

      ;; The metronome section is not active, so make its button dim
      (swap! (:next-text-buttons controller) assoc metronome-button dim-white-color))

    ;; Regardless, flash the tap tempo button on beats
    (swap! (:next-text-buttons controller)
           assoc tap-tempo-button
           (if (or (new-beat? controller marker) (< (rhythm/snapshot-beat-phase snapshot) 0.15))
             white-color dim-white-color))))

(defn- render-cue-grid
  "Figure out how the cue grid pads should be illuminated, based on the
  currently active cues and our current point in musical time."
  [controller snapshot]
  (let [[origin-x origin-y] @(:origin controller)
        active-keys (show/active-effect-keys (:show controller))]
    (doseq [x (range 8)
            y (range 8)]
      (let [[cue active] (show/find-cue-grid-active-effect (:show controller) (+ x origin-x) (+ y origin-y))
            ending (and active (:ending active))
            base-color (when cue (cues/current-cue-color cue active (:show controller) snapshot))
            l-boost (when base-color (if (zero? (colors/saturation base-color)) 20.0 0.0))
            color (when base-color
                    (colors/create-color
                     :h (colors/hue base-color)
                     :s (colors/saturation base-color)
                     ;; Figure the brightness. Active, non-ending cues are full brightness;
                     ;; when ending, they blink between middle and low. If they are not active,
                     ;; they are at middle brightness unless there is another active effect with
                     ;; the same keyword, in which case they are dim.
                     :l (+ (if active
                             (if ending
                               (if (> (rhythm/snapshot-beat-phase snapshot) 0.4) 6 22)
                               55)
                             (if (or (active-keys (:key cue))
                                     (seq (clojure.set/intersection active-keys (set (:end-keys cue))))) 6 22))
                           l-boost)))]
        (swap! (:next-grid-pads controller) assoc (+ x (* y 8)) (or color off-color))))))

(defn- update-cue-grid
  "See if any of the cue grid button states have changed, and send any
  required updates."
  [controller]
  (doseq [x (range 8)
          y (range 8)]
    (let [index (+ x (* y 8))
          color (get @(:next-grid-pads controller) index)]
      (when-not (= color (get @(:last-grid-pads controller) index))
        (set-pad-color controller x y color)
        (swap! (:last-grid-pads controller) assoc index color)))))

(defn- cue-vars-for-encoders
  "Find the correct cue variables that correspond to each of the two
  encoders within a cue's display region, given the cue's variable
  list and the offset by which the user has shifted the cue
  variables."
  [cue-vars var-offset]
  (case (count cue-vars)
    0 nil ; No variables to adjust
    1 (vec (repeat 2 (first cue-vars)))
    (vec (take 2 (drop var-offset (apply concat (repeat cue-vars)))))))

(defn- best-cue-variable-name
  "Picks the best version of a cue variable name to fit under the
  specified number of encoders."
  [controller v encoder-count]
  (let [graphics (create-graphics controller)
        space (space-for-encoder-button-label encoder-count)
        font font-for-encoder-button-label
        context (.getFontRenderContext graphics)
        longer (or (:name v) (name (:key v)))
        shorter (or (:short-name v) longer)]
    (if (<= (string-width longer font context) space) longer shorter)))

(defn draw-cue-variable-names
  "Draw the names of adjustable variables under the appropriate
  encoders for a cue."
  [controller x cue effect-id]
  (let [cue-vars (cue-vars-for-encoders (:variables cue) (get @(:cue-var-offsets controller) effect-id 0))]
    (when (seq cue-vars)
      (if (= (count (:variables cue)) 1)
        (draw-encoder-button-label controller (* x 2) 2
                                   (best-cue-variable-name controller (first cue-vars) 1) white-color)
        (do
          (draw-encoder-button-label controller (* x 2) 1
                                     (best-cue-variable-name controller (first cue-vars) 1) white-color)
          (draw-encoder-button-label controller (inc (* x 2)) 1
                                     (best-cue-variable-name controller (second cue-vars) 1) white-color))))))

(defn- format-cue-variable-value
  "Translates a cue variable to a string format that will look good
  and be meaningful on the display."
  [controller cue v effect-id]
  (let [val (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id)
        formatted (if (some? val)
                    (cond
                      (= (:type v) :integer)
                      (int val)

                      (or (= (type val) :com.evocomputing.colors/color) (= (:type v) :color))
                      (colors/rgb-hexstr val)

                      (or (= (type val) Boolean) (= (:type v) :boolean))
                      (if val "Yes" "No")

                      ;; If we don't know what else to do, at least turn ratios to doubles
                      :else
                      (double val))

                    ;; We got no value, display an ellipsis
                    "…")]
    (str formatted)))

(defn- draw-cue-variable-values
  "Displays the current values of the adjustable variables currently
  assigned to the encoders over an active cue."
  [controller x cue effect-id]
  (let [cue-vars (cue-vars-for-encoders (:variables cue) (get @(:cue-var-offsets controller) effect-id 0))]
    (when (seq cue-vars)
      (if (= (count (:variables cue)) 1)
        (draw-cue-variable-value controller (* x 2) 2
                                 (format-cue-variable-value controller cue (first cue-vars) effect-id) white-color)
        (do
          (draw-cue-variable-value controller (* x 2) 1
                                   (format-cue-variable-value controller cue (first cue-vars) effect-id) white-color)
          (draw-cue-variable-value controller (inc (* x 2)) 1
                                   (format-cue-variable-value controller cue (second cue-vars) effect-id)
                                   white-color))))))

(defn draw-cue-variable-gauge
  "Draw an appropriate gauge for a cue variable given its type and
  value."
  [controller index encoder-count cue cue-var effect-id]
  (let [cur-val (cues/get-cue-variable cue cue-var :show (:show controller) :when-id effect-id)]
    (cond
      (or (number? cur-val) (#{:integer :double} (:type cue-var :double)))
      (if (:centered cue-var)
        (draw-pan-gauge controller index encoder-count cur-val :lowest (min cur-val (:min cue-var))
                    :highest (max cur-val (:max cue-var)))
        (draw-gauge controller index encoder-count cur-val :lowest (min cur-val (:min cue-var))
                    :highest (max cur-val (:max cue-var))))

      (or (= (type cur-val) :com.evocomputing.colors/color) (= (:type cue-var) :color))
      nil ;; TODO: Implement based on this:
      #_(controllers/add-overlay (:overlays controller)
                               (build-color-adjustment-overlay controller note cue cue-var effect info))

      (or (= (type cur-val) Boolean) (= (:type cue-var) :boolean))
      nil ;; TODO: Implement based on this:
      #_(controllers/add-overlay (:overlays controller)
                               (build-boolean-adjustment-overlay controller note cue cue-var effect info)))))

(defn draw-cue-variable-gauges
  "Displays the appropriate style of adjustment gauge for variables
  currently assigned to the encoders over an active cue."
  [controller x cue effect-id]
  (let [cue-vars (cue-vars-for-encoders (:variables cue) (get @(:cue-var-offsets controller) effect-id 0))]
    (when (seq cue-vars)
      (if (= (count (:variables cue)) 1)
        (draw-cue-variable-gauge controller (* x 2) 2 cue (first cue-vars) effect-id)
        (do
          (draw-cue-variable-gauge controller (* x 2) 1 cue (first cue-vars) effect-id)
          (draw-cue-variable-gauge controller (inc (* x 2)) 1 cue (second cue-vars) effect-id))))))

(defn- room-for-effects
  "Determine how many display cells are available for displaying
  effect information."
  [controller]
  (if (seq @(:metronome-mode controller)) 3 4))

(defn- find-effect-offset-range
  "Determine the valid offset range for scrolling through the effect
  list, based on how many effects are running, and how many currently
  fit on the display. If we are currently scrolled beyond the sensible
  range, correct that. Returns a tuple of the current offset, the
  maximum sensible offset, and the number of effects displayed."
  [controller]
  (let [room (room-for-effects controller)
        size (count (:effects @(:active-effects (:show controller))))
        max-offset (max 0 (- size room))
        ;; If we are offset more than now makes sense, fix that.
        offset (swap! (:effect-offset controller) min max-offset)]
    [offset max-offset room]))

(def no-effects-active-color
  "The color to use for the text explaining that no effects are
  active."
  (let [color (colors/desaturate (colors/create-color :blue) 70)]
    (java.awt.Color. (colors/red color) (colors/green color) (colors/blue color))))

(defn- update-effect-list
  "Display information about the four most recently activated
  effects (or three, if the metronome is taking up a slot)."
  [controller snapshot]

  ;; First clean up any cue variable scroll offsets for effects that have ended
  (swap! (:cue-var-offsets controller) select-keys (map :id (:meta @(:active-effects (:show controller)))))

  ;; Then adjust our scroll offset if it no longer makes sense
  (find-effect-offset-range controller)

  (let [room        (room-for-effects controller)
        first-cell  (- 4 room)
        fx-info     @(:active-effects (:show controller))
        fx          (:effects fx-info)
        fx-meta     (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        graphics    (create-graphics controller)]
    (if (seq fx)
      (do (loop [fx      (take room (drop num-skipped fx))
                 fx-meta (take room (drop num-skipped fx-meta))
                 x       first-cell]
            (let [effect      (:effect (first fx))
                  info        (first fx-meta)
                  ending      ((:key info) (:ending fx-info))
                  cue         (:cue info)
                  base-color       (if cue
                                     (cues/current-cue-color cue effect (:show controller) snapshot)
                                     white-color)
                  color (when base-color
                          (if (and ending (> (rhythm/snapshot-beat-phase snapshot) 0.4))
                            (colors/darken base-color 40)
                            base-color))
                  width       (* 2 button-cell-width)
                  left        (* x width)
                  scroll-vars (> (count (:variables cue)) 2)
                  cur-vals    (when cue (cues/snapshot-cue-variables cue (:id info) :show (:show controller)))
                  saved-vals  (controllers/cue-vars-saved-at (:cue-grid (:show controller)) (:x info) (:y info))
                  save-action (when (seq cur-vals)
                                (if (seq saved-vals)
                                  (if (= cur-vals saved-vals) :clear :save)
                                  (when (not= cur-vals (:starting-vars info))
                                    :save)))]
              (draw-cue-variable-names controller x cue (:id info))
              (draw-cue-variable-values controller x cue (:id info))
              (draw-cue-variable-gauges controller x cue (:id info))
              (set-graphics-color graphics color)
              (.fillRect graphics (+ left (/ button-cell-margin 2.0)) (- Wayang/DISPLAY_HEIGHT 38)
                         (- width button-cell-margin) 20)
              (let [label-font (get-display-font :condensed Font/PLAIN 16)
                    context (.getFontRenderContext graphics)
                    label (fit-string (or (:name cue) (:name (first fx))) label-font context
                                      (- width button-cell-margin 2))
                    label-width (string-width label label-font context)]
                (set-graphics-color graphics off-color)
                (.setFont graphics label-font)
                (.drawString graphics label (int (math/round (- (+ left button-cell-width) (/ label-width 2))))
                             (- Wayang/DISPLAY_HEIGHT 23)))
              (if (in-mode? controller :record)
                (when save-action
                  (let [save-color (case save-action
                                     :save  green-color
                                     :clear amber-color)]
                    (swap! (:next-top-pads controller) assoc (* 2 x) (dim save-color))
                    (draw-bottom-button-label controller (* 2 x) (case save-action
                                                                   :save  "Save"
                                                                   :clear "Clear") save-color)))
                (do
                  (swap! (:next-top-pads controller) assoc (* 2 x) dim-red-color)
                  (draw-bottom-button-label controller (* 2 x) (if ending "Ending" "End") red-color)))
              (when scroll-vars
                (swap! (:next-top-pads controller) assoc (inc (* 2 x)) dim-white-color)
                (draw-bottom-button-label controller (inc (* 2 x)) "Next Vars >" white-color))
              (when (seq (rest fx))
                (recur (rest fx) (rest fx-meta) (inc x)))))

          ;; Draw indicators if there are effects hidden from view in either direction
          (when (pos? num-skipped)
            (set-graphics-color graphics white-color)
            (.draw graphics (java.awt.geom.Line2D$Double.
                             (+ (* first-cell 2 button-cell-width) 7) (- Wayang/DISPLAY_HEIGHT 16)
                             (* first-cell 2 button-cell-width) (- Wayang/DISPLAY_HEIGHT 9)))
            (.draw graphics (java.awt.geom.Line2D$Double.
                             (* first-cell 2 button-cell-width) (- Wayang/DISPLAY_HEIGHT 9)
                             (+ (* first-cell 2 button-cell-width) 7) (- Wayang/DISPLAY_HEIGHT 2))))
          (when (pos? @(:effect-offset controller))
            (set-graphics-color graphics white-color)
            (.draw graphics (java.awt.geom.Line2D$Double.
                             (- Wayang/DISPLAY_WIDTH 8) (- Wayang/DISPLAY_HEIGHT 16)
                             (dec Wayang/DISPLAY_WIDTH) (- Wayang/DISPLAY_HEIGHT 9)))
            (.draw graphics (java.awt.geom.Line2D$Double.
                             (dec Wayang/DISPLAY_WIDTH) (- Wayang/DISPLAY_HEIGHT 9)
                             (- Wayang/DISPLAY_WIDTH 8) (- Wayang/DISPLAY_HEIGHT 2)))))
      (do
        (let [font  (get-display-font :condensed-light Font/ITALIC 36)
              text  "No effects are active."
              width (calculate-text-width graphics font text)]
          (.setFont graphics font)
          (.setPaint graphics no-effects-active-color)
          (.drawString graphics text (int (math/round (- (/ Wayang/DISPLAY_WIDTH 2) (/ width 2))))
                       (/ Wayang/DISPLAY_HEIGHT 2)))))))

(declare enter-stop-mode)

(defn- update-scroll-arrows
  "Activate the arrow buttons for directions in which scrolling is
  possible."
  [controller]
  ;; The page left/right buttons scroll through the effect list
  (let [[offset max-offset] (find-effect-offset-range controller)]
    ;; If there is an offset, user can scroll to the right
    (when (pos? offset)
      (swap! (:next-text-buttons controller) assoc (:page-right control-buttons) dim-white-color))
    ;; Is there room to scroll to the left?
    (when (< offset max-offset)
      (swap! (:next-text-buttons controller) assoc (:page-left control-buttons) dim-white-color)))

  ;; The arrow keys scroll through the cue grid
  (let [[origin-x origin-y] @(:origin controller)]
    (when (pos? origin-x)
      (swap! (:next-text-buttons controller) assoc (:left-arrow control-buttons) dim-white-color))
    (when (pos? origin-y)
      (swap! (:next-text-buttons controller) assoc (:down-arrow control-buttons) dim-white-color))
    (when (> (- (controllers/grid-width (:cue-grid (:show controller))) origin-x) 7)
      (swap! (:next-text-buttons controller)
             assoc (:right-arrow control-buttons) dim-white-color))
    (when (> (- (controllers/grid-height (:cue-grid (:show controller))) origin-y) 7)
      (swap! (:next-text-buttons controller)
             assoc (:up-arrow control-buttons) dim-white-color))))

(defn- update-mode-buttons
  "Illuminate the buttons which activate modes while they are held
  down. Make them dim when not held, and bright when held."
  [controller mode-buttons]
  (doseq [button-key mode-buttons]
    (let [button (button-key control-buttons)]
      (swap! (:next-text-buttons controller)
             assoc button (if (in-mode? controller button-key)
                            (or (:bright-color button) white-color)
                            (or (:dim-color button) dim-white-color))))))

(def empty-top-pads
  "A representation of the state when all eight of the top pads are
  off."
  (vec (repeat 8 off-color)))

(def empty-grid-pads
  "A representation of the state when all 64 of the grid pads are
  off."
  (vec (repeat 64 off-color)))

(defn- update-interface
  "Determine the desired current state of the interface, and send any
  changes needed to get it to that state."
  [controller]
  (try
    ;; Assume we are starting out with a blank interface.
    (clear-display-buffer controller)
    (reset! (:next-text-buttons controller) {})
    (reset! (:next-top-pads controller) empty-top-pads)
    (reset! (:next-touch-strip controller) [0 touch-strip-mode-level])

    (let [snapshot (rhythm/metro-snapshot (get-in controller [:show :metronome]))]
      (update-effect-list controller snapshot)
      (update-metronome-section controller snapshot)

      ;; If the show has stopped without us noticing, enter stop mode
      (with-show (:show controller)
        (when-not (or (show/running?) (in-mode? controller :stop))
          (enter-stop-mode controller :already-stopped true)))

      (update-mode-buttons controller [:shift :record])
      (render-cue-grid controller snapshot)
      (update-scroll-arrows controller)

      ;; Make the User button bright, since we live in User mode
      (swap! (:next-text-buttons controller) assoc (:user-mode control-buttons) white-color)

      ;; Make the play button red, indicating it will stop the show
      (swap! (:next-text-buttons controller) assoc (:stop control-buttons) dim-red-color)

      ;; Add any contributions from interface overlays, removing them
      ;; if they report being finished.
      (controllers/run-overlays (:overlays controller) snapshot))

    (update-cue-grid controller)
    (Wayang/sendFrame)
    (update-top-pads controller)
    (update-text-buttons controller)
    (update-touch-strip controller)

    (catch Throwable t
      (timbre/warn t "Problem updating Ableton Push Interface"))))

(declare clear-interface)

(defn- welcome-frame
  "Render a frame of the welcome animation, or if it is done, start
  the main interface update thread, and terminate the task running the
  animation."
  [controller counter task]
  (try
    (cond
      (< @counter 8)
      (doseq [y (range 0 (inc @counter))]
        (let [color (colors/create-color
                     :h 0 :s 0 :l (max 10 (- 75 (/ (* 50 (- @counter y)) 6))))]
          (set-pad-color controller 3 y color)
          (set-pad-color controller 4 y color)))

      (< @counter 12)
      (doseq [x (range 0 (- @counter 7))
              y (range 0 8)]
        (let [color (colors/create-color
                     :h 340 :s 100 :l (- 75 (* (- @counter 8 x) 20)))]
          (set-pad-color controller (- 3 x) y color)
          (set-pad-color controller (+ 4 x) y color)))

      (< @counter 15)
      (doseq [y (range 0 8)]
        (let [color (colors/create-color
                     :h (* 13 (- @counter 11)) :s 100 :l 50)]
          (set-pad-color controller (- @counter 7) y color)
          (set-pad-color controller (- 14 @counter) y color)))

      (= @counter 15)
      (do
        (show-labels controller white-color)
        (Wayang/sendFrame))

      (= @counter 16)
      (doseq [x (range 0 8)]
        (set-top-pad-color controller x amber-color))

      (= @counter 17)
      (doseq [x (range 0 8)]
        (set-encoder-pad-color controller x amber-color)
        (set-top-pad-color controller x red-color))

      (< @counter 26)
      (doseq [x (range 0 8)]
        (let [lightness-index (if (> x 3) (- 7 x) x)
              lightness ([10 30 50 70] lightness-index)
              color (colors/create-color
                     :h (+ 60 (* 40 (- @counter 18))) :s 100 :l lightness)]
          (set-pad-color controller x (- 25 @counter) color)))

      (= @counter 26)
      (do
        (show-labels controller dim-white-color)
        (doseq [x (range 0 8)]
          (set-top-pad-color controller x off-color)))

      (= @counter 27)
      (doseq [x (range 0 8)]
        (set-encoder-pad-color controller x off-color))

      (< @counter 36)
      (doseq [x (range 0 8)]
        (set-pad-color controller x (- 35 @counter) off-color))

      :else
      (do
        (clear-interface controller)
        (amidi/add-device-mapping (:port-in controller) @(:midi-handler controller))
        (enter-metronome-showing controller)
        (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                #(update-interface controller)
                                                controllers/pool
                                                :initial-delay 10
                                                :desc "Push interface update"))
        (at-at/kill @task)))
    (catch Throwable t
      (timbre/warn t "Animation frame failed")))

  (swap! counter inc))

(defn- show-welcome-text
  "Draw the welcome message on the display."
  [controller]
  (clear-display-buffer controller)
  (let [welcome (str "Welcome to " (version/title))
        version (str "version " (version/tag))
        graphics (create-graphics controller)
        context (.getFontRenderContext graphics)]
    (.setFont graphics (get-display-font :roboto-medium Font/PLAIN 42))
    (.setPaint graphics (java.awt.Color/WHITE))
    (let [x (int (- (/ Wayang/DISPLAY_WIDTH 2) (/ (string-width welcome (.getFont graphics) context) 2)))]
      (.drawString graphics welcome x 80))
    (.setFont graphics (get-display-font :condensed-light Font/ITALIC 20))
    (.setPaint graphics (java.awt.Color/BLUE))
    (let [x (int (- (/ Wayang/DISPLAY_WIDTH 2) (/ (string-width version (.getFont graphics) context) 2)))]
      (.drawString graphics version x 110))
    (let [afterglow-logo (javax.imageio.ImageIO/read
                          (.getResourceAsStream Effect "/public/img/Afterglow-logo-padded-left.png"))
          afterglow-scale (/ Wayang/DISPLAY_HEIGHT (.getHeight afterglow-logo))
          deep-logo (javax.imageio.ImageIO/read
                     (.getResourceAsStream Effect "/public/img/Deep-Symmetry-logo.png"))
          deep-scale 0.4
          deep-width (math/round (* (.getWidth deep-logo) deep-scale))
          deep-height (math/round (* (.getHeight deep-logo) deep-scale))]
      (.drawImage graphics afterglow-logo 20 0
                  (math/round (* (.getWidth afterglow-logo) afterglow-scale)) Wayang/DISPLAY_HEIGHT nil)
      (.drawImage graphics deep-logo
                  (- Wayang/DISPLAY_WIDTH deep-width 20) (- (/ Wayang/DISPLAY_HEIGHT 2) (/ deep-height 2))
                  deep-width deep-height nil)))
  (Wayang/sendFrame))

(defn- welcome-animation
  "Provide a fun animation to make it clear the Push is online."
  [controller]
  (show-welcome-text controller)
  (let [counter (atom 0)
        task (atom nil)]
    (reset! task (at-at/every 50 #(welcome-frame controller counter task)
                              controllers/pool))))

(defn clear-interface
  "Clears the graphical display and all illuminated buttons and pads."
  [controller]
  (clear-display controller)
  (doseq [x (range 8)]
    (set-top-pad-color controller x off-color)
    (set-encoder-pad-color controller x off-color)
    (doseq [y (range 8)]
      (set-pad-color controller x y off-color)))
  (reset! (:last-top-pads controller) empty-top-pads)
  (reset! (:last-grid-pads controller) empty-grid-pads)
  (doseq [[_ button] control-buttons]
    (set-button-color controller button off-color))
  (reset! (:last-text-buttons controller) {})
  (set-touch-strip-mode controller touch-strip-mode-default)
  (reset! (:last-touch-strip controller) nil))

(defn- calculate-touch-strip-value
  "Display the value of a variable being adjusted in the touch strip."
  [controller cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id) 0)
        low (min value (:min v))  ; In case user set "out of bounds".
        high (max value (:max v))
        full-range (- high low)]
    (reset! (:next-touch-strip controller) [(math/round (* 16383 (/ (- value low) full-range)))
                                            (if (:centered v)
                                              touch-strip-mode-pan
                                              touch-strip-mode-level)])))

(def master-background
  "The background for the grand master section, to mark it as such."
  (colors/darken (colors/desaturate (colors/create-color :yellow) 55) 45))

(def master-content
  "The color for content in the metronome section, to mark it as such."
  (colors/lighten (colors/create-color :yellow) 20))

(defn- master-encoder-touched
  "Add a user interface overlay to give feedback when turning the
  master encoder."
  [controller]
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{79})
                             (captured-notes [this] #{8 7})
                             (adjust-interface [this _]
                               (let [level (master-get-level (get-in controller [:show :grand-master]))
                                     graphics (create-graphics controller)]
                                 (set-graphics-color graphics master-background)

                                 ;; Draw the background that makes the master section distinctive.
                                 (set-graphics-color graphics master-background)
                                 (.fillRect graphics (- Wayang/DISPLAY_WIDTH button-cell-width) 0
                                            button-cell-width 100)

                                 ;; Draw the label, value, and gauge
                                 (set-graphics-color graphics master-content)
                                 (draw-encoder-button-label controller 7 1 "Grand Master" master-content)
                                 (draw-cue-variable-value controller 7 1
                                                          (format "%5.1f" level) master-content)
                                 (draw-gauge controller 7 1 level :track-color dim-amber-color
                                             :active-color master-content)
                                 (reset! (:next-touch-strip controller) [(math/round (* 16383 (/ level 100)))
                                                                         touch-strip-mode-level]))
                               true)
                             (handle-control-change [this message]
                               ;; Adjust the BPM based on how the encoder was twisted
                               (let [delta (/ (sign-velocity (:velocity message)) 2)
                                     level (master-get-level (get-in controller [:show :grand-master]))]
                                 (master-set-level (get-in controller [:show :grand-master]) (+ level delta))
                                 true))
                             (handle-note-on [this message]
                               true) ; Suppress activation of the encoder above our overlay
                             (handle-note-off [this message]
                               ;; Exit the overlay if it was our own encoder being released
                               (when (= (:note message) 8) :done))
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]
                               (master-set-level (get-in controller [:show :grand-master])
                                                 (* 100.0 (/ (+ (* (:data2 message) 128) (:data1 message)) 16383)))))))

(defn- bpm-encoder-touched
  "Add a user interface overlay to give feedback when turning the BPM
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-bpm :true)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{15})
                             (captured-notes [this] #{9 1})
                             (adjust-interface [this snapshot]
                               (bpm-adjusting-interface controller snapshot)
                               true)
                             (handle-control-change [this message]
                               (adjust-bpm-from-encoder controller message))
                             (handle-note-on [this message]
                               ;; Suppress the extra encoder above the BPM display.
                               ;; We can't get a note on for the BPM encoder, because
                               ;; that was the event that created this overlay.
                               true)
                             (handle-note-off [this message]
                               (when (= (:note message) 9)
                                 ;; They released us, end the overlay
                                 (swap! (:metronome-mode controller) dissoc :adjusting-bpm)
                                 :done))
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]))))

(defn- beat-encoder-touched
  "Add a user interface overlay to give feedback when turning the beat
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-beat :true)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{14})
                             (captured-notes [this] #{10 0})
                             (adjust-interface [this snapshot]
                               (beat-adjusting-interface controller snapshot))
                             (handle-control-change [this message]
                               (adjust-beat-from-encoder controller message))
                             (handle-note-on [this message]
                               ;; Suppress the extra encoder above the beat display.
                               ;; We can't get a note on for the beat encoder, because
                               ;; that was the event that created this overlay.
                               true)
                             (handle-note-off [this message]
                               (when (= (:note message) 10)
                                 ;; They released us, exit the overlay
                                 (swap! (:metronome-mode controller) dissoc :adjusting-beat)
                                 :done))
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]))))

(defn- leave-user-mode
  "The user has asked to exit user mode, so suspend our display
  updates, and prepare to restore our state when user mode is pressed
  again."
  [controller]
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))
  (clear-interface controller)
  (restore-led-palettes controller)

  ;; In case Live isn't running, leave the User Mode button dimly lit, to help the user return.
  (set-button-color controller (:user-mode control-buttons) dim-white-color)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{59})
                             (captured-notes [this] #{})
                             (adjust-interface [this _]
                               true)
                             (handle-control-change [this message]
                               (when (pos? (:velocity message))
                                 ;; We are returning to user mode, restore display
                                 (clear-interface controller)
                                 (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                                         #(update-interface controller)
                                                                         controllers/pool
                                                                         :initial-delay 250
                                                                         :desc "Push interface update"))
                                 :done))
                             (handle-note-on [this message])
                             (handle-note-off [this message])
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]))))

(def stop-indicator
  "The overlay drawn on top of the effects when the show is stopped."
  (javax.imageio.ImageIO/read (.getResourceAsStream Effect "/public/img/Push-2-Stopped.png")))

(defn- enter-stop-mode
  "The user has asked to stop the show. Suspend its update task
  and black it out until the stop button is pressed again."
  [controller & {:keys [already-stopped]}]

  (update-mode! controller :stop true)
  (when-not already-stopped
    (with-show (:show controller)
      (show/stop!)
      (Thread/sleep (:refresh-interval (:show controller)))
      (show/blackout-show)))

  (let [start-counter (atom 8)
        end-counter (atom nil)
        graphics (create-graphics controller)
        saved-composite (.getComposite graphics)]
    (controllers/add-overlay (:overlays controller)
                             (reify controllers/IOverlay
                               (captured-controls [this] #{85})
                               (captured-notes [this] #{})
                               (adjust-interface [this _]
                                 (if @end-counter
                                   ;; Draw an ending animation frame, returning false once finished to end the overlay
                                   (do
                                     (.setComposite graphics (AlphaComposite/getInstance AlphaComposite/SRC_OVER
                                                                                         (/ @end-counter 8.0)))
                                     (.drawImage graphics stop-indicator 400 0 nil)
                                     (.setComposite graphics saved-composite)
                                     (pos? (swap! end-counter dec)))
                                   (do
                                     (if (pos? @start-counter)
                                       ;; Draw a starting animation frame rather than the normal view
                                       (let [x (quot Wayang/DISPLAY_WIDTH 2)
                                             y (quot Wayang/DISPLAY_HEIGHT 2)
                                             scale (/ (- 9 @start-counter) 8)
                                             side (math/round (* scale Wayang/DISPLAY_HEIGHT))
                                             offset (quot side 2)]
                                         (.setComposite graphics
                                                        (AlphaComposite/getInstance AlphaComposite/SRC_OVER
                                                                                    (/ (- 9 @start-counter) 8.0)))
                                         (.drawImage graphics stop-indicator (- x offset) (- y offset) side side nil)
                                         (.setComposite graphics saved-composite)
                                         (swap! start-counter dec))
                                       ;; Draw the normal view
                                       (.drawImage graphics stop-indicator 400 0 nil))

                                     (when (in-mode? controller :stop)
                                       ;; We seem to still be in stop mode, so do our normal things.

                                       ;; Make the play button green to indicate it will start the show
                                       (swap! (:next-text-buttons controller)
                                              assoc (:stop control-buttons) dim-green-color)

                                       ;; But see if we need to exit because the show has started
                                       (with-show (:show controller)
                                         (when (show/running?)
                                           (update-mode! controller :stop false)
                                           (swap! end-counter #(or % (- 8 @start-counter))))
                                         true)))))  ; Give the ending animation a chance to run
                               (handle-control-change [this message]
                                 #_(timbre/info "Stop message" message)
                                 (when (pos? (:velocity message))
                                   ;; End stop mode and start the ending animation if it isn't already
                                   (with-show (:show controller)
                                     (show/start!))
                                   (update-mode! controller :stop false)
                                   (swap! end-counter #(or % (- 8 @start-counter)))))
                               (handle-note-on [this message])
                               (handle-note-off [this message])
                               (handle-aftertouch [this message])
                               (handle-pitch-bend [this message])))))

(defn add-button-held-feedback-overlay
  "Adds a simple overlay which keeps a control button bright as long
  as the user is holding it down."
  [controller button]
  (controllers/add-control-held-feedback-overlay (:overlays controller) (:control button)
                                                 (fn [_] (swap! (:next-text-buttons controller)
                                                                assoc button (or (:bright-color button)
                                                                                 white-color)))))

(defn- handle-save-effect
  "Process a tap on one of the pads which indicate the user wants to
  save or clear the variables for the associated effect."
  [controller note]
  (let [room    (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx      (:effects fx-info)
        fx-meta (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        fx (vec (drop num-skipped fx))
        fx-meta (vec (drop num-skipped fx-meta))
        offset  (- 4 room)
        x       (quot (- note 20) 2)
        index   (- x offset)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect      (get fx index)
            info        (get fx-meta index)
            cue         (:cue info)
            cur-vals    (cues/snapshot-cue-variables cue (:id info) :show (:show controller))
            saved-vals  (controllers/cue-vars-saved-at (:cue-grid (:show controller)) (:x info) (:y info))
            save-action (when (seq cur-vals)
                          (if (= cur-vals saved-vals) :clear :save))]
        (when save-action
          (case save-action
            :save  (controllers/save-cue-vars! (:cue-grid (:show controller)) (:x info) (:y info) cur-vals)
            :clear (controllers/clear-saved-cue-vars! (:cue-grid (:show controller)) (:x info) (:y info)))
          (controllers/add-control-held-feedback-overlay (:overlays controller) note
                                                         (fn [_]
                                                           (swap! (:next-top-pads controller) assoc (* 2 x)
                                                                  (case save-action
                                                                    :save  green-color
                                                                    :clear amber-color))
                                                           (write-display-cell controller 3 x
                                                                               (case save-action
                                                                                 :save  "  Saved  "
                                                                                 :clear "Cleared  "))
                                                           true)))))))

(defn- handle-end-effect
  "Process a tap on one of the pads which indicate the user wants to
  end the associated effect."
  [controller note]
  (let [room (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx (:effects fx-info)
        fx-meta (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        fx (vec (drop num-skipped fx))
        fx-meta (vec (drop num-skipped fx-meta))
        offset (- 4 room)
        x (quot (- note 20) 2)
        index (- x offset)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect (get fx index)
            info (get fx-meta index)]
        (with-show (:show controller)
          (show/end-effect! (:key info) :when-id (:id info)))
        (controllers/add-overlay (:overlays controller)
                                 (reify controllers/IOverlay
                                   (captured-controls [this] #{note (inc note)})
                                   (captured-notes [this] #{})
                                   (adjust-interface [this _]
                                     (swap! (:next-top-pads controller) assoc (* 2 x) red-color)
                                     (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
                                     true)
                                   (handle-control-change [this message]
                                     (when (and (= (:note message) note) (zero? (:velocity message)))
                                       :done))
                                   (handle-note-on [this message])
                                   (handle-note-off [this message])
                                   (handle-aftertouch [this message])
                                   (handle-pitch-bend [this message])))))))

(defn- handle-scroll-cue-vars
  "Process a tap on one of the pads which indicate the user wants to
  scroll forward in the list of cue variables."
  [controller note]
  (let [room (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx (vec (:effects fx-info))
        fx-meta (vec (:meta fx-info))
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        fx (vec (drop num-skipped fx))
        fx-meta (vec (drop num-skipped fx-meta))
        offset (- 4 room)
        x (quot (- note 21) 2)
        index (- x offset)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect (get fx index)
            info (get fx-meta index)
            cue (:cue info)
            var-count (count (:variables cue))]
        (when (> var-count 2)
          (swap! (:cue-var-offsets controller) update-in [(:id info)] #(mod (+ 2 (or % 0)) var-count))
          (controllers/add-overlay (:overlays controller)
                                   (reify controllers/IOverlay
                                     (captured-controls [this] #{note})
                                     (captured-notes [this] #{})
                                     (adjust-interface [this _]
                                       (swap! (:next-top-pads controller) assoc (inc (* 2 x)) white-color)
                                       true)
                                     (handle-control-change [this message]
                                       (when (and (= (:note message) note) (zero? (:velocity message)))
                                         :done))
                                     (handle-note-on [this message])
                                     (handle-note-off [this message])
                                     (handle-aftertouch [this message])
                                     (handle-pitch-bend [this message]))))))))

(defn- move-origin
  "Changes the origin of the controller, notifying any registered
  listeners."
  [controller origin]
  (when (not= origin @(:origin controller))
    (reset! (:origin controller) origin)
    (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :moved))))

(defn- control-change-received
  "Process a control change message which was not handled by an
  interface overlay."
  [controller message]
  (case (:note message)
    3 ; Tap tempo button
    (when (pos? (:velocity message))
      ((:tempo-tap-handler controller))
      (enter-metronome-showing controller))

    9 ; Metronome button
    (when (pos? (:velocity message))
      (enter-metronome-showing controller))

    (20 22 24 26) ; Effect end/save pads
    (when (pos? (:velocity message))
      (if (in-mode? controller :record)
        (handle-save-effect controller (:note message))
        (handle-end-effect controller (:note message))))

    (21 23 25 27) ; Effect cue variable scroll pads
    (when (pos? (:velocity message))
      (handle-scroll-cue-vars controller (:note message)))

    ;; 28 ; Master button

    85 ; Play button
    (when (pos? (:velocity message))
      (enter-stop-mode controller))

    (49 86) ; Shift or Record button
    (update-mode! controller (:note message) (pos? (:velocity message)))

    62 ; Page left, scroll back to older effects
    (when (pos? (:velocity message))
        (let [[offset max-offset room] (find-effect-offset-range controller)
              new-offset (if (in-mode? controller :shift)
                           max-offset
                           (min max-offset (+ offset room)))]
          (when (not= offset new-offset)
            (reset! (:effect-offset controller) new-offset)
            (add-button-held-feedback-overlay controller (:page-left control-buttons)))))

    63; Page right, scroll forward to newer effects
    (when (pos? (:velocity message))
      (let [[offset max-offset room] (find-effect-offset-range controller)
            new-offset (if (in-mode? controller :shift) 0 (max 0 (- offset room)))]
        (when (not= offset new-offset)
          (reset! (:effect-offset controller) new-offset)
          (add-button-held-feedback-overlay controller (:page-right control-buttons)))))

    44 ; Left arrow, scroll left in cue grid
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? x)
          (move-origin controller [(if (in-mode? controller :shift) 0 (max 0 (- x 8))) y])
          (add-button-held-feedback-overlay controller (:left-arrow control-buttons)))))

    45 ; Right arrow, scroll right in cue grid
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        ;; TODO: Shift to scroll all the way; also add shift support in web UI and other controllers
        (when (> (- (controllers/grid-width (:cue-grid (:show controller))) x) 7)
          (move-origin controller [(+ x 8) y])
          (add-button-held-feedback-overlay controller (:right-arrow control-buttons)))))

    46 ; Up arrow, scroll up in cue grid
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        ;; TODO: Shift to scroll all the way; also add shift support in web UI and other controllers
        (when (> (- (controllers/grid-height (:cue-grid (:show controller))) y) 7)
          (move-origin controller [x (+ y 8)])
          (add-button-held-feedback-overlay controller (:up-arrow control-buttons)))))

    47 ; Down arrow, scroll down in cue grid
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? y)
          (move-origin controller [x (if (in-mode? controller :shift) 0 (max 0 (- y 8)))])
          (add-button-held-feedback-overlay controller (:down-arrow control-buttons)))))

    59 ; User mode button
    (when (pos? (:velocity message))
      (leave-user-mode controller))

    ;; Something we don't care about
    nil))

(defn- note-to-cue-coordinates
  "Translate the MIDI note associated with an incoming message to its
  coordinates in the show cue grid."
  [controller note]
  (let [base (- note 36)
        [origin-x origin-y] @(:origin controller)
        pad-x (rem base 8)
        pad-y (quot base 8)
        cue-x (+ origin-x pad-x)
        cue-y (+ origin-y pad-y)]
    [cue-x cue-y pad-x pad-y]))

(defn- cue-grid-pressed
  "One of the pads in the 8x8 pressure-sensitve cue grid was pressed."
  [controller note velocity]
  (let [[cue-x cue-y pad-x pad-y] (note-to-cue-coordinates controller note)
        [cue active] (show/find-cue-grid-active-effect (:show controller) cue-x cue-y)]
          (when cue
            (with-show (:show controller)
              (if (and active (not (:held cue)))
                (show/end-effect! (:key cue))
                (let [id (show/add-effect-from-cue-grid! cue-x cue-y :velocity velocity)
                      holding (and (:held cue) (not (in-mode? controller :shift)))]
                  (controllers/add-overlay
                   (:overlays controller)
                   (reify controllers/IOverlay
                     (captured-controls [this] #{})
                     (captured-notes [this] #{note})
                     (adjust-interface [this snapshot]
                       (when holding
                         (let [active (show/find-effect (:key cue))
                               base-color (cues/current-cue-color cue active (:show controller) snapshot)
                               color (colors/create-color
                                      :h (colors/hue base-color)
                                      :s (colors/saturation base-color)
                                      :l 75)]
                           (swap! (:next-grid-pads controller) assoc (+ pad-x (* pad-y 8)) color)))
                       true)
                     (handle-control-change [this message])
                     (handle-note-on [this message])
                     (handle-note-off [this message]
                       (when holding
                         (with-show (:show controller)
                           (show/end-effect! (:key cue) :when-id id)))
                       :done)
                     (handle-aftertouch [this message]
                       (if (zero? (:velocity message))
                         (do (when holding
                               (with-show (:show controller)
                                 (show/end-effect! (:key cue) :when-id id)))
                             :done)
                         (doseq [v (:variables cue)]
                           (when (:velocity v)
                             (cues/set-cue-variable! cue v
                                                     (controllers/value-for-velocity v (:velocity message))
                                                     :show (:show controller) :when-id id)))))
                     (handle-pitch-bend [this message])))))))))

(defn- control-for-top-encoder-note
  "Return the control number on which rotation of the encoder whose
  touch events are sent on the specified note will be sent."
  [note]
  (+ note 71))

(defn- ^:deprecated draw-variable-gauge
  "Display the value of a variable being adjusted in the effect list."
  [controller cell width offset cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id) 0)
        low (min value (:min v))  ; In case user set "out of bounds".
        high (max value (:max v))
        gauge (if (:centered v)
                (make-pan-gauge value :lowest low :highest high :width width)
                (make-gauge value :lowest low :highest high :width width))]
    (write-display-text controller 0 (+ offset (* cell 17)) gauge)))

(defn- adjust-variable-value
  "Handle a control change from turning an encoder associated with a
  variable being adjusted in the effect list."
  [controller message cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id) 0)
        low (min value (:min v))  ; In case user set "out of bounds".
        high (max value (:max v))
        raw-resolution (/ (- high low) 200)
        resolution (or (:resolution v) (if (= :integer (:type v))
                                         (max 1 (Math/round (double raw-resolution)))
                                         raw-resolution))
        delta (* (sign-velocity (:velocity message)) resolution)
        adjusted (+ value delta)
        normalized (if (= :integer (:type v)) (Math/round (double adjusted))
                       (double (* (Math/round (/ adjusted resolution)) resolution)))]
    (cues/set-cue-variable! cue v (max low (min high normalized)) :show (:show controller) :when-id effect-id)))

(defn- bend-variable-value
  "Handle a pitch bend change while an encoder associated with a
  variable is being adjusted in the effect list."
  [controller message cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id) 0)
        full-range (- (:max v) (:min v))
        fraction (/ (+ (* (:data2 message) 128) (:data1 message)) 16383)
        adjusted (double (+ (:min v) (* fraction full-range)))
        raw-resolution (/ full-range 200)
        resolution (or (:resolution v) (if (= :integer (:type v))
                                         1
                                         raw-resolution))
        normalized (if (= :integer (:type v)) (Math/round (double adjusted))
                       (double (* (Math/round (/ adjusted resolution)) resolution)))]
    (cues/set-cue-variable! cue v (max (:min v) (min (:max v) normalized)) :show (:show controller)
                            :when-id effect-id)))

(defn- draw-boolean-gauge
  "Display the value of a boolean variable being adjusted in the effect list."
  [controller cell width offset cue v effect-id]
  (let [value (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id)
        gauge (concat "No" (when-not value [0])
                      (repeat (- width 6) \ )
                      (when value [0]) "Yes")]
    (write-display-text controller 0 (+ offset (* cell 17)) gauge)))

(defn- adjust-boolean-value
  "Handle a control change from turning an encoder associated with a
  boolean variable being adjusted in the effect list."
  [controller message cue v effect-id]
  (let [new-value (true? (pos? (sign-velocity (:velocity message))))]
    (cues/set-cue-variable! cue v new-value :show (:show controller) :when-id effect-id)))

(defn- same-effect-active
  "See if the specified effect is still active with the same id."
  [controller cue id]
  (with-show (:show controller)
    (let [effect-found (show/find-effect (:key cue))]
      (and effect-found (= (:id effect-found) id)))))

(defn- build-boolean-adjustment-overlay
  "Create an overlay for adjusting a boolean cue parameter. `note`
  identifies the encoder that was touched to bring up this overlay,
  `cue` is the cue whose variable is being adjusted, `v` is the map
  identifying the variable itself, `effect` is the effect which that
  cue is running, and `info` is the metadata about that effect.

  Also suppresses the ability to scroll through the cue variables
  while the encoder is being held."
  [controller note cue v effect info]
  (let [x (quot note 2)
        var-index (rem note 2)]
    (if (> (count (:variables cue)) 1)
      ;; More than one variable, adjust whichever's encoder was touched
      (reify controllers/IOverlay
        (captured-controls [this] #{(control-for-top-encoder-note note) (+ 21 (* 2 x))})
        (captured-notes [this] #{note})
        (adjust-interface [this _]
          (when (same-effect-active controller cue (:id info))
            (draw-boolean-gauge controller x 8 (* 9 var-index) cue v (:id info))
            (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
            true))
        (handle-control-change [this message]
          (when (= (:note message) (control-for-top-encoder-note note))
            (adjust-boolean-value controller message cue v (:id info)))
          true)
        (handle-note-on [this message])
        (handle-note-off [this message]
          :done)
        (handle-aftertouch [this message])
        (handle-pitch-bend [this message]))

      ;; Just one variable, take full cell, using either encoder,
      ;; suppress the other one.
      (let [paired-note (if (odd? note) (dec note) (inc note))]
        (reify controllers/IOverlay
          (captured-controls [this] #{(control-for-top-encoder-note note) (+ 21 (* 2 x))})
          (captured-notes [this] #{note paired-note})
          (adjust-interface [this _]
            (when (same-effect-active controller cue (:id info))
              (draw-boolean-gauge controller x 17 0 cue v (:id info)))
            (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
            true)
          (handle-control-change [this message]
            (when (= (:note message) (control-for-top-encoder-note note))
              (adjust-boolean-value controller message cue v (:id info)))
            true)
          (handle-note-on [this message]
            true)
          (handle-note-off [this message]
            (when (= (:note message) note)
              :done))
          (handle-aftertouch [this message])
          (handle-pitch-bend [this message]))))))

(defn- build-numeric-adjustment-overlay
  "Create an overlay for adjusting a numeric cue parameter. `note`
  identifies the encoder that was touched to bring up this overlay,
  `cue` is the cue whose variable is being adjusted, `v` is the map
  identifying the variable itself, `effect` is the effect which that
  cue is running, and `info` is the metadata about that effect.

  Also suppresses the ability to scroll through the cue variables
  while the encoder is being held."
  [controller note cue cue-var effect info]
  (let [x (quot note 2)
        var-index (rem note 2)]
    (if (> (count (:variables cue)) 1)
      ;; More than one variable, adjust whichever's encoder was touched
      (reify controllers/IOverlay
        (captured-controls [this] #{(control-for-top-encoder-note note) (+ 21 (* 2 x))})
        (captured-notes [this] #{note})
        (adjust-interface [this _]
          (when (same-effect-active controller cue (:id info))
            (let [cur-val (or (cues/get-cue-variable cue cue-var :show (:show controller) :when-id (:id info)) 0)]
              (if (:centered cue-var)
                (draw-pan-gauge controller note 1 cur-val :lowest (min cur-val (:min cue-var))
                                :highest (max cur-val (:max cue-var)) :active-color white-color)
                (draw-gauge controller note 1 cur-val :lowest (min cur-val (:min cue-var))
                            :highest (max cur-val (:max cue-var)) :active-color white-color)))
            (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
            (calculate-touch-strip-value controller cue cue-var (:id info))
            true))
        (handle-control-change [this message]
          (when (= (:note message) (control-for-top-encoder-note note))
            (adjust-variable-value controller message cue cue-var (:id info)))
          true)
        (handle-note-on [this message])
        (handle-note-off [this message]
          :done)
        (handle-aftertouch [this message])
        (handle-pitch-bend [this message]
          (bend-variable-value controller message cue cue-var (:id info))
          true))

      ;; Just one variable, take full cell, using either encoder,
      ;; suppress the other one.
      (let [paired-note (if (odd? note) (dec note) (inc note))]
        (reify controllers/IOverlay
          (captured-controls [this] #{(control-for-top-encoder-note note) (+ 21 (* 2 x))})
          (captured-notes [this] #{note paired-note})
          (adjust-interface [this _]
            (when (same-effect-active controller cue (:id info))
              (draw-variable-gauge controller x 17 0 cue cue-var (:id info))
              (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
              (calculate-touch-strip-value controller cue cue-var (:id info))
              true))
          (handle-control-change [this message]
            (when (= (:note message) (control-for-top-encoder-note note))
              (adjust-variable-value controller message cue cue-var (:id info)))
            true)
          (handle-note-on [this message]
            true)
          (handle-note-off [this message]
            (when (= (:note message) note)
              :done))
          (handle-aftertouch [this message])
          (handle-pitch-bend [this message]
            (bend-variable-value controller message cue cue-var (:id info))
            true))))))

(def ^:private color-picker-grid
  (let [result (transient (vec (repeat 64 nil)))]
    (doseq [i (range 16)]
      (let [x (* 4 (quot i 8))
            y (- 7 (rem i 8))
            origin (+ x (* 8 y))
            hue (* 360 (/ i 15))
            base-color (colors/create-color :hue hue :saturation 100 :lightness 50)]
        (assoc! result origin base-color)
        (assoc! result (inc origin) (colors/desaturate base-color 25))
        (assoc! result (+ origin 2) (colors/desaturate base-color 50))
        (assoc! result (+ origin 3) (colors/desaturate base-color 75))))
    (assoc! result 4 (colors/create-color :h 0 :s 0 :l 100))
    (assoc! result 5 (colors/create-color :h 0 :s 0 :l 50))
    (assoc! result 6 (colors/create-color :h 0 :s 0 :l 0))
    (persistent! result)))

(defn- build-color-adjustment-overlay
  "Create an overlay for adjusting a color cue parameter. `note`
  identifies the encoder that was touched to bring up this overlay,
  `cue` is the cue whose variable is being adjusted, `v` is the map
  identifying the variable itself, `effect` is the effect which that
  cue is running, and `info` is the metadata about that effect.

  Also suppresses the ability to scroll through the cue variables
  while the encoder is being held."
  [controller note cue v effect info]
  (let [anchors (atom #{note})  ; Track which encoders are keeping the overlay active
        x (quot note 2)
        effect-id (:id info)]
    ;; Take full cell, using both encoders to adjust hue and saturation.
    (let [[hue-note sat-note] (if (odd? note) [(dec note) note] [note (inc note)])
          [hue-control sat-control] (map control-for-top-encoder-note [hue-note sat-note])]
      (reify controllers/IOverlay
        (captured-controls [this] #{hue-control sat-control (+ 21 (* 2 x))})
        (captured-notes [this] (clojure.set/union #{hue-note sat-note} (set (drop 36 (range 100)))))
        (adjust-interface [this _]
          (when (same-effect-active controller cue (:id info))
            ;; Draw the color picker grid
            (reset! (:next-grid-pads controller) color-picker-grid)
            (let [current-color (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id)
                                    white-color)
                  hue (colors/hue current-color)
                  sat (colors/saturation current-color)]
              ;; Show the preview color at the bottom right
              (swap! (:next-grid-pads controller) assoc 7 current-color)

              ;; Blink any pad which matches the currently selected color
              (when (< (rhythm/metro-beat-phase (:metronome (:show controller))) 0.3)
                (doseq [i (range 64)]
                  (when (and (not= i 7) (colors/color= current-color (get @(:next-grid-pads controller) i)))
                    (swap! (:next-grid-pads controller) assoc i (if (= i 4)
                                                                  (colors/darken current-color 20)
                                                                  (colors/lighten current-color 20))))))

              ;; Display the hue and saturation numbers and gauges
              (let [hue-str (clojure.string/join (take 5 (str (double hue) "     ")))
                    sat-str (clojure.string/join (take 5 (str (double sat))))
                    hue-gauge (make-pan-gauge hue :highest 360 :width 8)
                    sat-gauge (make-gauge sat :width 8)]
                (write-display-cell controller 0 x (str "H: " hue-str " S: " sat-str))
                (write-display-text controller 1 (* x 17) hue-gauge)
                (write-display-text controller 1 (+ 9 (* x 17)) sat-gauge))

              ;; Put the touch pad into the appropriate state
              (reset! (:next-touch-strip controller)
                      (if (@anchors hue-note)
                        [(math/round (* 16383 (/ hue 360))) touch-strip-mode-hue]
                        [(math/round (* 16383 (/ sat 100))) touch-strip-mode-level]))

              ;; Darken the cue var scroll button if it was going to be lit
              (swap! (:next-top-pads controller) assoc (inc (* 2 x)) off-color)
              true)))
        (handle-control-change [this message]
          ;; Adjust hue or saturation depending on controller; ignore if it was the cue var scroll button
          (when (#{hue-control sat-control} (:note message))
            (let [current-color (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id)
                                    white-color)
                  current-color (colors/create-color :h (colors/hue current-color) :s (colors/saturation current-color)
                                                     :l 50)
                  delta (* (sign-velocity (:velocity message)) 0.5)]
              (cues/set-cue-variable! cue v
                                      (if (= (:note message) hue-control)
                                        (colors/adjust-hue current-color delta)
                                        (colors/saturate current-color delta))
                                      :show (:show controller) :when-id effect-id)))
          true)
        (handle-note-on [this message]
          (let [note (:note message)]
            (if (#{hue-note sat-note} note)
              ;; The user has grabbed another of our controllers, stay active until it is released.
              (swap! anchors conj note)

              ;; It's a grid pad. Set the color based on the selected note, unless it's the preview pad.
              (when-not (= note 43)
                (let [chosen-color (get color-picker-grid (- note 36))]
                  (cues/set-cue-variable! cue v chosen-color :show (:show controller) :when-id effect-id)))))
          true)
        (handle-note-off [this message]
          (swap! anchors disj (:note message))
          (when (empty? @anchors)
            :done))
        (handle-aftertouch [this message])
        (handle-pitch-bend [this message]
          (let [current-color (or (cues/get-cue-variable cue v :show (:show controller) :when-id effect-id)
                                  white-color)
                fraction (double (/ (+ (* (:data2 message) 128) (:data1 message)) 16383))
                new-hue (if (@anchors hue-note) (* fraction 360) (colors/hue current-color))
                new-sat (if (@anchors sat-note) (* fraction 100) (colors/saturation current-color))]
            (cues/set-cue-variable! cue v (colors/create-color :h new-hue :s new-sat :l 50)
                                    :when-id effect-id)))))))

(defn- display-encoder-touched
  "One of the eight encoders above the text display was touched."
  [controller note]
  (let [room (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx (:effects fx-info)
        fx-meta (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        fx (vec (drop num-skipped (:effects fx-info)))
        fx-meta (vec (drop num-skipped (:meta fx-info)))
        offset (- 4 room)
        x (quot note 2)
        index (- x offset)
        var-index (rem note 2)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect (get fx index)
            info (get fx-meta index)
            cue (:cue info)
            cue-vars (cue-vars-for-encoders (:variables cue) (get @(:cue-var-offsets controller) (:id info) 0))
            cue-var (get cue-vars var-index)]
        (when cue-var
          (let [cur-val (cues/get-cue-variable cue cue-var :show (:show controller) :when-id (:id info))]
            (cond
              (or (number? cur-val) (#{:integer :double} (:type cue-var :double)))
              (controllers/add-overlay (:overlays controller)
                                       (build-numeric-adjustment-overlay controller note cue cue-var effect info))

              (or (= (type cur-val) :com.evocomputing.colors/color) (= (:type cue-var) :color))
              (controllers/add-overlay (:overlays controller)
                                       (build-color-adjustment-overlay controller note cue cue-var effect info))

              (or (= (type cur-val) Boolean) (= (:type cue-var) :boolean))
              (controllers/add-overlay (:overlays controller)
                                       (build-boolean-adjustment-overlay controller note cue cue-var effect info))

              :else  ; Something we don't know how to adjust
              nil)))))))

(defn- note-on-received
  "Process a note-on message which was not handled by an interface
  overlay."
  [controller message]
  (let [note (:note message)]
    (cond (<= 0 note 7)
          (display-encoder-touched controller note)

          (<= 36 note 99)
          (cue-grid-pressed controller note (:velocity message))

          :else
          ;; Some other UI element was touched
          (case note
            8 ; Master encoder
            (master-encoder-touched controller)

            9 ; BPM encoder
            (bpm-encoder-touched controller)

            10 ; Beat encoder
            (beat-encoder-touched controller)

            ;; Something we don't care about
            nil))))

(defn- note-off-received
  "Process a note-off message which was not handled by an interface
  overlay."
  [controller message]
  (case (:note message)

    ;; Something we don't care about
    nil))

(defn- midi-received
  "Called whenever a MIDI message is received from the controller
  while the mapping is active; takes whatever action is appropriate."
  [controller message]
  #_(timbre/info message)
  (try
    (when-not (controllers/overlay-handled? (:overlays controller) message)
      (cond
        (= (:command message) :control-change)
        (control-change-received controller message)

        (= (:command message) :note-on)
        (note-on-received controller message)

        (= (:command message) :note-off)
        (note-off-received controller message)

        (= 0xf0 (:status message))
        (sysex-received controller message)))
    (catch Exception e
      (timbre/error e "Problem processing incoming MIDI message:" message))))

(defn deactivate
  "Deactivates a controller interface, killing its update thread and
  removing its MIDI listeners. If `:disconnected` is passed with a
  `true` value, it means that the controller has already been removed
  from the MIDI environment, so no effort will be made to clear its
  display or take it out of User mode.

  You can also pass a watcher object created by [[auto-bind]] as
  `controller`; this will both deactivate the controller being managed
  by the watcher, if it is currently connected, and cancel the
  watcher itself. In such cases, `:disconnected` is meaningless."
  [controller & {:keys [disconnected] :or {disconnected false}}]
  {:pre [(= ::controller (type controller))]}
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))
  (show/unregister-grid-controller @(:grid-controller-impl controller))
  (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :deactivated))
  (reset! (:move-listeners controller) #{})
  (amidi/remove-device-mapping (:port-in controller) @(:midi-handler controller))

  (when-not disconnected
    (Thread/sleep 35) ; Give the UI update thread time to shut down
    (clear-interface controller)
    (restore-led-palettes controller)
    (Wayang/close)

    ;; Leave the User button bright, in case the user has Live
    ;; running and wants to be able to see how to return to it.
    (set-button-color controller (:user-mode control-buttons) white-color))

  ;; Cancel any UI overlays which were in effect
  (reset! (:overlays controller) (controllers/create-overlay-state))

  ;; And finally, note that we are no longer active.
  (controllers/remove-active-binding @(:deactivate-handler controller)))

(defn bind-to-show
  "Establish a connection to the Ableton Push 2, for managing the
  given show. This version is designed to be delegated to by
  [[afterglow.controllers.ableton-push/bind-to-show]] when that detects
  that it has found a Push 2 rather than an original Push. It uses the
  ports and device identifier information that were found in the
  process of starting the binding. See that function for more
  information, and for the externally usable configuration
  parameters."
  [show port-in port-out device refresh-interval display-name]
  {:pre [(some? show)]}
  (load-fonts)
  (let [modes (atom #{})
        controller
        (with-meta
          {:display-name         display-name
           :device-id            device
           :show                 show
           :origin               (atom [0 0])
           :effect-offset        (atom 0)
           :cue-var-offsets      (atom {})
           :refresh-interval     refresh-interval
           :port-in              port-in
           :port-out             port-out
           :task                 (atom nil)
           :led-palettes         (atom {})
           :display-buffer       (Wayang/open)
           :last-text-buttons    (atom {})
           :next-text-buttons    (atom {})
           :last-top-pads        (atom empty-top-pads)
           :next-top-pads        (atom empty-top-pads)
           :last-grid-pads       (atom empty-grid-pads)
           :next-grid-pads       (atom empty-grid-pads)
           :metronome-mode       (atom {})
           :last-marker          (atom nil)
           :modes                modes
           :last-touch-strip     (atom nil)
           :next-touch-strip     (atom nil)
           :midi-handler         (atom nil)
           :deactivate-handler   (atom nil)
           :tempo-tap-handler    (tempo/create-show-tempo-tap-handler
                                  show :shift-fn (fn [] (get @modes (get-in control-buttons [:shift :control]))))
           :overlays             (controllers/create-overlay-state)
           :move-listeners       (atom #{})
           :grid-controller-impl (atom nil)}
          {:type ::controller})]
    (when (gather-led-palettes controller)
      (reset! (:midi-handler controller) (partial midi-received controller))
      (reset! (:deactivate-handler controller) #(deactivate controller))
      (reset! (:grid-controller-impl controller)
              (reify controllers/IGridController
                (display-name [this] (:display-name controller))
                (physical-height [this] 8)
                (physical-width [this] 8)
                (current-bottom [this] (@(:origin controller) 1))
                (current-bottom [this y] (move-origin controller (assoc @(:origin controller) 1 y)))
                (current-left [this] (@(:origin controller) 0))
                (current-left [this x] (move-origin controller (assoc @(:origin controller) 0 x)))
                (add-move-listener [this f] (swap! (:move-listeners controller) conj f))
                (remove-move-listener [this f] (swap! (:move-listeners controller) disj f))))

      ;; Set controller in User mode
      (send-sysex controller [0x0a 1])

      ;; Put pads in aftertouch (poly) pressure mode
      (send-sysex controller [0x1e 1])

      ;; TODO: Set pad sensitivity level to avoid stuck pads? May not be necessary with Push 2

      (clear-interface controller)
      (welcome-animation controller)
      (controllers/add-active-binding @(:deactivate-handler controller))
      (show/register-grid-controller @(:grid-controller-impl controller))
      (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
      controller)))
