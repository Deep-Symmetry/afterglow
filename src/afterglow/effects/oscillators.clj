(ns afterglow.effects.oscillators
  (:require [afterglow.rhythm :as rhythm]
            [taoensso.timbre :as timbre :refer [error warn info debug]]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile]]))

(defn- adjust-phase
  "Helper function to offset a phase by a given amount. Phases range from [0.0-1.0)."
  [^Double phase ^Double offset]
  (let [sum (+ phase offset)]
    (- sum (long sum))))

(defn sawtooth-beat
  "Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current beat. Passing true for down? creates an inverse sawtooth wave
  (ramps downward rather than upward), supplying a beat ratio will run the
  oscillator at the specified fraction or multiple of a beat, and supplying a
  phase will offset the oscillator from the underlying metronome phase by that
  amount."
  ([]
   (sawtooth-beat false))

  ([^Boolean down?]
   (if down?
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (- 1.0 (.beat-phase snapshot)))
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (.beat-phase snapshot))))

  ([^Boolean down? beat-ratio]
   (if (= beat-ratio 1)
     (sawtooth-beat down?) ;Delegate to faster implementation since it gives the right results
     (if down?
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (- 1.0 (rhythm/snapshot-beat-phase snapshot beat-ratio)))
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (rhythm/snapshot-beat-phase snapshot beat-ratio)))))

  ([^Boolean down? beat-ratio ^Double phase]
   (if (= phase 0.0)
     (sawtooth-beat down? beat-ratio) ;Delegate to faster implementation since it gives the right results
     (if down?
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (- 1.0 (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)))
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase))))))

(defn sawtooth-bar
  "Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current bar. Passing true for down? creates an inverse sawtooth wave
  (ramps downward rather than upward), supplying a bar ratio will run the
  oscillator at the specified fraction or multiple of a bar, and supplying a
  phase will offset the oscillator from the underlying metronome phase by that
  amount."
  ([]
   (sawtooth-bar false))

  ([^Boolean down?]
   (if down?
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (- 1.0 (.bar-phase snapshot)))
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (.bar-phase snapshot))))

  ([^Boolean down? bar-ratio]
   (if (= bar-ratio 1)
     (sawtooth-bar down?) ;Delegate to faster implementation since it gives the right results
     (if down?
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (- 1.0 (rhythm/snapshot-bar-phase snapshot bar-ratio)))
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (rhythm/snapshot-bar-phase snapshot bar-ratio)))))

  ([^Boolean down? bar-ratio ^Double phase]
   (if (= phase 0.0)
     (sawtooth-bar down? bar-ratio) ;Delegate to faster implementation since it gives the right results
     (if down?
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (- 1.0 (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)))
       (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
         (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase))))))
