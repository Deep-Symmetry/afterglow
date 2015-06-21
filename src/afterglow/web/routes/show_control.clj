(ns afterglow.web.routes.show-control
  (:require [afterglow.web.layout :as layout]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.controllers :as controllers]
            [clojure.data.json :refer [read-json write-str]]
            [com.evocomputing.colors :as colors]
            [compojure.core :refer [defroutes GET]]
            [org.httpkit.server :refer [with-channel on-receive on-close]]
            [overtone.at-at :refer [now]]
            [ring.util.response :refer [response]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :refer [info warn]]))

(defn- current-cue-color
  "Given a show, the set of keys identifying effects that are
  currently running in that show, a cue, and the currently-running
  effect launched by that cue (if any), determines the color with
  which that cue cell should be drawn in the web interface."
  [show active-keys cue cue-effect]
  (let [ending (and cue-effect (:ending cue-effect))
        l-boost (if (zero? (colors/saturation (:color cue))) 10.0 0.0)]
    (colors/create-color
     :h (colors/hue (:color cue))
     ;; Figure the brightness. Active, non-ending cues are full brightness;
     ;; when ending, they blink between middle and low. If they are not active,
     ;; they are at middle brightness unless there is another active effect with
     ;; the same keyword, in which case they are dim.
     :s (colors/saturation (:color cue))
     :l (+ (if cue-effect
             (if ending
               (if (> (rhythm/metro-beat-phase (:metronome show)) 0.4) 20.0 40.0)
               65.0)
             (if (active-keys (:key cue)) 20.0 40.0))
           l-boost))))

(defn cue-view
  "Returns a nested structure of rows of cue information starting at
  the specified origin, with the specified width and height. Ideal for
  looping over and rendering in textual form, such as in a HTML table.
  Each cell contains a tuple [cue effect], the cue assigned to that
  grid location, and the currently-running effect, if any, launched
  from that cue. Cells which do not have associated cues still be
  assigned a unique cue ID (identifying page-relative coordinates,
  with zero at the lower left) so they can be updated if a cue is
  created for that slot while the page is still up."
  [show left bottom width height]
  (let [active-keys (show/active-effect-keys show)]
    (for [y (range (dec (+ bottom height)) (dec bottom) -1)]
      (for [x (range left (+ left width))]
        (assoc
         (if-let [[cue active] (show/find-cue-grid-active-effect show x y)]
           (let [color (current-cue-color show active-keys cue active)]
             (assoc cue :current-color color
                    :style-color (str "style=\"background-color: " (colors/rgb-hexstr color) "\"")))
           ;; No actual cue found, start with an empty map
           {})
         ;; Add the ID whether or not there is a cue
         :id (str "cue-" (- x left) "-" (- y bottom)))))))

(defonce ^{:doc "Tracks the active show interface pages, and any
  pending interface updates for each."}
  clients
  (atom {:counter 0}))

(defn- record-page-grid
  "Stores the view of the cue grid last rendered by a particular web
  interface so that differences can be sent the next time the page
  asks for an update."
  [page-id grid left bottom width height]
  (swap! clients update-in [page-id] assoc :view [left bottom width height] :grid grid :when (now)))

(defn show-page [id]
  "Renders the web interface for interacting with the specified show."
  (let [[show description] (get @show/shows (Integer/valueOf id))
        grid (cue-view show 0 0 8 8)
        page-id (:counter (swap! clients update-in [:counter] inc))]
    (swap! clients update-in [page-id] assoc :show show)
    (record-page-grid page-id grid 0 0 8 8)
    (layout/render "show.html" {:show show :title description :grid grid :page-id page-id})))

(defn- grid-changes
  "Returns the changes which need to be sent to a page to update its
  cue grid display since it was last rendered, and updates the
  record."
  [page-id grid left bottom width height]
  (let [last-info (get @clients page-id)
        changes (filter identity (flatten (for [[last-row row] (map list (:grid last-info) grid)]
                                            (for [[last-cell cell] (map list last-row row)]
                                              (when (not= last-cell cell)
                                                {:id (:id cell)
                                                 :name (:name cell)
                                                 :color (colors/rgb-hexstr (:current-color cell))})))))]
    (record-page-grid page-id grid left bottom width height)
    (when (seq changes) {:grid-changes changes})))

(defn- button-states
  "Determine which of the scroll buttons should be enabled for the cue
  grid."
  [show left bottom width height]
  (let [bounds @(:dimensions (:cue-grid show))]
    {:cues-up (>= (second bounds) (+ bottom height))
     :cues-right (>= (first bounds) (+ left width))
     :cues-down (pos? bottom)
     :cues-left (pos? left)}))

(defn- button-changes
  [page-id left bottom width height]
  (let [last-info (get @clients page-id)
        last-states (:button-states last-info)
        next-states (button-states (:show last-info) left bottom width height)
        changes (filter identity (for [[key _] next-states]
                                   (when (not= (key last-states) (key next-states))
                                     {:id (name key)
                                      :disabled (not (key next-states))})))]
    (swap! clients update-in [page-id] assoc :button-states next-states)
    (when (seq changes) {:button-changes changes})))

(defn ui-updates [id req]
  (let [page-id(Integer/valueOf id)]
    (if-let [last-info (get @clients page-id)]
      ;; Found the page tracking information, send an update
      (let [[left bottom width height] (:view last-info)
            grid (cue-view (:show last-info) left bottom width height)]
        (response (merge {}
                         (grid-changes page-id grid left bottom width height)
                         (button-changes page-id left bottom width height))))
      (response {:reload "Page ID not found"}))))


