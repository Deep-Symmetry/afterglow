(ns afterglow.fixtures
  "Utility functions common to fixture definitions."
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]))

(defn- build-function-map
  "Gathers all the functions defined on the channels a fixture or head
  into a map indexed by the function name which references the channel
  and function specs."
  [fixture-or-head]
  (into {} (apply concat (for [c (:channels fixture-or-head)]
                           (for [f (:functions c)]
                             [(:type f) [c f]])))))

(defn index-functions
  "Associates function maps with the fixture and all its heads, for
  easy lookup by function-oriented effects."
  [fixture]
  (update-in (assoc fixture :function-map (build-function-map fixture))
             [:heads]
             #(map (fn [head] (assoc head :function-map (build-function-map head))) %)))

(defn- build-color-wheel-hue-map
  "Gathers all functions which assign a color wheel hue on a fixture
  or head into a sorted map indexed by the hue which references the
  channel and function specs."
  [fixture-or-head]
  (into (sorted-map) (apply concat (for [c (:channels fixture-or-head)]
                                     (for [f (filter :color-wheel-hue (:functions c))]
                                       [(:color-wheel-hue f) [c f]])))))

(defn index-color-wheel-hues
  "Associates color wheel hue maps with the fixture and all its heads,
  for easy lookup when assigning color at the end of a color effect
  chain."
  [fixture]
  (update-in (assoc fixture :color-wheel-hue-map (build-color-wheel-hue-map fixture))
             [:heads]
             #(map (fn [head] (assoc head :color-wheel-hue-map (build-color-wheel-hue-map head))) %)))

(defn printable
  "Strips a mapped fixture (or fixture list) of keys which make it a pain to print,
  such as the back links from the heads to the entire fixture, and the function
  maps."
  [fixture-or-fixture-list]
  (if (sequential? fixture-or-fixture-list)
    (map (fn [fixture] (update-in (dissoc fixture :function-map) [:heads]
                                  #(map (fn [head] (dissoc head :fixture :function-map)) %)))
         fixture-or-fixture-list)
    (printable [fixture-or-fixture-list])))

(defn visualizer-relevant
 "Returns all the fixtures or heads which might appear in the
 visualizer. If the fixture or head has an  explicit
 :visualizer-visible boolean, that is honored, otherwise it is assumed
 that fixtures without heads are themselves relevant, while for
 fixtures with heads, only the heads are relevant."
 [fixtures-or-heads]
 (mapcat #(if-let [heads (seq (:heads %))]
         (concat (visualizer-relevant heads)
                 (when (:visualizer-visible %) [%]))
         (when (:visualizer-visible % true) [%]))
      fixtures-or-heads))

(defn generic-dimmer
  "A fixture definition where a single channel controls the power
  level of a socket to which an incandescent light source can be
  connected. If you are using a product such as the [Chauvet
  DMX-4](http://www.chauvetlighting.com/dmx-4.html), its channels can
  be patched as four of these, or four [[generic-switch]], or some
  combination."
  []
  {:channels [(chan/dimmer 1)]
   :name "Generic dimmer"})

(defn generic-switch
  "A fixture definition where a single channel turns on or off the
  power at a socket to which an incandescent light source or other
  non-DMX-enabled equipment can be connected. If you are using a
  product such as the [Chauvet
  DMX-4](http://www.chauvetlighting.com/dmx-4.html), its channels can
  be patched as four of these, or four [[generic-dimmer]], or some
  combination."
  []
  {:channels [(chan/functions :switch 1 0 "off" 1 nil 255 "on")]
   :name "Generic switch"})
