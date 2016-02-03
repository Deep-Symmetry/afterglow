(ns afterglow.effects.dimmer
  "Effects pipeline functions for working with dimmer channels for
  fixtures and heads. Dimmer effects are always tied to a _master_
  chain, which can scale back the maximum allowable value for that
  dimmer channel, as a percentage. Unless otherwise specified, the
  dimmer cue will be attached to the show grand master, but you can
  create other masters to adjust the brightness of groups of fixtures,
  perhaps because they are intrinsically brighter, or to adjust the
  balance of lighting for artistic reasons. Secondary masters can be
  chained to each other, and are always chained to the show grand
  master, so turning that down will dim the entire show; setting it to
  zero will black out the show.

  This master scaling capability is so useful that you will almost
  always want a prominent fader on a MIDI controller tied to the show
  grand master, and possibly others to secondary masters.
  [[show/add-midi-control-to-master-mapping]] makes that easy,
  especially for the grand master, and for submasters stored in show
  variables, which can be referred to by their keywords.

  Some fixtures have damping functions that slow down their dimmer
  response, so you may not get the kind of coordination you would like
  from oscillated dimmer cues. A potential workaround is to use the
  dimmer channels as a maximum brightness level to allow tweaking the
  overall brightness of an effect, and using the lightness attribute
  of a color cue to create time-varying brightness effects."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.params :as params]
            [afterglow.effects :as fx :refer [always-active end-immediately]]
            [afterglow.rhythm :refer [metro-snapshot]]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :refer [valid-dmx-value?]]
            [com.evocomputing.colors :as colors :refer [clamp-percent-float
                                                        clamp-rgb-int]]
            [taoensso.timbre.profiling :refer [pspy]]
            [taoensso.timbre :as timbre])
  (:import (afterglow.effects Effect)))

(defn- assign-level
  "Assigns a dimmer level to the channel."
  [level channel]
  (assoc channel :value level))

(defn dimmer-channel?
  "Returns true if the supplied channel is a dimmer."
  [c]
  (= (:type c) :dimmer))

(defn gather-dimmer-channels
  "Finds all channels in the supplied fixture list which are dimmers,
  even if they are inside heads."
  [fixtures]
  (let [heads (channels/extract-heads-with-some-matching-channel fixtures dimmer-channel?)]
    (channels/extract-channels heads dimmer-channel?)))

(defn gather-partial-dimmer-function-heads
  "Find all heads in the supplied fixture list which contain
  multipurpose channels that are partially used for dimming,
  rather than full dedicated dimmer channels."
  [fixtures]
  (filter #(when-let [dimmer (:dimmer (:function-map %))]
             (not= :dimmer (:type (first dimmer))))
          (channels/expand-heads fixtures)))

(defn gather-no-dimmer-rgb-heads
  "Finds all the RGB heads from the supplied fixture list which have
  no dimmer capability at either the head or fixture level. These
  heads are suitable for creating virtual dimmer effects when
  desired."
  [fixtures]
  (let [no-dimmers (filter #(not (:dimmer (set (mapcat keys (map :function-map (channels/expand-heads [%]))))))
                           fixtures)]
    (channels/find-rgb-heads no-dimmers)))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
(defprotocol IDimmerMaster
  "A chainable limiter of dimmer cues."
  (master-set-level [master new-level]
  "Set the level of this master, as a percentage from 0 to 100. Any
  value less than 100 will cause the dimmer cues attached to this
  master to have their levels scaled back by that amount. If there are
  any parent masters attached to this one, they may further scale back
  the value in turn.")
  (master-get-level [master]
  "Get the level of this master, as a percentage from 0 to 100. Any
  value less than 100 will cause the dimmer cues attached to this
  master to have their levels scaled back by that amount. If there are
  any parent masters attached to this one, they may further scale back
  the value in turn.")
  (master-scale [master value]
  "Scale down the value being sent to a dimmer according to this
  master level, and any parent masters associated with it."))))

(defrecord Master [level parent]
  IDimmerMaster
  (master-set-level [master new-level]
    (reset! level (clamp-percent-float new-level)))
  (master-get-level [master]
    @level)
  (master-scale [master value]
    (let [scaled (* value (/ @level 100))]
      (if (some? parent)
        (master-scale parent scaled)
        scaled))))

(defn master
  "Create a master for scaling dimmer cues. If you set its level to
  less than 100 (interpreted as a percentage), all dimmer cues created
  with this master will be scaled back appropriately. If you supply a
  parent master, it will get a chance to scale them back as well. If
  you don't, the show's grand master is used as the parent master."
  [show & {:keys [level parent] :or {level 100 parent (:grand-master show)}}]
  (let [initial-level (atom (clamp-percent-float level))]
    (Master. initial-level parent)))

(defn- build-parameterized-dimmer-effect
  "Returns an effect which assigns a dynamic value to all the supplied
  dimmers (which are broken into three lists: full dedicated dimmer
  channels, heads that have a dimmer function on a multipurpose
  channel, and RGB heads with no dimmer at all that should be assigned
  a virtual dimmer effect).

  If `htp?` is true, applies highest-takes-precedence (i.e. compares
  the value to the previous assignment for the channel, and lets the
  highest value remain).

  All dimmer cues are associated with a master chain which can scale
  down the values to which they are set. If none is supplied when
  creating the dimmer cue, the show's grand master is used."
  [name level show full-channels function-heads virtual-heads htp? master]
  (params/validate-param-type master Master)
  (let [full-f (if htp?  ; Assignment function for dedicated dimmer channels
                 (fn [show snapshot target previous-assignment]
                   (clamp-rgb-int (max (master-scale master (params/resolve-param level show snapshot))
                                       (or (params/resolve-param previous-assignment show snapshot) 0))))
                 (fn [show snapshot target previous-assignment]
                   (clamp-rgb-int (master-scale master (params/resolve-param level show snapshot)))))
        full-assigners (chan-fx/build-raw-channel-assigners full-channels full-f)
        func-f (if htp?  ; Assignment function for dimmer functions on multipurpose channels
                 ;; We must scale dimmer level from 0-255 to 0-100, since function effects use percentages.
                 (fn [show snapshot target previous-assignment]
                   (max (/ (master-scale master (params/resolve-param level show snapshot)) 2.55)
                        (or (params/resolve-param previous-assignment show snapshot) 0)))
                 (fn [show snapshot target previous-assignment]
                   (/ (master-scale master (params/resolve-param level show snapshot)) 2.55)))
        func-assigners (chan-fx/build-head-function-assigners :dimmer function-heads func-f)
        virtual-f (fn [show snapshot target previous-assignment]
                    (when previous-assignment
                      (let [resolved (params/resolve-param previous-assignment show snapshot target)
                            fraction (/ (master-scale master (params/resolve-param level show snapshot)) 255)]
                        (colors/create-color :h (colors/hue resolved)
                                             :s (colors/saturation resolved)
                                             :l (clamp-percent-float (* (colors/lightness resolved) fraction))
                                             :a (colors/alpha resolved)))))
        virtual-assigners (fx/build-head-assigners :color virtual-heads virtual-f)]
    (Effect. name always-active
             (fn [show snapshot] (concat full-assigners func-assigners virtual-assigners))
             end-immediately)))

(defn dimmer-effect
  "Returns an effect which assigns a dynamic value to all the supplied
  dimmers. If a `true` value is passed for `:htp?`, applies
  _highest-takes-precedence_ (i.e. compares the value to the previous
  assignment for the channel, and lets the highest value remain).

  All dimmer cues are associated with a [[master]] chain which can
  scale down the values to which they are set. If none is supplied
  when creating the dimmer cue, the show's grand master is used.

  Dimmers are either a channel fully dedicated to dimming, identified
  by the channel `:type` of `:dimmer`, or a dimmer function range
  defined over only part of a multi-purpose channel, where the
  function `:type` is `:dimmer`, and the channel `:type` must be
  something else. A single head cannot have both types of dimmer,
  since a function can only exist on one channel in a given head.

  If you have any fixtures that are capable of RGB color mixing, but
  lack dedicated dimmer channels, you can have this effect simulate a
  dimmer channel for those fixtures by passing a `true` value with
  `add-virtual-dimmers?`. The virtual dimmer works by modifying any
  color effect that has already run for those fixtures, to reduce the
  brightness of the color being assigned. To do that, this dimmer
  effect needs to run at a higher effect priority than any color
  effect you want it to modify, so be sure to add it to your show with
  an appropriate priority value. Virtual dimmers are incompatible with
  hightest-takes-precedence dimming, because there is no actual
  previous dimmer value for them to work with, so you cannot use both
  `htp?` and `add-virtual-dimmers` at the same time."
  [level fixtures & {:keys [htp? master effect-name add-virtual-dimmers?]
                     :or {htp? true master (:grand-master *show*)}}]
  {:pre [(some? *show*) (not (and htp? add-virtual-dimmers?))]}
  (let [level (params/bind-keyword-param level Number 255)
        master (params/bind-keyword-param master Master (:grand-master *show*))]
    (let [full-channels (gather-dimmer-channels fixtures)
          function-heads (gather-partial-dimmer-function-heads fixtures)
          virtual-heads (when add-virtual-dimmers? (gather-no-dimmer-rgb-heads fixtures))
          snapshot (metro-snapshot (:metronome *show*))
          level (params/resolve-unless-frame-dynamic level *show* snapshot)
          master (params/resolve-param master *show* snapshot)  ; Can resolve now; value is inherently static.
          label (if (params/param? level) "<dynamic>" level)]
      (build-parameterized-dimmer-effect (or effect-name (str "Dimmers=" label (when htp? " (HTP)")))
                                         level *show* full-channels function-heads virtual-heads htp? master))))

