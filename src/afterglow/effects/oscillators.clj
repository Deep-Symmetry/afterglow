(ns afterglow.effects.oscillators
  "Provide a variety of waveforms at frequencies related to the show metronome to
  facilitate building visually and musically pleasing effects."
  (:require [afterglow.rhythm :as rhythm]))

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

(defn triangle-beat
  "Returns an oscillator which generates a triangle wave relative to the phase
  of the current beat. Supplying a beat ratio will run the oscillator at
  the specified fraction or multiple of a beat, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount."
  ([]
   (triangle-beat 1 0.0))

  ([beat-ratio]
   (triangle-beat beat-ratio 0.0))

  ([beat-ratio ^Double phase]
   (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
     (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)
           intensity (adjust-phase (rhythm/snapshot-beat-phase snapshot (/ beat-ratio 2)) phase)]
       (if (< reached 0.5)
         intensity
         (- 1.0 intensity))))))

(defn triangle-bar
  "Returns an oscillator which generates a triangle wave relative to the phase
  of the current bar. Supplying a bar ratio will run the oscillator at
  the specified fraction or multiple of a bar, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount."
  ([]
   (triangle-bar 1 0.0))

  ([bar-ratio]
   (triangle-bar bar-ratio 0.0))

  ([bar-ratio ^Double phase]
   (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
     (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)
           intensity (adjust-phase (rhythm/snapshot-bar-phase snapshot (/ bar-ratio 2)) phase)]
       (if (< reached 0.5)
         intensity
         (- 1.0 intensity))))))

(defn square-beat
  "Returns an oscillator which generates a square wave relative to the phase
  of the current beat. Specifying a width adjusts how much of the time the
  wave is on (high); the default is 0.5, lower values cause it to turn off
  sooner, larger values later. In any case the width must be greater than
  0.0 and less than 1.0. Supplying a beat ratio will run the oscillator at
  the specified fraction or multiple of a beat, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount. "
  ([]
   (square-beat 0.5 1 0.0))

  ([^Double width]
   (square-beat width 1 0.0))

  ([^Double width beat-ratio]
   (square-beat width beat-ratio 0.0))

  ([^Double width beat-ratio ^Double phase]
   (when-not (and (> width 0.0) (< width 1.0))
     (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
   (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
     (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)]
       (if (< reached width)
         1.0
         0.0)))))

(defn square-bar
  "Returns an oscillator which generates a square wave relative to the phase
  of the current bar. Specifying a width adjusts how much of the time the
  wave is on (high); the default is 0.5, lower values cause it to turn on
  sooner, larger values later. In any case the width must be greater than
  0.0 and less than 1.0. Supplying a bar ratio will run the oscillator at
  the specified fraction or multiple of a bar, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount. "
  ([]
   (square-bar 0.5 1 0.0))

  ([^Double width]
   (square-bar width 1 0.0))

  ([^Double width bar-ratio]
   (square-bar width bar-ratio 0.0))

  ([^Double width bar-ratio ^Double phase]
   (when-not (and (> width 0.0) (< width 1.0))
     (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
   (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
     (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)]
       (if (< reached width)
         0.0
         1.0)))))

(defn sine-beat
  "Returns an oscillator which generates a sine wave relative to the phase
  of the current beat. The wave has value 0.0 at phase 0.0, rising to 1.0
  at phase 0.5, and returning to 0.0. Supplying a beat ratio will run the
  oscillator at the specified fraction or multiple of a beat, and supplying
  a phase will offset the oscillator from the underlying metronome phase by
  that amount. "
  ([]
   (sine-beat 1 0.0))

  ([beat-ratio]
   (sine-beat beat-ratio 0.0))

  ([beat-ratio ^Double phase]
   (let [adjusted-phase (- phase 0.25)
         two-pi (* 2.0 Math/PI)]
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (+ 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) adjusted-phase))))))))

(defn sine-bar
  "Returns an oscillator which generates a sine wave relative to the phase
  of the current bar. The wave has value 1.0 at phase 0.0, dropping to 0.0
  at phase 0.5, and returning to 1.0. Supplying a bar ratio will run the
  oscillator at the specified fraction or multiple of a bar, and supplying
  a phase will offset the oscillator from the underlying metronome phase by
  that amount. "
  ([]
   (sine-bar 1 0.0))

  ([bar-ratio]
   (sine-bar bar-ratio 0.0))

  ([bar-ratio ^Double phase]
   (let [adjusted-phase (- phase 0.25)
         two-pi (* 2.0 Math/PI)]
     (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
       (+ 0.5 (* 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) adjusted-phase)))))))))
