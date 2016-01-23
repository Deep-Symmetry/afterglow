(ns afterglow.controllers.tempo
  "Provides support for easily implementing tap-tempo and shift
  buttons on any controller."
  {:author "James Elliott"}
  (:require [overtone.midi :as midi]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show]]
            [taoensso.timbre :as timbre]))

(defn create-show-tempo-tap-handler
  "Returns a function that provides higher level tempo tap support for a show,
  based on the sync mode of the show metronome. Call the returned
  function whenever the user has tapped your tempo button.

  * If the show's sync mode is manual, this will invoke a low-level metronome
  tap-tempo handler to adjust the metronome tempo.

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
  next higher level. (In other words, if it was going to be a tempo
  tap, it would be treated as a beat tap; what would normally be a
  beat tap would be treated as a bar tap, and a bar tap would be
  promoted to start a phrase.)

  Returns a map describing the result of the current tempo tap."
  [show & {:keys [shift-fn] :or {shift-fn (constantly false)}}]
  (let [metronome  (:metronome show)
        tempo-handler (amidi/create-tempo-tap-handler metronome)]
    (fn []
      (with-show show
        (let [base-level (:level (show/sync-status))
              level      (if (shift-fn)
                           (case base-level
                             nil   :bpm
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
