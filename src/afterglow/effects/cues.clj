(ns afterglow.effects.cues
  "Cues provide a user interface for controlling effects, by
  associating them with cells in a cue grid so they can be easily
  triggered and monitored, either through a physical grid controller,
  or the web show control interface. They also provide a way of
  binding cue variables to effect parameters, which can enable
  controller interfaces to adjust them, and of tying those variables
  to velocity and pressure sensitivity on physical grid controllers
  which have such capabilities."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects :as fx]
            [afterglow.effects.channel :as chan]
            [afterglow.effects.params :as params]
            [afterglow.midi :as midi]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show]]
            [afterglow.util :as util]
            [taoensso.timbre :as timbre])
  (:import (afterglow.effects Effect)))

(defn cue
  "Creates a cue for managing in a cue grid. `show-key` will be used
  as the effect keyword when the cue is triggered to add it to a show,
  ending any existing effect that was using that key. `effect-fn` is a
  function that will be called to obtain the effect to be started and
  monitored when this cue is triggered. It will be passed a map
  allowing lookup of any temporary variables introduced by the
  cue (see the `:variables` parameter below).

  If supplied, `:short-name` identifies a compact, user-oriented name
  to be displayed in the web interface or controller display (if it
  has one) to help identify the cue, which can be helpful if the name
  of the underlying effect is ambiguous or overly long.

 `:color` requests that the web interface and control surfaces draw
  the cue using the specified color rather than the default white to
  help the user identify it. This is only a request, as not all
  control surfaces support all (or any) colors. The color is passed
  to [[interpret-color]], so it can be specified in a variety of ways.
  If omitted, a default color of white is used.

  `:color-fn`, if present, is a function that will be called to
  determine the current color to be used in user interfaces for cues
  that have variable colors. This function will be called with the
  cue, the currently-running effect launched by that cue (if any), as
  returned by [[show/find-effect]], the show, and metronome snapshot
  representing the time at which the user interface is being updated.
  If it returns a value, that will be used rather than the static
  color in `:color`. If it returns `nil`, `:color` is used instead. As
  with `:color`, the presence of this function is only a request, not
  all user interfaces support dynamic cue colors.

  `:end-keys` introduces a sequence of keywords identifying other
  effects which should be ended whenever this one is started. This
  allows a set of grid cues to be set up as mutually exclusive, even
  if they use different keywords within the show for other reasons.

  `:priority` assigns a sorting priority to the effect. If not
  assigned, a default priority of zero is used. The effect will be
  added to the show after any effects of equal or lower priority, but
  before any with higher priority. Effects are run in order, and later
  effects can override earlier ones if they are trying to affect the
  same things, so a higher priority and more recent effect wins.

  If `:held` is passed with a true value, then the cue will be active
  only as long as the corresponding pad or button on the control
  surface is held down, for controllers which support this feature.
  This can be useful for very intense cues like strobes. Show
  operators can override the `:held` flag by holding down the `Shift`
  key when triggering the cue on interfaces which have `Shift` keys,
  like the web interface and Ableton Push.

  `:variables` introduces a list of variable bindings for the cue,
  each of which is a map with the following keys:

  * `:key` identifies the variable that is being bound by the cue (for easy
    adjustment in the user interface while the cue is running). If it is
    a string rather than a keyword, it identifies a temporary variable
    which need exist only for the duration of the cue. The actual name
    will be assigned when the cue is activated. In order for `effect-fn`
    to be able to access the correct variable, it is passed a map
    whose keys are keywords made from the string `:key` values supplied
    in the `:variables` list, and whose values are the actual keyword
    of the corresponding temporary show variable created for the cue.

  * `:name`, if present, gives the name by which the variable should be
    displayed in the user interface for adjusting it. If not specified,
    the name of `:key` is used.

  * `:short-name`, if present, is a shorter version of the name which
    can be used in interfaces with limited space.

  * `:min` specifies the minimum value to which the variable can be set.
    If not supplied, zero is assumed.

  * `:max` specifies the maximum value to which the variable can be set.
    If not supplied, 100 is assumed.

  * `:start` specifies the value to assign to the variable at the
    start of the cue, if any. It can be a simple value, a keyword which
    will be replaced by the current value of a show variable by the same
    name when the cue begins, or a function which will be called to
    obtain a value for the variable.

  * `:type` identifies the type of the variable, to help formatting
    its display. Supported values are `:integer`, `:float`, and
    `:color`. Other types may be supported in the future. If omitted or
    unrecognized, `:float` is assumed.

  * `:centered` supplied with a true value requests that the gauge
    displayed when adjusting this variable's value be like a pan
    gauge, showing deviation from a central value, for interfaces
    which support this.

  * `:resolution` specifies the smallest amount by which the variable
     will be incremented or decremented when the user adjusts it using
     a continuous encoder on a physical controller. If not specified the
     resolution is up to the controller, but 1/256 of the range from
     `:min` to `:max` is a recommended default implementation, since that
     allows access to the full DMX parameter space for channel-oriented
     values.

  * `:velocity` accompanied by a true value enables the variable to be
     set by strike pressure and adjusted by aftertouch pressure while the
     pad which launched the cue is held down on pressure-sensitive
     controllers.

  * `:velocity-min` and `:velocity-max` specify the range into
     which MIDI velocity and aftertouch values will be mapped, if they are present.
     Otherwise the standard `:min` and `:max` values will be used."
  [show-key effect-fn & {:keys [variables short-name color color-fn end-keys priority held]
                         :or {short-name (:name (show/get-cue-effect effect-fn variables)) color :white priority 0}}]
  {:pre [(some? show-key) (fn? effect-fn) (satisfies? fx/IEffect (show/get-cue-effect effect-fn variables))
         (or (nil? color-fn) (fn? color-fn))]}
  (merge {:name (name short-name)
          :key (keyword show-key)
          :effect effect-fn
          :priority priority
          :held held
          :color (params/interpret-color (if (keyword? color) (name color) color))
          :end-keys (vec end-keys)
          :variables (vec variables)}
         (when color-fn {:color-fn color-fn})))

(defn function-cue
  "Creates a cue that applies the specified function to the supplied
  fixtures or heads. Automatically adds a temporary variable for
  adjusting the function value if the function is not fixed over its
  range. `show-key` will be used as the effect keyword when the cue is
  triggered to add it to a show, ending any existing effect that was
  using that key. `function` identifies the fixture function to be
  activated by this cue, and `fixtures` lists the fixtures and heads
  can which should be affected (though only fixtures and heads which
  implement the specified function will actually participate).

  Passing a value for `:effect-name` sets the name for the effect
  created by this cue. If none is provided, the name of the function
  is used.

  If supplied, `:short-name` identifies a compact, user-oriented name
  to be displayed in the web interface or controller display (if it
  has one) to help identify the cue, which can be helpful if the name
  of the underlying effect is ambiguous or overly long.

  `:color` requests that the web interface and control surfaces draw
  the cue using the specified color rather than the default white to
  help the user identify it. This is only a request, as not all
  control surfaces support all (or any) colors. The color is passed
  to [[interpret-color]], so it can be specified in a variety of ways.

  `:level` can be used to set the initial level for functions which
  have a variable effect over their range. Such functions will be
  automatically assigned a variable parameter which can be used to
  adjust the level while the cue runs, and which will be visible in
  the web interface and on controllers which support adjusting cue
  parameters. Function levels are expressed as a percentage, which is
  mapped onto the range of DMX values which are assigned to the
  function in the fixture definition. Functions with no variable
  effect will ignore `:level` and will not be assigned variables for
  adjustment.

  If `:htp` is passed a true value, the created effect applies
  highest-takes-precedence (i.e. compares the value to the previous
  assignment for the channels implementing the function, and lets the
  highest value remain). See [[channel/function-effect]] for more
  details about the underlying effect.

  `:end-keys` introduces a sequence of keywords identifying other
  effects which should be ended whenever this one is started. This
  allows a set of grid cues to be set up as mutually exclusive, even
  if they use different keywords within the show for other reasons.

  `:priority` assigns a sorting priority to the effect. If not
  assigned, a default priority of zero is used. The effect will be
  added to the show after any effects of equal or lower priority, but
  before any with higher priority. Effects are run in order, and later
  effects can override earlier ones if they are trying to affect the
  same things, so a higher priority and more recent effect wins.

  If `:held` is passed with a true value, then the cue will be active
  only as long as the corresponding pad or button on the control
  surface is held down, for controllers which support this feature.
  This can be useful for very intense cues like strobes. Show
  operators can override the `:held` flag by holding down the `Shift`
  key when triggering the cue on interfaces which have `Shift` keys,
  like the web interface and Ableton Push.

  If the function being controlled has a variable effect, and thus a
  cue variable is being introduced to adjust it, `:velocity`,
  `:velocity-min`, and `:velocity-max` will be used when creating
  that variable, allowing it to be controlled by strike and aftertouch
  pressure on control surfaces which support that feature, as described
  in [[cue]]."
  [show-key function fixtures & {:keys [level htp? effect-name short-name color color-fn end-keys priority held
                                        velocity velocity-min velocity-max]
                                 :or {color :white level 0 priority 0}}]
  (let [function (keyword function)
        heads (chan/find-heads-with-function function fixtures)
        specs (map second
                   (map function
                        (map :function-map
                             (afterglow.effects.channel/find-heads-with-function function (show/all-fixtures)))))
        effect-name (or effect-name (:label (first specs)) (name function))
        short-name (or short-name effect-name)
        variable? (some #(= (:range %) :variable) specs)]
    (if variable?
      ;; Introduce a variable for conveniently adjusting the function level
      (let [label (or (:var-label (first specs)) "Level")]
        (cue show-key (fn [var-map] (chan/function-effect effect-name function (params/bind-keyword-param
                                                                                (:level var-map level) Number level)
                                                          fixtures :htp? htp?))
             :short-name short-name
             :color color
             :color-fn color-fn
             :end-keys end-keys
             :priority priority
             :held held
             :variables [(merge {:key "level" :min 0 :max 100 :start level :name label}
                                (when velocity {:velocity velocity})
                                (when velocity-min {:velocity-max velocity-min})
                                (when velocity-max {:velocity-max velocity-max}))]))
      ;; It's a fixed function, no variable required
      (cue show-key (fn [_] (chan/function-effect effect-name function (params/bind-keyword-param level Number 0)
                                                  fixtures :htp? htp?))
           :short-name short-name
           :color color
           :color-fn color-fn
           :end-keys end-keys
           :priority priority
           :held held))))

;; TODO: A compound function cue which takes a vector of functions
;;       and htp/velocity/level/var-label-overrides and builds a
;;       single effect.

(defn compound-cues-effect
  "Creates an effect which launches the specified cues from the grid,
  stays running as long as they are, and ends them all when it is
  asked to end. Takes a list of three-element tuples identifying the x
  and y coordinates within the cue grid of the subordinate cues to
  launch, and an optional map of cue variable overrides, which can be
  used to change the initial values of any temporary variables
  introduced by that cue."
  [name show cues]
  (let [running (filter identity (for [[x y overrides] cues]
                                   (with-show show
                                     (when-let [id (show/add-effect-from-cue-grid! x y :var-overrides overrides)]
                                       [id (:key (controllers/cue-at (:cue-grid show) x y))]))))]
    (Effect. name
             (fn [show snapshot]  ; We are still running if any of the nested effects we launched are.
               (with-show show
                 (some (fn [[id k]]
                         (when-let [effect (show/find-effect k)]
                           (= (:id effect) id)))
                       running)))
             (fn [show snapshot] nil)  ; We do not assign any values; only the nested effects do.
             (fn [show snapshot]  ; Tell all our launched effects to end.
               (with-show show
                 (doseq [[id k] running]
                   (show/end-effect! k :when-id id)))))))


(defn code-cue
  "Creates a cue that runs an arbitrary chunk of Clojure code instead
  of starting an effect, for example to reset the metronome if the
  controller mapping doesn't have a dedicated button for doing that.

  The `code` argument must be a function that takes two arguments. It
  will be called with the show and metronome snapshot when the cue is
  started, and should return immediately, because this takes place on
  the effect rendering pipeline, so any lengthy operations must be
  performed on another thread. The effect won't do anything else after
  calling `code` once, but the cue is configured to keep it
  \"running\" until you let go of that grid controller pad, so you can
  see visual feedback that it ran.

  The `label` argument is a string which is used to identify the cue
  in the cue grid.

  The optional keyword argument `:color` requests that the web
  interface and control surfaces draw the cue using the specified
  color rather than the default white to help the user identify it.
  This is only a request, as not all control surfaces support all (or
  any) colors. The color is passed to [[interpret-color]], so it can
  be specified in a variety of ways."
  [code label & {:keys [color] :or {color :white}}]
  {:pre [(fn? code) (string? label)]}
  (cue ::code-cue (fn [_] (fx/code code)) :short-name label :color color :held true))

(defn find-cue-variable-keyword
  "Finds the keyword by which a cue variable is accessed; it may be
  directly bound to a show variable, or may be a temporary variable
  introduced for the cue, whose name needs to be looked up in the
  running effect's variable map. If a temporary variable, the id of
  the effect which is expected to be running for the cue must be
  passed with `:when-id`, and this id must match the id of the actual
  effect currently running under that key, or `nil` will be returned.

  If a show is passed with the `:show` optional keyword argument, the
  effect is looked up in that show. Otherwise, it is looked up in the
  default show."
  [cue var-spec & {:keys [show when-id]}]
  (if show
    (with-show show
      (find-cue-variable-keyword cue var-spec :when-id when-id))
    (let [effect (show/find-effect (:key cue))]
      (if (keyword? (:key var-spec))
        (when (or (nil? when-id) (= when-id (:id effect)))
          (:key var-spec))
        (when (and (some? effect) (= when-id (:id effect)))
          ((keyword (:key var-spec)) (:variables effect)))))))

(defn get-cue-variable
  "Finds the current value of the supplied cue variable, which may be
  directly bound to a show variable, or may be a temporary variable
  introduced for the cue, whose name needs to be looked up in the
  running effect's variable map. If a temporary variable, the id of
  the effect which is expected to be running for the cue must be
  passed with `:when-id`, and this id must match the id of the actual
  effect currently running under that key, or `nil` will be returned.

  Permanent variables associated with the cue can always be retrieved by
  omitting `:with-id`. If you want to only get the variable when a
  particular effect is running, however, you can do so by passing in
  that effect's id with `:with-id`, and the same restriction will then
  be applied as is for temporary variables.

  If a show is passed with the `:show` optional keyword argument, the
  effect is looked up in that show. Otherwise, it is looked up in the
  default show."
  [cue var-spec & {:keys [show when-id]}]
  (if show
    (with-show show
      (get-cue-variable cue var-spec :when-id when-id))
    (when-let [k (find-cue-variable-keyword cue var-spec :when-id when-id)]
      (util/normalize-cue-variable-value var-spec (show/get-variable k)))))

(defn set-cue-variable!
  "Sets the current value of the supplied cue variable, which may be
  directly bound to a show variable, or may be a temporary variable
  introduced for the cue, whose name needs to be looked up in the
  running effect's variable map. If a temporary variable, the id of
  the effect which is expected to be running for the cue must be
  passed with `:when-id`, and this id must match the id of the actual
  effect currently running under that key, or nothing will be set.

  Permanent variables associated with the cue can always be set by
  omitting `:with-id`. If you want to only affect the variable when a
  particular effect is running, however, you can do so by passing in
  that effect's id with `:with-id`, and the same restriction will then
  be applied as is for temporary variables.

  If a show is passed with the `:show` optional keyword argument, the
  effect is looked up in that. Otherwise, it is looked up in the
  default show."
  [cue var-spec value & {:keys [show when-id]}]
  (if show
    (with-show show
      (set-cue-variable! cue var-spec value :when-id when-id))
    (when-let [k (find-cue-variable-keyword cue var-spec :when-id when-id)]
      (show/set-variable! k value))))

(defn snapshot-cue-variables
  "Returns a map containing the keys and current values of all
  variables used by the specified cue, which is suitable for saving via
  [[save-cue-vars!]]. The id of the effect currently running the cue
  must be passed as `when-id` to ensure that the cue is still running.
  If it is not running (or ends while the variable values are being
  looked up), `nil` is returned.

  If a show is passed with the `:show` optional keyword argument, the
  effect is looked up in that show. Otherwise, it is looked up in the
  default show."
  [cue when-id & {:keys [show]}]
  {:pre [(some? when-id)]}
  (if show
    (with-show show
      (snapshot-cue-variables cue when-id))
    (let [result (map (fn [v]
                        [(keyword (:key v)) (get-cue-variable cue v :when-id when-id)])
                      (:variables cue))]
      (when (= when-id (:id (show/find-effect (:key cue))))
        (into {} result)))))

(defn add-midi-to-cue-mapping
  "Cause the specified cue from the [[*show*]] cue grid to be
  triggered by receipt of the specified note (when `kind` is `:note`)
  or controller-change (when `kind` is `:control`) message with a
  non-zero velocity or control value. This allows generic MIDI
  controllers, which do not have enough pads or feedback capabilities
  to act as a full grid controller like the Ableton Push, to still
  provide a physical means of triggering cues.

  The device to be mapped is identified by `device-filter`. The first
  input port which matches using [[filter-devices]] will be used.

  The desired cue is identified by passing in its `x` and `y`
  coordinates within the show cue grid.

  Afterglow will attempt to provide feedback about the progress of the
  cue by sending note on/off or control-change values to the same
  controller when the cue starts and ends. The note velocities or
  control values used can be changed by passing in different values
  with `:feedback-on` and `:feedback-off`, and this behavior can be
  suppressed entirely by passing `false` with `:feedback-on`.

  If the controller is pressure-sensitive and you would like to have
  the velocity information passed on to any cue variables which are
  configured to respond to it, pass `true` with `:use-velocity`. This
  is the default assumed if `:use-velocity` is omitted, and also
  enables responsiveness to aftertouch (polyphonic key pressure)
  messages, which can adjust the cue variable for as long as you are
  holding down the key or pad, if your controller sends them (and
  `kind` is `:note`).

  If the controller is not pressure-sensitive, and you would like to
  have the cue react as if it was pressed with a particular velocity,
  you can pass a number from `0` to `127` with `:use-velocity`.
  Whenever the cue is activated, it will be activated with the
  specified velocity. If you pass `:use-velocity` with `false`, the
  cue will be activated with a default velocity. In any of these cases
  where `use-velocity` is not `true`, no attempt will be made to
  respond to aftertouch messages.

  Afterglow assumes the control is momentary, meaning it sends a note
  off (or control value of 0) as soon as it is released, and a second
  press will be used to end the cue unless the cue uses the `:held`
  modifier to indicate it should be ended when the button is released.
  If your controller does not have momentary buttons and already
  requires a second press to turn off the note or control value, pass
  `false` with `:momentary?` and Afterglow will always end cues when
  it receives a control value of 0, even if cues are not marked as
  `:held`.

  Returns the cue-triggering function which can be passed
  to [[remove-midi-to-cue-mapping]] if you ever want to stop the MIDI
  control or note from affecting the cue in the future."
  [device-filter channel kind note x y & {:keys [feedback-on feedback-off use-velocity momentary?]
                                             :or {feedback-on 127 feedback-off 0 use-velocity true momentary? true}}]
  {:pre [(some? *show*) (#{:control :note} kind) (some? device-filter) (integer? channel) (<= 0 channel 15)
         (integer? note) (<= 0 note 127) (integer? x) (<= 0 x) (integer? y) (<= 0 y)
         (or (not feedback-on) (and (integer? feedback-on) (<= 0 feedback-on 127)))
         (integer? feedback-off) (<= 0 feedback-off 127)
         (or (true? use-velocity) (false? use-velocity) (and (integer? use-velocity) (<= 0 use-velocity 127)))]}
  (let [show *show*  ; Bind so we can pass it to update functions running on another thread
        feedback-device (when feedback-on (midi/find-midi-out device-filter))
        our-id (atom nil)  ; Track when we have created an effect
        midi-handler (fn [msg]
                       (with-show show
                         ;; See if the cue exists and is running
                         (let [[cue active] (show/find-cue-grid-active-effect show x y)
                               velocity (:velocity msg)
                               aftertouch-handler (fn [msg]
                                                    (with-show show
                                                      (doseq [v (:variables cue)]
                                                        (when (:velocity v)
                                                          (set-cue-variable!
                                                           cue v (controllers/value-for-velocity v (:velocity msg))
                                                           :when-id @our-id)))))]
                           (when cue
                             (if (and (pos? velocity) (not= (:command msg) :note-off))
                               ;; Control or note has been pressed
                               (if (and active (not (:held cue)))
                                 (show/end-effect! (:key cue))
                                 (let [effective-velocity (if (true? use-velocity) velocity use-velocity)]
                                   (if use-velocity
                                     (reset! our-id (show/add-effect-from-cue-grid! x y :velocity effective-velocity))
                                     (reset! our-id (show/add-effect-from-cue-grid! x y)))
                                   (when (and (true? use-velocity) (= kind :note))
                                     (midi/add-aftertouch-mapping device-filter channel note aftertouch-handler))))
                               ;; Control has been released
                               (when (some? @our-id)
                                 (when (and (true? use-velocity) (= kind :note))
                                   (midi/remove-aftertouch-mapping device-filter channel note aftertouch-handler))
                                 (when (or (:held cue) (not momentary?))
                                   (show/end-effect! (:key cue) :when-id @our-id)
                                   (reset! our-id nil))))))))]
    (when feedback-device  ; Set up to give feedback as cue activation changes
      (controllers/add-cue-feedback! (:cue-grid show) x y feedback-device channel kind note
                                     :on feedback-on :off feedback-off)
      (let [[cue active] (show/find-cue-grid-active-effect show x y)]  ; Was already active, so reflect that
        (when active (case kind
                      :control (overtone.midi/midi-control feedback-device note feedback-on channel)
                      :note (overtone.midi/midi-note-on feedback-device note feedback-on channel)))))
    (case kind
      :control (midi/add-control-mapping device-filter channel note midi-handler)
      :note (midi/add-note-mapping device-filter channel note midi-handler))
    midi-handler))

(defn add-midi-control-to-cue-mapping
  "Deprecated in favor of the more accurately
  named [[add-midi-to-cue-mapping]]."
  {:deprecated "0.2.0"}
  [device-filter channel kind note x y & {:keys [feedback-on feedback-off use-velocity momentary?]
                                          :or {feedback-on 127 feedback-off 0 use-velocity true momentary? true}}]
  (add-midi-to-cue-mapping device-filter channel kind note x y :feedback-on feedback-on :feedback-off feedback-off
                           :use-velocity use-velocity :momentary? momentary?))

(defn remove-midi-to-cue-mapping
  "Stop triggering the specified cue from the [[*show*]] cue grid upon
  receipt of the specified note or controller-change message. The
  desired cue is identified by passing in its `x` and `y` coordinates
  within the show cue grid. `f` is the handler function that was
  returned by [[add-midi-to-cue-mapping]] when the mapping was
  established."
  [device-filter channel kind note x y f]
  {:pre [(some? *show*) (#{:control :note} kind) (some? device-filter) (integer? channel) (<= 0 channel 15)
         (integer? note) (<= 0 note 127) (integer? x) (<= 0 x) (integer? y) (<= 0 y) (fn? f)]}
  (let [feedback-device (first (midi/filter-devices device-filter (midi/open-outputs-if-needed!)))
        feedback (when feedback-device
                   (controllers/clear-cue-feedback! (:cue-grid *show*) x y feedback-device channel kind note))]
    (when feedback  ; We had been giving feedback, see if we need to turn it off
      (let [[cue active] (show/find-cue-grid-active-effect *show* x y)]
        (when active (case kind
                       :control (overtone.midi/midi-control feedback-device note (second feedback) channel)
                       :note (overtone.midi/midi-note-on feedback-device note (second feedback) channel))))))
  (case kind
    :control (midi/remove-control-mapping device-filter channel note f)
    :note (midi/remove-note-mapping device-filter channel note f)))

(defn remove-midi-control-to-cue-mapping
  "Deprecated in favor of the more accurately named
  [[remove-midi-to-cue-mapping]]."
  {:deprecated "0.2.0"}
  [device-filter channel kind note x y f]
  (remove-midi-to-cue-mapping device-filter channel kind note x y f))

(defn current-cue-color
  "Given a cue, an active effect map, and a metronome snapshot
  representing the moment for which the user interface is being
  rendered, return the color with which the cue should be rendered on
  a cue grid. If the cue is not currently running, `active` should be
  `nil`. Handles the delegation to the cue's dynamic color function if
  one has been assigned. Useful dynamic color functions are built
  by [[color-fn-from-cue-var]] and [[color-fn-from-param]]."
  [cue active show snapshot]
  (if-let [f (:color-fn cue)]
    (or (f cue active show snapshot) (:color cue))
    (:color cue)))

(defn color-fn-from-cue-var
  "Builds a dynamic cue color function which reports the color of a
  cue based on the content of a cue variable, given the cue variable
  map. If the cue variable is temporary, when the cue is not running,
  the function returns non-dynamic cue color. When running, or if the
  variable is not temporary, it looks up the value of the specified
  cue variable, and returns that. If the variable is `nil`, the
  non-dynamic cue color is returned."
  [var-spec]
  (fn [cue active show _]
    (if (or active (keyword? (:key var-spec)))
      (or (get-cue-variable cue var-spec :show show :when-id (:id active))
          (:color cue))
      (:color cue))))

(defn color-fn-from-param
  "Builds a dynamic cue color function which reports the color of a
  cue based on the value of a dynamic parameter. If the parameter
  evaluates to `nil`, the non-dynamic cue color is returned."
  [param]
  (params/validate-param-type param :com.evocomputing.colors/color)
  (fn [cue active show snapshot]
    (or (params/evaluate param show snapshot nil) (:color cue))))

