(ns
    ^{:doc "Functions for modeling DMX channels"
      :author "James Elliott"}
  afterglow.channels
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre :refer [error info debug]]))

(defn channel [offset]
  {:offset offset})

(defn- assign-channel [universe start-address raw-channel]
  (assoc raw-channel :address (+ (:offset raw-channel) (dec start-address)) :universe universe))

(defn mount-fixture [fixture universe start-address]
  (let [assigner (partial assign-channel universe start-address)]
    (update-in fixture [:fixture :channels] #(map assigner %))))

;; TODO figure out a good range data structure for finding which one a value falls into
(defn dimmer [offset]
  (assoc (channel offset)
         :type :dimmer
         :offset offset
         :ranges { 255 {:type :variable
                        :label "Intensity"}}))

;; TODO here is where we would add wavelength support once we can figure out the formulas
(defn color
  ([offset kwd]
   (color offset kwd (string/capitalize (name kwd))))
  ([offset kwd name]
   (assoc (dimmer offset)
          :type :color
          :offset offset
          :color kwd
          :ranges { 255 {:type :variable
                         :label name}})))

;; TODO pan-tilt channels, with multi-byte support

;; TODO control channels

;; TODO gobo wheel and color wheel channels special variants of control channels?
