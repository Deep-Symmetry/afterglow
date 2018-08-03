(ns afterglow.rhythm
  "Functions to help work with musical time, evolved from the original
  version in [Overtone](https://github.com/overtone/overtone/blob/master/src/overtone/music/rhythm.clj)."
 {:author "Jeff Rose, James Elliott"}
 (:require [clojure.math.numeric-tower :refer [round floor]]
           [overtone.at-at :refer [now]]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads."}
  _PROTOCOLS_
  (do
(defprotocol IMetronome
  "A time-keeping tool for music-related systems."

  (metro-start [metro] [metro start-beat]
  "Returns the start time of the metronome. Also restarts the
  metronome at `start-beat` if given.")

  (metro-bar-start [metro start-bar]
  "Restarts the metronome at `start-bar`, keeping the beat phase
  unchanged in case it is being synced to an external source.")

  (metro-phrase-start [metro start-phrase]
  "Restarts the metronome at `start-phrase`, keeping the beat phase
  unchanged in case it is being synced to an external source.")

  (metro-adjust [metro ms]
  "Adds a number of milliseconds to the start time of the metronome.")

  (metro-tick [metro]
  "Returns the duration of one beat in milleseconds.")

  (metro-tock [metro]
  "Returns the duration of one bar in milliseconds.")

  (metro-ding [metro]
  "Returns the duration of one phrase in milliseconds.")

  (metro-beat [metro] [metro beat]
  "Returns the next beat number or the timestamp (in milliseconds) of the
  given `beat`.")

  (metro-beat-phase [metro] [metro phase]
  "Returns the distance traveled into the current beat as a phase
  number ranging from [0.0, 1.0), or adjusts the phase to match the
  one supplied.")

  (metro-bar [metro] [metro bar]
  "Returns the next bar number or the timestamp (in milliseconds) of
  the given `bar`.")

  (metro-bar-phase [metro] [metro phase]
  "Returns the distance traveled into the current bar as a phase
  number ranging from [0.0, 1.0), or adjusts the phase to match the
  one supplied.")

  (metro-phrase [metro] [metro phrase]
  "Returns the next phrase number or the timestamp (in milliseconds)
  of the given `phrase`.")

  (metro-phrase-phase [metro] [metro phase]
  "Returns the distance traveled into the current phrase as a phase
  number ranging from [0.0, 1.0), or adjusts the phase to match the
  one supplied.")

  (metro-bpb [metro] [metro new-bpb]
  "Get the current beats per bar or change it to `new-bpb`")

  (metro-bpp [metro] [metro new-bpp]
  "Get the current bars per phrase, or change it to `new-bpp`")

  (metro-bpm [metro] [metro new-bpm]
  "Get the current bpm or change the bpm to `new-bpm`.")

  (metro-snapshot [metro] [metro instant]
  "Take a snapshot of the current beat, bar, phrase, and phase state.
  If `instant` is supplied, calculates a snapshot for the
  corresponding time rather than the current time.")

  (metro-marker [metro]
  "Returns the current time as `\"phrase.bar.beat\"`")

  (metro-add-bpm-watch [metro key f]
  "Register a function to be called whenever the metronome's BPM
  changes. The `key` and `function` arguments are the same as found in
  [clojure.core/add-watch](http://clojuredocs.org/clojure.core/add-watch),
  and in fact are passed on to it.")

  (metro-remove-bpm-watch [metro key]
  "Stop calling the function which was registered with the specified
  `key`."))

(defprotocol ISnapshot
  "A snapshot to support a series of beat and phase calculations with
  respect to a given instant in time. Used by Afterglow so that all
  phase computations performed when updating a frame of DMX data have
  a consistent sense of when they are being run, to avoid, for
  example, half the lights acting as if they are at the very end of a
  beat while the rest are at the beginning of the next beat, due to a
  fluke in timing as their evaluation occurs over time. Snapshots also
  extend the notions of beat phase to enable oscillators with
  frequencies that are fractions or multiples of a beat."

  (snapshot-beat-phase [snapshot] [snapshot beat-ratio]
  "Determine the metronome's phase at the time of the snapshot with
  respect to a multiple or fraction of beats. Calling this with a
  `beat-ratio` of 1 (the default if not provided) is equivalent
  to [[metro-beat-phase]], calling with a `beat-ratio` equal
  to [[metro-bpb]] is equivalent to [[metro-bar-phase]], 1/2
  oscillates twice as fast as 1, 3/4 oscillates 4 times every three
  beats... Phases range from [0-1).")

  (snapshot-bar-phase [snapshot] [snapshot bar-ratio]
  "Determine the metronome's phase at the time of the snapshot with
  respect to a multiple or fraction of bars. Calling this with a
  `bar-ratio` of 1 (the default if not provided) is equivalent
  to [[metro-bar-phase]], calling with a `bar-ratio` equal
  to [[metro-bpp]] is equivalent to [[metro-phrase-phase]], 1/2
  oscillates twice as fast as 1, 3/4 oscillates 4 times every three
  bars... Phases range from [0-1).")

  (snapshot-beat-within-bar [snapshot]
  "Returns the beat number within the snapshot relative to the start
  of the bar: The down beat is 1, and the range goes up to the value
  returned by [[metro-bpb]] for the metronome.")

  (snapshot-beat-within-phrase [snapshot]
  "Returns the beat number within the snapshot relative to the start
  of the phrase: The first beat is 1, and the range goes up to the
  values returned by
  [[metro-bpb]] times [[metro-bpp]] for the metronome.")

  (snapshot-down-beat? [snapshot]
  "True if the current beat at the time of the snapshot was the first
  beat in its bar.")

  (snapshot-phrase-phase [snapshot] [snapshot phrase-ratio]
  "Determine the metronome's phase at the time of the snapshot with
  respect to a multiple or fraction of phrases. Calling this with a
  `phrase-ratio` of 1 (the default if not provided) is equivalent
  to [[metro-phrase-phase]], 1/2 oscillates twice as fast as 1, 3/4
  oscillates 4 times every three bars... Phases range from [0-1).")
  (snapshot-bar-within-phrase [snapshot]
  "Returns the bar number within the snapshot relative to the start of
  the phrase: Ranges from 1 to the value returned by [[metro-bpp]] for
  the metronome.")

  (snapshot-phrase-start? [snapshot]
  "True if the current beat at the time of the snapshot wass the first
  beat in its phrase.")

  (snapshot-marker [snapshot]
  "Returns the time represented by the snapshot as
  `\"phrase.bar.beat`"))))

;; Rhythm

;; * a resting heart rate is 60-80 bpm
;; * around 150 induces an excited state

;; A rhythm system should let us refer to time in terms of rhythmic units
;; like beat, bar, measure, and it should convert these units to real time
;; units (ms) based on the current BPM and signature settings.

(defn beat-ms
  "Convert `b` beats to milliseconds at the given `bpm`."
  [b bpm] (* (/ 60000 bpm) b))

(defn marker-number
  "Helper function to calculate the beat, bar, or phrase number in
  effect at a given `instant` (in milliseconds), given a starting
  point (`start`, also in milliseconds), and the `interval` (also in
  milliseconds) between beats, bars, or phrases."
  [instant start interval]
  (inc (long (/ (- instant start) interval))))

(defn marker-phase
  "Helper function to calculate the beat, bar, or phrase phase at a
  given `instant` (in millseconds), given a `start` time (also in
  milliseconds) and `interval` (in milliseconds) between beats, bars,
  or phrases."
  [instant start interval]
  (let [marker-ratio (/ (- instant start) interval)]
    (- (double marker-ratio) (long marker-ratio))))

(defn enhanced-phase
  "Calculate a phase with respect to multiples or fractions of a
  marker (beat, bar, or phrase), given the `phase` with respect to
  that marker, the number of that `marker`, and the desired ratio. A
  `desired-ratio` of 1 returns the phase unchanged; 1/2 oscillates
  twice as fast, 3/4 oscillates 4 times every three markers..."
  [marker phase desired-ratio]
  (let [r (rationalize desired-ratio)
          numerator (if (ratio? r) (numerator r) r)
          denominator (if (ratio? r) (denominator r) 1)
          base-phase (if (> numerator 1)
                       (/ (+ (mod (dec marker) numerator) phase) numerator)
                       phase)
          adjusted-phase (* base-phase denominator)]
      (- (double adjusted-phase) (long adjusted-phase))))

(defrecord MetronomeSnapshot [start bpm bpb bpp instant beat bar phrase beat-phase bar-phase phrase-phase]
  ISnapshot

  (snapshot-beat-phase [snapshot]
    (snapshot-beat-phase snapshot 1))

  (snapshot-beat-phase [snapshot beat-ratio]
    (enhanced-phase beat beat-phase beat-ratio))

  (snapshot-bar-phase [snapshot]
    (snapshot-bar-phase snapshot 1))

  (snapshot-bar-phase [snapshot bar-ratio]
    (enhanced-phase bar bar-phase bar-ratio))

  (snapshot-beat-within-bar [snapshot]
    (let [beat-size (/ 1 bpb)]
      (inc (int (floor (/ (snapshot-bar-phase snapshot 1) beat-size))))))

  (snapshot-beat-within-phrase [snapshot]
    (let [beat-size (/ 1 bpb bpp)]
      (inc (int (floor (/ (snapshot-phrase-phase snapshot 1) beat-size))))))

  (snapshot-down-beat? [snapshot]
    (let [beat-size (/ 1 bpb)]
      (zero? (floor (/ (snapshot-bar-phase snapshot 1) beat-size)))))

  (snapshot-phrase-phase [snapshot phrase-ratio]
    (enhanced-phase phrase phrase-phase phrase-ratio))

  (snapshot-bar-within-phrase [snapshot]
    (let [phrase-size (/ 1 bpp)]
      (inc (int (floor (/ (snapshot-phrase-phase snapshot 1) phrase-size))))))

  (snapshot-phrase-start? [snapshot]
    (let [phrase-size (/ 1 bpp)]
      (zero? (floor (/ (snapshot-phrase-phase snapshot 1) phrase-size)))))

  (snapshot-marker [snapshot]
    (str (:phrase snapshot) "." (snapshot-bar-within-phrase snapshot) "." (snapshot-beat-within-bar snapshot))))

(defn normalize-phase
  "Makes sure a phase value is in the range [0.0,1.0)"
  [phase]
  (if (neg? phase)
    (inc (- phase (long phase)))
    (- phase (long phase))))

(defrecord Metronome [start bpm bpb bpp]
  IMetronome

  (metro-start [metro] @start)

  (metro-start [metro start-beat]
    (dosync
     (ensure bpm)
     (let [new-start (round (- (now) (* (dec start-beat) (metro-tick metro))))]
       (ref-set start new-start))))

  (metro-bar-start [metro start-bar]
    (dosync
     (ensure bpm)
     (ensure bpb)
     (let [phase (metro-beat-phase metro)
           shift (* (metro-tick metro) (if (> phase 0.5) (dec phase) phase))
           new-bar-start (round (- (now) shift (* (dec start-bar) (metro-tock metro))))]
       (ref-set start new-bar-start))))

  (metro-phrase-start [metro start-phrase]
    (dosync
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (let [phase (metro-beat-phase metro)
           shift (* (metro-tick metro) (if (> phase 0.5) (dec phase) phase))
           new-phrase-start (round (- (now) shift (* (dec start-phrase) (metro-ding metro))))]
       (ref-set start new-phrase-start))))

  (metro-adjust [metro ms]
    (dosync
     (alter start + ms)))

  (metro-tick [metro] (beat-ms 1 @bpm))

  (metro-tock [metro] (dosync
                       (ensure bpm)
                       (ensure bpb)
                       (beat-ms @bpb @bpm)))

  (metro-ding [metro] (dosync
                       (ensure bpm)
                       (ensure bpb)
                       (ensure bpp)
                       (beat-ms (* @bpb @bpp) @bpm)))

  (metro-beat [metro] (dosync
                       (ensure start)
                       (ensure bpm)
                       (marker-number (now) @start (metro-tick metro))))

  (metro-beat [metro b] (dosync
                         (ensure start)
                         (ensure bpm)
                         (+ (* b (metro-tick metro)) @start)))

  (metro-beat-phase [metro]
    (dosync
     (ensure start)
     (ensure bpm)
     (marker-phase (now) @start (metro-tick metro))))

  (metro-beat-phase [metro phase]
    (dosync
     (ensure bpm)
     (let [delta (- (normalize-phase phase) (metro-beat-phase metro))
           shift (round (* (metro-tick metro) (if (> delta 0.5) (dec delta)
                                                  (if (< delta -0.5) (inc delta) delta))))]
       (alter start - shift))))

  (metro-bar [metro]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (marker-number (now) @start (metro-tock metro))))

  (metro-bar [metro b]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (+ (* b (metro-tock metro)) @start)))

  (metro-bar-phase [metro]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (marker-phase (now) @start (metro-tock metro))))

  (metro-bar-phase [metro phase]
    (dosync
     (ensure bpm)
     (ensure bpb)
     (let [delta (- (normalize-phase phase) (metro-bar-phase metro))
           shift (round (* (metro-tock metro) (if (> delta 0.5) (dec delta)
                                                  (if (< delta -0.5) (inc delta) delta))))]
       (alter start - shift))))

  (metro-phrase [metro]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (marker-number (now) @start (metro-ding metro))))

  (metro-phrase [metro p]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (+ (* p (metro-ding metro)) @start)))

  (metro-phrase-phase [metro]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (marker-phase (now) @start (metro-ding metro))))

  (metro-phrase-phase [metro phase]
    (dosync
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (let [delta (- (normalize-phase phase) (metro-phrase-phase metro))
           shift (round (* (metro-ding metro) (if (> delta 0.5) (dec delta)
                                                  (if (< delta -0.5) (inc delta) delta))))]
       (alter start - shift))))

  (metro-bpm [metro] @bpm)

  (metro-bpm [metro new-bpm]
    (dosync
     (let [cur-beat (metro-beat metro)
           new-tick (beat-ms 1 new-bpm)
           new-start (round (- (metro-beat metro cur-beat) (* new-tick cur-beat)))]
       (ref-set start new-start)
       (ref-set bpm new-bpm)))
    [:bpm new-bpm])

  (metro-bpb [metro] @bpb)

  (metro-bpb [metro new-bpb]
    (dosync
     (ensure bpm)
     (let [cur-bar (metro-bar metro)
           new-tock (beat-ms new-bpb @bpm)
           new-start (- (now) (* new-tock (dec cur-bar)))
           phase (metro-beat-phase metro)  ; Preserve beat phase
           shift (* (metro-tick metro) phase)]
       (ref-set start (round (- new-start shift)))
       (ref-set bpb new-bpb))))

  (metro-bpp [metro] @bpp)

  (metro-bpp [metro new-bpp]
    (dosync
     (ensure bpm)
     (ensure bpb)
     (let [cur-phrase (metro-phrase metro)
           new-ding (beat-ms (* @bpb new-bpp) @bpm)
           new-start (- (now) (* new-ding (dec cur-phrase)))
           phase (metro-bar-phase metro)  ; Preserve beat & bar phase
           shift (* (metro-tock metro) phase)]
       (ref-set start (round (- new-start shift)))
       (ref-set bpp new-bpp))))

  (metro-snapshot [metro]
    (metro-snapshot metro (now)))

  (metro-snapshot [metro instant]
    (dosync
     (ensure start)
     (ensure bpm)
     (ensure bpb)
     (ensure bpp)
     (let [beat (marker-number instant @start (metro-tick metro))
           bar (marker-number instant @start (metro-tock metro))
           phrase (marker-number instant @start (metro-ding metro))
           beat-phase (marker-phase instant @start (metro-tick metro))
           bar-phase (marker-phase instant @start (metro-tock metro))
           phrase-phase (marker-phase instant @start (metro-ding metro))]
       (MetronomeSnapshot. @start @bpm @bpb @bpp instant beat bar phrase beat-phase bar-phase phrase-phase))))

  (metro-marker [metro]
    (snapshot-marker (metro-snapshot metro)))

  (metro-add-bpm-watch [metro key f]
    (add-watch bpm key f))

  (metro-remove-bpm-watch [metro key]
    (remove-watch bpm key)))

(defn metronome
  "A metronome is a beat management tool. Tell it what BPM you want,
  and it will compute beat, bar, and phrase timestamps accordingly.
  See the [[IMetronome]] interface for full details."
  [bpm & {:keys [bpb bpp] :or {bpb 4 bpp 8}}]
  (let [start (ref (now))
        bpm (ref bpm)
        bpb (ref bpb)
        bpp (ref bpp)]
    (Metronome. start bpm bpb bpp)))
