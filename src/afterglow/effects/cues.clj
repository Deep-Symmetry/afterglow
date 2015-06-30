(ns afterglow.effects.cues
  (:require [afterglow.controllers :as cues]
            [afterglow.effects.channel :as chan]
            [afterglow.effects.params :as params]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [taoensso.timbre :refer [info]])
  (:import (afterglow.effects Effect)))

(defn cue
  "Creates a cue for managing in a cue grid. `show-key` will be used
  as the effect keyword when the cue is triggered to add it to a show,
  ending any existing effect that was using that key. `effect` is a
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
  This can be useful for very intense cues like strobes.

  `:variables` introduces a list of variable bindings for the cue,
  each of which is a map with the following keys:

  * `:key` identifies the variable that is being bound by the cue (for easy
    adjustment in the user interface while the cue is running). If it is
    a string rather than a keyword, it identifies a temporary variable
    which should exist only for the duration of the cue. The actual name
    will be assigned when the cue is activated. In order for the effect
    function to be able to access the correct variable, it is passed a
    map whose keys are keywords made from the string `:key` values
    supplied in the `:variables` list, and whose values are the actual
    keyword of the corresponding temporary show variable created for the
    cue.

  * `:name`, if present, gives the name by which the variable should be
    displayed in the user interface for adjusting it. If not specified,
    the name of `:key` is used.

  * `:short-name`, if present, is a shorter version of the name which
    can be used in interfaces with limited space.

  * `:min` specifies the minimum value to which the variable can be set.
    If not supplied, zero is assumed.

  * `:max` specifies the maximum value to which the variable can be set.
    If not supplied, 100 is assumed.

  * `:start` specifies the value to assign to the variable at the start
    of the cue, if any.

  * `:type` identifies the type of the variable, to help formatting
    its display. Supported values are `:integer`, `:float`, possibly
    others in the future. If omitted or unrecognized, `:float` is
    assumed.

  * `:centered` supplied with a true value requests that the gauge
    displayed when adjusting this variable's value be like a pan
    gauge, showing deviation from a central value.

  * `:resolution` specifies the smallest amount by which the variable
     will be incremented or decremented when the user adjusts it for
     controllers with continuous encoders. If not specified the
     resolution is up to the controller, but 1/256 of the range from
     `:min` to `:max` is a recommended default implementation, since that
     allows access to the full DMX parameter space for channel-oriented
     values.

  * `:aftertouch` accompanied by a true value enables the variable to
  be adjusted by aftertouch values on pressure-sensitive controllers.

  * `:aftertouch-min` and `:aftertouch-max` specify the range into
    which MIDI aftertouch values will be mapped, if they are present.
    Otherwise the standard `:min` and `:max` values will be used."
  {:doc/format :markdown}
  [show-key effect & {:keys [short-name color end-keys priority held variables]
                      :or {short-name (:name (effect {})) color :white priority 0}}]
  {:pre [(some? show-key) (ifn? effect) (= (class (effect {})) Effect)]}
  {:name (name short-name)
   :key (keyword show-key)
   :effect effect
   :priority priority
   :held held
   :color (params/interpret-color (if (keyword? color) (name color) color))
   :end-keys (vec end-keys)
   :variables (vec variables)
   })

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
  is used. `:level` can be used to set the initial level for functions
  which have a variable effect over their range. Such functions will
  be automatically assigned a variable parameter which can be used to
  adjust the level while the cue runs, and which will be visible in
  the web interface and on controllers which support adjusting cue
  parameters. Functions with no variable effect will ignore `:level`
  and will not be assigned variables for adjustment.

  If `:htp` is passed a true value, the created effect applies
  highest-takes-precedence (i.e. compares the value to the previous
  assignment for the channels implementing the function, and lets the
  highest value remain). See [[channel/function-effect]] for more
  details about the underlying effect.

  If supplied, `:short-name` identifies a compact, user-oriented name
  to be displayed in the web interface or controller display (if it
  has one) to help identify the cue, which can be helpful if the name
  of the underlying effect is ambiguous or overly long.

  `:color` requests that the web interface and control surfaces draw
  the cue using the specified color rather than the default white to
  help the user identify it. This is only a request, as not all
  control surfaces support all (or any) colors. The color is passed
  to [[interpret-color]], so it can be specified in a variety of ways.

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
  This can be useful for very intense cues like strobes.

  If the function being controlled has a variable effect, and thus a
  cue variable is being introduced to adjust it, `:aftertouch`,
  `:aftertouch-min`, and `:aftertouch-max` will be used when creating
  that variable, allowing it to be controlled by aftertouch pressure
  on control surfaces which support that feature, as described
  in [[cue]]."
  {:doc/format :markdown}
  [show-key function fixtures & {:keys [level htp? effect-name short-name color end-keys priority held
                                        aftertouch aftertouch-min aftertouch-max]
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
             :end-keys end-keys
             :priority priority
             :held held
             :variables [(merge {:key "level" :min 0 :max 100 :start level :name label}
                                (when aftertouch {:aftertouch aftertouch})
                                (when aftertouch-min {:aftertouch-max aftertouch-min})
                                (when aftertouch-max {:aftertouch-max aftertouch-max}))]))
      ;; It's a fixed function, no variable required
      (cue show-key (fn [_] (chan/function-effect effect-name function (params/bind-keyword-param level Number 0)
                                                  fixtures :htp? htp?))
           :short-name short-name
           :color color
           :end-keys end-keys
           :priority priority
           :held held))))

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
                                       [id (:key (cues/cue-at (:cue-grid show) x y))]))))]
    (Effect. name
             (fn [show snapshot]  ; We are still running if any of the nested effects we launched are.
               (some (fn [[id k]]
                       (with-show show
                         (when-let [effect (show/find-effect k)]
                           (= (:id effect) id))))
                      running))
             (fn [show snapshot] nil)  ; We do not assign any values; only the nested effects do.
             (fn [show snapshot]  ; Tell all our launched effects to end.
               (doseq [[id k] running]
                 (with-show show
                   (show/end-effect! k :when-id id)))))))
