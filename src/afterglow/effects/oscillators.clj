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
  [& {:keys [down? beat-ratio phase] :or {down? false beat-ratio 1 phase 0.0}}]
  (cond (and (= beat-ratio 1) (= phase 0.0)) ; Simplest case; maybe no calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (.beat-phase snapshot)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (.beat-phase snapshot)))
        
        (= phase 0.0)  ; Can ignore phase, but have a beat-ratio to contend with
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (rhythm/snapshot-beat-phase snapshot beat-ratio)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (rhythm/snapshot-beat-phase snapshot beat-ratio)))

        :else  ; Full blown beat calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)))))

(defn sawtooth-bar
  "Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current bar. Passing true for down? creates an inverse sawtooth wave
  (ramps downward rather than upward), supplying a bar ratio will run the
  oscillator at the specified fraction or multiple of a bar, and supplying a
  phase will offset the oscillator from the underlying metronome phase by that
  amount."
  [& {:keys [down? bar-ratio phase] :or {down? false bar-ratio 1 phase 0.0}}]
  (cond (and (= bar-ratio 1) (= phase 0.0))  ; Simplest case, maybe no calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (.bar-phase snapshot)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (.bar-phase snapshot)))

        (= phase 0.0)  ; Can ignore phase, but have a bar-ratio to contend with
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (rhythm/snapshot-bar-phase snapshot bar-ratio)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (rhythm/snapshot-bar-phase snapshot bar-ratio)))

        :else  ; Full blown bar calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)))))

(defn sawtooth-phrase
  "Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current phrase. Passing true for down? creates an inverse sawtooth wave
  (ramps downward rather than upward), supplying a phrase ratio will run the
  oscillator at the specified fraction or multiple of a phrase, and supplying a
  phase will offset the oscillator from the underlying metronome phase by that
  amount."
  [& {:keys [down? phrase-ratio phase] :or {down? false phrase-ratio 1 phase 0.0}}]
  (cond (and (= phrase-ratio 1) (= phase 0.0))  ; Simplest case, maybe no calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (.phrase-phase snapshot)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (.phrase-phase snapshot)))

        (= phase 0.0)  ; Can ignore phase, but have a phrase-ratio to contend with
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (rhythm/snapshot-phrase-phase snapshot phrase-ratio)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (rhythm/snapshot-phrase-phase snapshot phrase-ratio)))

        :else  ; Full blown phrase calculation
        (if down?
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (- 1.0 (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)))
          (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
            (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)))))

(defn triangle-beat
  "Returns an oscillator which generates a triangle wave relative to the phase
  of the current beat. Supplying a beat ratio will run the oscillator at
  the specified fraction or multiple of a beat, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount."
  [& {:keys [beat-ratio phase] :or {beat-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)]
      (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn triangle-bar
  "Returns an oscillator which generates a triangle wave relative to the phase
  of the current bar. Supplying a bar ratio will run the oscillator at
  the specified fraction or multiple of a bar, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount."
  [& {:keys [bar-ratio phase] :or {bar-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)]
            (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn triangle-phrase
  "Returns an oscillator which generates a triangle wave relative to the phase
  of the current phrase. Supplying a phrase ratio will run the oscillator at
  the specified fraction or multiple of a phrase, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount."
  [& {:keys [phrase-ratio phase] :or {phrase-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)]
      (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn square-beat
  "Returns an oscillator which generates a square wave relative to the phase
  of the current beat. Specifying a width adjusts how much of the time the
  wave is on (high); the default is 0.5, lower values cause it to turn off
  sooner, larger values later. In any case the width must be greater than
  0.0 and less than 1.0. Supplying a beat ratio will run the oscillator at
  the specified fraction or multiple of a beat, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount. "
  [& {:keys [width beat-ratio phase] :or {width 0.5 beat-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)]
      (if (< reached width)
        1.0
        0.0))))

(defn square-bar
  "Returns an oscillator which generates a square wave relative to the phase
  of the current bar. Specifying a width adjusts how much of the time the
  wave is on (high); the default is 0.5, lower values cause it to turn on
  sooner, larger values later. In any case the width must be greater than
  0.0 and less than 1.0. Supplying a bar ratio will run the oscillator at
  the specified fraction or multiple of a bar, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount. "
  [& {:keys [width bar-ratio phase] :or {width 0.5 bar-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)]
      (if (< reached width)
        0.0
        1.0))))

(defn square-phrase
  "Returns an oscillator which generates a square wave relative to the phase
  of the current phrase. Specifying a width adjusts how much of the time the
  wave is on (high); the default is 0.5, lower values cause it to turn on
  sooner, larger values later. In any case the width must be greater than
  0.0 and less than 1.0. Supplying a phrase ratio will run the oscillator at
  the specified fraction or multiple of a phrase, and supplying a phase
  will offset the oscillator from the underlying metronome phase by that
  amount. "
  [& {:keys [width phrase-ratio phase] :or {width 0.5 phrase-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)]
      (if (< reached width)
        0.0
        1.0))))

(defn sine-beat
  "Returns an oscillator which generates a sine wave relative to the phase
  of the current beat. The wave has value 0.0 at phase 0.0, rising to 1.0
  at phase 0.5, and returning to 0.0. Supplying a beat ratio will run the
  oscillator at the specified fraction or multiple of a beat, and supplying
  a phase will offset the oscillator from the underlying metronome phase by
  that amount. "
  [& {:keys [beat-ratio phase] :or {beat-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) adjusted-phase)))))))

(defn sine-bar
  "Returns an oscillator which generates a sine wave relative to the phase
  of the current bar. The wave has value 1.0 at phase 0.0, dropping to 0.0
  at phase 0.5, and returning to 1.0. Supplying a bar ratio will run the
  oscillator at the specified fraction or multiple of a bar, and supplying
  a phase will offset the oscillator from the underlying metronome phase by
  that amount. "
  [& {:keys [bar-ratio phase] :or {bar-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (* 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) adjusted-phase))))))))

(defn sine-phrase
  "Returns an oscillator which generates a sine wave relative to the phase
  of the current phrase. The wave has value 1.0 at phase 0.0, dropping to 0.0
  at phase 0.5, and returning to 1.0. Supplying a phrase ratio will run the
  oscillator at the specified fraction or multiple of a phrase, and supplying
  a phase will offset the oscillator from the underlying metronome phase by
  that amount. "
  [& {:keys [phrase-ratio phase] :or {phrase-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (* 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) adjusted-phase))))))))
