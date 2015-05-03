(ns afterglow.rhythm
  "Functions to help work with musical time."
  {:author "Jeff Rose, James Elliott",
   :original
   "https://github.com/overtone/overtone/blob/master/src/overtone/music/rhythm.clj"}
  (:require [overtone.at-at :refer [now]]
            [clojure.math.numeric-tower :refer [round]]))

(defonce ^{:private true}
  _PROTOCOLS_
  (defprotocol IMetronome
    (metro-start [metro] [metro start-beat]
      "Returns the start time of the metronome. Also restarts the metronome at
     'start-beat' if given.")
    (metro-bar-start [metro] [metro start-bar]
      "Returns the bar-start time of the metronome. Also restarts the metronome at
     'start-bar' if given.")
    (metro-tick [metro]
      "Returns the duration of one metronome 'tick' in milleseconds.")
    (metro-tock [metro]
      "Returns the duration of one bar in milliseconds.")
    (metro-beat [metro] [metro beat]
      "Returns the next beat number or the timestamp (in milliseconds) of the
     given beat.")
    (metro-beat-phase [metro] [metro phase]
      "Returns the distance traveled into the current beat [0.0, 1.0), or
       adjusts the phase to match the one supplied.")
    (metro-bar [metro] [metro  bar]
      "Returns the next bar number or the timestamp (in milliseconds) of the
     given bar")
    (metro-bar-phase [metro] [metro phase]
      "Returns the distance traveled into the current bar [0.0, 1.0), or
       adjusts the phase to match the one supplied.")
    (metro-bpb [metro] [metro new-bpb]
      "Get the current beats per bar or change it to new-bpb")
    (metro-bpm [metro] [metro new-bpm]
      "Get the current bpm or change the bpm to 'new-bpm'.")
    (metro-snapshot [metro]
      "Take a snapshot of the current beat, bar, and phase state.")))


                                        ; Rhythm

                                        ; * a resting heart rate is 60-80 bpm
                                        ; * around 150 induces an excited state


                                        ; A rhythm system should let us refer to time in terms of rhythmic units like beat, bar, measure,
                                        ; and it should convert these units to real time units (ms) based on the current BPM and signature settings.

(defn beat-ms
  "Convert 'b' beats to milliseconds at the given 'bpm'."
  [b bpm] (* (/ 60000.0 bpm) b))

                                        ;(defn bar-ms
                                        ;  "Convert b bars to milliseconds at the current bpm."
                                        ;  ([] (bar 1))
                                        ;  ([b] (* (bar 1) (first @*signature) b)))

(defn- marker-number
  "Helper function to calculate the beat or bar number in effect at a given moment, given a
  starting point and beat or bar interval."
  [instant start interval]
  (inc (long (/ (- instant start) interval))))

(defn- marker-phase
  "Helper function to calculate the beat or bar phase at a given moment, given a
  starting point and beat interval."
  [instant start interval]
  (let [marker-ratio (/ (- instant start) interval)]
    (- (double marker-ratio) (long marker-ratio))))

;; Snapshot to support a series of beat and phase calculations with respect to a given instant in
;; time. Used by Afterglow so that all phase computations run when updating a frame of DMX data have
;; a consistent sense of when they are being run, to avoid, for example, half the lights acting as
;; if they are at the very end of a beat while the rest are at the beginning of the next beat, due
;; to a fluke in timing as their evaluation occurs over time. These also extend the notions of beat
;; phase to enable oscillators with frequencies that are fractions or multiples of a beat.
(defprotocol ISnapshot
  (snapshot-beat-phase [snapshot beat-ratio]
    "Determine the phase with respect to a multiple or fraction of beats.
Calling this with a beat-ratio of 1 is equivalent to beat-phase,
a beat-ratio of bpb is equivalent to bar-phase (unless the bar
start has been moved away from the beat start), 1/2 oscillates
twice as fast as 1, 3/4 oscillates 4 times every three beats...")
  (snapshot-bar-phase [snapshot bar-ratio]
    "Determine the phase with respect to a multiple or fraction of bars.
Calling this with a beat-ratio of 1 is equivalent to bar-phase,
1/2 oscillates twice as fast as 1, 3/4 oscillates 4 times every
three bars..."))

(defn- enhanced-phase
  "Calculate a phase with respect to multiples or fractions of a
marker interval (beat or bar), given the phase with respect to
that marker, the number of that marker, and the desired ratio.
A ratio of 1 returns the phase unchanged; 1/2 oscillates twice
as fast, 3/4 oscillates 4 times every three markers..."
  [marker phase desired-ratio]
  (let [r (rationalize desired-ratio)
          numerator (if (ratio? r) (numerator r) r)
          denominator (if (ratio? r) (denominator r) 1)
          base-phase (if (> numerator 1)
                       (/ (+ (mod (dec marker) numerator) phase) numerator)
                       phase)
          adjusted-phase (* base-phase denominator)]
      (- (double adjusted-phase) (long adjusted-phase))))

(defrecord MetronomeSnapshot [start bar-start bpm bpb instant beat bar beat-phase bar-phase]
  ISnapshot
  (snapshot-beat-phase [snapshot beat-ratio]
    (enhanced-phase beat beat-phase beat-ratio))
  (snapshot-bar-phase [snapshot bar-ratio]
    (enhanced-phase bar bar-phase bar-ratio)))

(deftype Metronome [start bar-start bpm bpb]

  IMetronome
  (metro-start [metro] @start)
  (metro-start [metro start-beat]
    (let [new-start (- (now) (* start-beat (metro-tick metro)))]
      (reset! start new-start)
      new-start))
  (metro-bar-start [metro] @bar-start)
  (metro-bar-start [metro start-bar]
    (let [new-bar-start (- (now) (* start-bar (metro-tock metro)))]
      (reset! bar-start new-bar-start)
      new-bar-start))
  (metro-tick  [metro] (beat-ms 1 @bpm))
  (metro-tock  [metro] (beat-ms @bpb @bpm))
  (metro-beat  [metro] (marker-number (now) @start (metro-tick metro)))
  (metro-beat  [metro b] (+ (* b (metro-tick metro)) @start))
  (metro-beat-phase [metro]
    (marker-phase (now) @start (metro-tick metro)))
  (metro-beat-phase [metro phase]
    (let [delta (- phase (metro-beat-phase metro))
          shift (round (* (metro-tick metro) (if (> delta 0.5) (- delta 1)
                                                 (if (< delta -0.5) (+ delta 1) delta))))]
      (swap! start - shift)
      (swap! bar-start - shift)))
  (metro-bar   [metro] (marker-number (now) @bar-start (metro-tock metro)))
  (metro-bar   [metro b] (+ (* b (metro-tock metro)) @bar-start))
  (metro-bar-phase [metro]
    (marker-phase (now) @bar-start (metro-tock metro)))
  (metro-bar-phase [metro phase]
    (let [delta (- phase (metro-bar-phase metro))
          shift (round (* (metro-tock metro) (if (> delta 0.5) (- delta 1)
                                                 (if (< delta -0.5) (+ delta 1) delta))))]
      (swap! start - shift)
      (swap! bar-start - shift)))
  (metro-bpm   [metro] @bpm)
  (metro-bpm   [metro new-bpm]
    (let [cur-beat      (metro-beat metro)
          cur-bar       (metro-bar metro)
          new-tick      (beat-ms 1 new-bpm)
          new-tock      (* @bpb new-tick)
          new-start     (- (metro-beat metro cur-beat) (* new-tick cur-beat))
          new-bar-start (- (metro-bar metro cur-bar) (* new-tock cur-bar))]
      (reset! start new-start)
      (reset! bar-start new-bar-start)
      (reset! bpm new-bpm))
    [:bpm new-bpm])
  (metro-bpb   [metro] @bpb)
  (metro-bpb   [metro new-bpb]
    (let [cur-bar       (metro-bar metro)
          new-tock      (beat-ms new-bpb @bpm)
          new-bar-start (- (metro-bar metro cur-bar) (* new-tock cur-bar))]
      (reset! bar-start new-bar-start)
      (reset! bpb new-bpb)))
  (metro-snapshot [metro]
    (let [instant    (now)
          beat       (marker-number instant @start (metro-tick metro))
          bar        (marker-number instant @bar-start (metro-tock metro))
          beat-phase (marker-phase instant @start (metro-tick metro))
          bar-phase  (marker-phase instant @bar-start (metro-tock metro))]
      (MetronomeSnapshot. @start @bar-start @bpm @bpb instant beat bar beat-phase bar-phase)))

  clojure.lang.ILookup
  (valAt [this key] (.valAt this key nil))
  (valAt [this key not-found]
    (cond (= key :start) @start
          (= key :bpm) @bpm
          :else not-found))

  clojure.lang.IFn
  (invoke [this] (metro-beat this))
  (invoke [this arg]
    (cond
      (number? arg) (metro-beat this arg)
      (= :bpm arg) (metro-bpm this) ;; (bpm this) fails.
      :else (throw (Exception. (str "Unsupported metronome arg: " arg)))))
  (invoke [this _ new-bpm] (metro-bpm this new-bpm)))

(defn metronome
  "A metronome is a beat management function.  Tell it what BPM you want,
  and it will output beat timestamps accordingly.  Call the returned function
  with no arguments to get the next beat number, or pass it a beat number
  to get the timestamp to play a note at that beat.

  Metronome also works with bars. Set the number of beats per bar using
  metro-bpb (defaults to 4). metro-bar returns a timestamp that can be used
  to play a note relative to a specified bar.

  (def m (metronome 128))
  (m)          ; => <next beat number>
  (m 200)      ; => <timestamp of beat 200>
  (m :bpm)     ; => return the current bpm val
  (m :bpm 140) ; => set bpm to 140"
  [bpm]
  (let [start (atom (now))
        bar-start (atom @start)
        bpm   (atom bpm)
        bpb   (atom 4)]
    (Metronome. start bar-start bpm bpb)))

                                        ;== Grooves
                                        ;
                                        ; A groove represents a pattern of velocities and timing modifications that is
                                        ; applied to a sequence of notes to adjust the feel.
                                        ;
                                        ; * swing
                                        ; * jazz groove, latin groove
                                        ; * techno grooves (hard on beat one)
                                        ; * make something more driving, or more layed back...

