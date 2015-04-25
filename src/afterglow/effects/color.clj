(ns afterglow.effects.color
  (:require [afterglow.channels :as channels]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre :refer [error warn info debug]]))

(defn- extract-rgb
  "Filters out only the channels which define RGB color components from a list of fixtures."
  [fixtures]
  (channels/extract-channels fixtures #(#{:red :green :blue} (:color %))))

(defn- assign-color
  "Given an RGB color component channel, assigns it the appropriate DMX value for a color."
  [color channel]
  (cond (= (:color channel) :red)
        (assoc channel :value (colors/red color))
        (= (:color channel) :green)
        (assoc channel :value (colors/green color))
        (= (:color channel) :blue)
        (assoc channel :value (colors/blue color))))

(defn color-cue
  "Returns an effect function which simply assigns a fixed color to the fixtures supplied when invoked."
  [c fixtures]
  (let [assigned (map (partial assign-color c) (extract-rgb fixtures))
        result (map #(select-keys % [:address :universe :value]) assigned)]
    (fn [show] result)))
