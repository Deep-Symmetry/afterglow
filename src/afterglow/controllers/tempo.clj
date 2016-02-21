(ns afterglow.controllers.tempo
  "Provides support for easily implementing tap-tempo and shift
  buttons on any MIDI controller."
  {:author "James Elliott"}
  (:require [overtone.midi :as midi]
            [afterglow.controllers :as controllers]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show]]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss :refer (have have! have?)]))

(defn create-show-tempo-tap-handler
  "Returns a function that provides higher level tempo tap support for a show,
  based on the sync mode of the show metronome. Call the returned
  function whenever the user has tapped your tempo button.

  * If the show's sync mode is manual, this will invoke a low-level
  metronome tap-tempo handler to align the beat to the tap, and (if
  three or more taps occur within two seconds of each other) adjust
  the metronome tempo.

  * If the show's sync mode is MIDI, calling the returned function will
  align the current beat to the tap.

  * If the show's sync mode is DJ Link or Traktor Beat phase (so beats
  are already automatically aligned), calling the returned function
  will align the current beat to be a down beat (first beat of a bar).

  If you have set up a button on your controller to act like the shift
  button on one of the full-featured grid controllers, you can pass in
  a function with `:shift-fn` that returns `true` when that the shift
  button is held down. Whenever that function returns `true` for a
  tempo tap, the returned tap handler function will synchronize at the
  next higher level. (In other words, if it was going to be a tempo or
  beat tap, it would be treated as a bar tap, and what would normally
  be a bar tap would be promoted to start a phrase.)

  Returns a map describing the result of the current tempo tap."
  [show & {:keys [shift-fn] :or {shift-fn (constantly false)}}]
  (let [metronome  (:metronome show)
        tempo-handler (amidi/create-tempo-tap-handler metronome)]
    (fn []
      (with-show show
        (let [base-level (:level (show/sync-status))
              level      (if (shift-fn)
                           (case base-level
                             nil   :bar
                             :bpm  :beat
                             :beat :bar
                             :bar  :phrase
                             base-level)
                           base-level)]
          (case level
            nil (do (tempo-handler)
                    {:tempo "adjusting"})
            :bpm (do (rhythm/metro-beat-phase metronome 0)
                     {:started "beat"})
            :beat (do (rhythm/metro-bar-start metronome (rhythm/metro-bar metronome))
                      {:started "bar"})
            :bar (do (rhythm/metro-phrase-start metronome (rhythm/metro-phrase metronome))
                     {:started "phrase"})
            (let [warning (str "Don't know how to tap tempo for sync type" level)]
              (timbre/warn warning)
              {:error warning})))))))

(defn add-midi-control-to-tempo-mapping
  "Sets up a controller pad or button to act as a sync-aware Tap Tempo
  button for the default metronome in [[*show*]]. Whenever the
  specified note (when `kind` is `:note`) or controller-change (when
  `kind` is `:control`) message is received with a non-zero
  velocity (or control value), the appropriate tempo adjustment action
  is taken.

  * If the show's sync mode is manual, this will invoke a low-level metronome
  tap-tempo handler to adjust the metronome tempo.

  * If the show's sync mode is MIDI, calling the returned function will
  align the current beat to the tap.

  * If the show's sync mode is DJ Link or Traktor Beat phase (so beats
  are already automatically aligned), calling the returned function
  will align the current beat to be a down beat (first beat of a bar).

  The device to be mapped is identified by `device-filter`. The first
  input port which matches using [[filter-devices]] will be used.

  Afterglow will attempt to provide feedback by flashing the pad or
  button on each beat sending note on/off or control-change values to
  the same controller. The note velocities or control values used can
  be changed by passing in different values with `:feedback-on` and
  `:feedback-off`, and this behavior can be suppressed entirely by
  passing `false` with `:feedback-on`.

  If you have set up a button on your controller to act like the shift
  button on one of the full-featured grid controllers, you can pass in
  a function with `:shift-fn` that returns `true` when that the shift
  button is held down. Whenever that function returns `true` for a
  tempo tap, the returned tap handler function will synchronize at the
  next higher level. (In other words, if it was going to be a tempo
  tap, it would be treated as a beat tap; what would normally be a
  beat tap would be treated as a bar tap, and a bar tap would be
  promoted to start a phrase.) When you map a shift button
  using [[add-midi-control-to-shift-mapping]], it gives you a function
  that is suitable for use with `:shift-fn` as the value of the
  `:state` key in the map it returns.

  Returns the tempo-mapping function which can be passed
  to [[remove-midi-control-to-tempo-mapping]] if you ever want to stop
  the MIDI control or note from affecting the metronome in the
  future."
  [device-filter channel kind note & {:keys [feedback-on feedback-off shift-fn]
                                      :or {feedback-on 127 feedback-off 0 shift-fn (constantly false)}}]
  {:pre [(have? some? *show*) (have? #{:control :note} kind) (have? some? device-filter)
         (have? integer? channel) (have? #(<= 0 % 15) channel) (have? integer? note) (have? #(<= 0 % 127) note)
         (have? #(or (not %) (and (integer? %) (<= 0 % 127))) feedback-on)
         (have? integer? feedback-off) (have? #(<= 0 % 127) feedback-off)
         (have? fn? shift-fn)]}
  (let [feedback-device (when feedback-on (amidi/find-midi-out device-filter))
        tap-handler (create-show-tempo-tap-handler *show* :shift-fn shift-fn)
        midi-handler (fn [message]
                       (when (pos? (:velocity message))
                         (tap-handler)))]
    (when feedback-device  ; Set up to give feedback as cue activation changes
      (controllers/add-beat-feedback! (:metronome *show*) feedback-device channel kind note
                                      :on feedback-on :off feedback-off))
    (case kind
      :control (amidi/add-control-mapping device-filter channel note midi-handler)
      :note (amidi/add-note-mapping device-filter channel note midi-handler))
    midi-handler))

(defn remove-midi-control-to-tempo-mapping
  "Undoes the effect of [[add-midi-control-to-tempo-mapping]]. You
  must pass the value that was returned by that function as the
  argument `f`."
  [device-filter channel kind note f]
  {:pre [(have? some? *show*) (have? #{:control :note} kind) (have? some? device-filter)
         (have? integer? channel) (have? #(<= 0 % 15) channel) (have? integer? note) (have? #(<= 0 % 127) note)
         (have? fn? f)]}
  (let [feedback-device (amidi/find-midi-out device-filter)]
    (when feedback-device
      (controllers/clear-beat-feedback! (:metronome *show*) feedback-device channel kind note))
    (case kind
      :control (amidi/remove-control-mapping device-filter channel note f)
      :note (amidi/remove-note-mapping device-filter channel note f))
    nil))

(defn add-midi-control-to-shift-mapping
  "Sets up a controller pad or button to act as a shift button which
  can be used to modify other mappings. The shift state is entered
  whenever the specified note (when `kind` is `:note`) or
  controller-change (when `kind` is `:control`) message is received
  with a non-zero velocity (or control value) on the specified
  `channel`. The shift state is ended whenever a matching note-off or
  controller-change message with velocity zero is received.

  The device to be mapped is identified by `device-filter`. The first
  input port which matches using [[filter-devices]] will be used.

  Afterglow will attempt to provide feedback by lighting the pad or
  button when it is held down by sending note on/off or control-change
  values to the same controller. The note velocities or control values
  used can be changed by passing in different values with
  `:feedback-on` and `:feedback-off`, and this behavior can be
  suppressed entirely by passing `false` with `:feedback-on`.

  Returns a map containing an entry `:state` whose value is a function
  that can be called to check the state of the shift button, returning
  `true` whenever it is held down. There are other entries in the map
  which are used for the mapping's own purposes. Pass the entire map
  to [[remove-midi-control-to-shift-mapping]] if you ever want to stop
  the MIDI control or note from acting like a shift button."
  [device-filter channel kind note & {:keys [feedback-on feedback-off]
                                      :or {feedback-on 127 feedback-off 0}}]
  {:pre [(have? #{:control :note} kind) (have? some? device-filter)
         (have? integer? channel) (have? #(<= 0 % 15) channel) (have? integer? note) (have? #(<= 0 % 127) note)
         (have? #(or (not %) (and (integer? %) (<= 0 % 127))) feedback-on)
         (have? integer? feedback-off) (have? #(<= 0 % 127) feedback-off)]}
  (let [feedback-device (when feedback-on (amidi/find-midi-out device-filter))
        state (atom false)
        midi-handler (fn [message]
                       (reset! state (and (pos? (:velocity message))
                                          (or (= :control kind)
                                              (= :note-on (:command message)))))
                       (when feedback-device
                         (if (= :control kind)
                           (midi/midi-control feedback-device note (if @state feedback-on feedback-off) channel)
                           (if @state
                             (midi/midi-note-on feedback-device note feedback-on channel)
                             (midi/midi-note-off feedback-device note channel)))))]
    (case kind
      :control (amidi/add-control-mapping device-filter channel note midi-handler)
      :note (amidi/add-note-mapping device-filter channel note midi-handler))
    (with-meta
      {:state (fn [] @state)
       :handler midi-handler
       :clear-handler (fn []
                        (when feedback-device
                          (if (= :control kind)
                            (midi/midi-control feedback-device note feedback-off channel)
                            (midi/midi-note-off feedback-device note channel))))}
      {:type :midi-shift-mapping})))

(defn remove-midi-control-to-shift-mapping
  "Undoes the effect of [[add-midi-control-to-tempo-mapping]]. You
  must pass the value that was returned by that function as the
  argument `mapping`."
  [device-filter channel kind note mapping]
  {:pre [(have? #{:control :note} kind) (have? some? device-filter)
         (have? integer? channel) (have? #(<= 0 % 15) channel) (have? integer? note) (have? #(<= 0 % 127) note)
         (have? #(= (type %) :midi-shift-mapping) mapping)]}
  (case kind
    :control (amidi/remove-control-mapping device-filter channel note (:handler mapping))
    :note (amidi/remove-note-mapping device-filter channel note (:handler mapping)))
  ((:clear-handler mapping))
  nil)
