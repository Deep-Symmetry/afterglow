(ns afterglow.controllers.launchpad-mini
  "Allows the Novation Launchpad Mini to be used as a control surface
  for Afterglow."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.controllers.tempo :as tempo]
            [afterglow.effects.cues :as cues]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre :refer [warn]]
            [taoensso.truss :as truss :refer [have have! have?]]))

(defonce ^{:doc "Counts the controller bindings which have been made,
  so each can be assigned a unique ID."}
  controller-counter (atom 0))

(defonce ^{:doc "Controllers which are currently bound to shows,
  indexed by the controller binding ID."}
  active-bindings (atom {}))

(def control-buttons
  "The round buttons which send and respond to Control Change events.
  These assignments don't correspond with any standard decal beyond
  the first four (arrows), but reflect Afterglow's needs. Maybe
  someone can make an Afterglow sticker for us."
  {:up-arrow    104
   :down-arrow  105
   :left-arrow  106
   :right-arrow 107
   :tap-tempo   108
   :shift       109
   :device-mode 110
   :stop        111})

(def button-off-color
  "The color of buttons that are completely off."
  0x0c)

(def button-available-color
  "The color of buttons that can be pressed but haven't yet been."
  0x1d)

(def button-active-color
  "The color of an available button that is currently being pressed."
  0x3f)

(def stop-available-color
  "The color of the Stop button when not active."
  0x0d)

(def stop-active-color
  "The color of the stop button when active."
  0x0f)

(def click-unsynced-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is taking place."
  0x3e)

(def click-unsynced-off-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is not taking place."
  0x1d)

(def click-synced-beat-color
  "The color of the tap tempo button when the metronome is
  synchronzied and a beat is taking place."
  0x3c)

(def click-synced-off-beat-color
  "The color of the tap tempo button when the metronome is
  synchronized and a beat is not taking place."
  0x1c)


