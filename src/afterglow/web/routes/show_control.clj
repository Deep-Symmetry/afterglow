(ns afterglow.web.routes.show-control
  (:require [afterglow.web.layout :as layout]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.controllers :as controllers]
            [clojure.data.json :refer [read-json write-str]]
            [com.evocomputing.colors :as colors]
            [compojure.core :refer [defroutes GET]]
            [org.httpkit.server :refer [with-channel on-receive on-close]]
            [overtone.at-at :refer [now]]
            [ring.util.response :refer [response]]
            [ring.util.http-response :refer [ok]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [selmer.parser :as parser]
            [taoensso.timbre :refer [info warn spy]]))

(defn- current-cue-color
  "Given a show, the set of keys identifying effects that are
  currently running in that show, a cue, and the currently-running
  effect launched by that cue (if any), determines the color with
  which that cue cell should be drawn in the web interface."
  [show active-keys cue cue-effect]
  (let [ending (and cue-effect (:ending cue-effect))
        l-boost (if (zero? (colors/saturation (:color cue))) 20.0 0.0)]
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
             (if (active-keys (:key cue)) 25.0 40.0))
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

(defn update-known-controllers
  "Makes sure the cached page information contains list of the
  currently-registered physical grid controllers associated with a
  page's show, assigning each a unique id number, so they can be
  selected in the user interface for scrolling or linking to. If this
  list changed, return the new list so it can be sent as an update to
  the page."
  [page-id]
  (let [page-info (get @clients page-id)
        show (:show page-info)
        old-info (:controller-info page-info)
        updated-known (loop [result (update-in old-info [:known]
                                               select-keys @(:grid-controllers show))
                             new-controllers (clojure.set/difference @(:grid-controllers show)
                                                                     (set (keys (:known result))))
                             counter (inc (:counter result 0))]
                        (if-not (seq new-controllers)
                          result
                          (recur (-> result
                                     (assoc-in [:known (first new-controllers)] counter)
                                     (assoc-in [:counter] counter))
                                 (rest new-controllers)
                                 (inc counter))))
        controller-info (if (and (:selected updated-known)
                                 (not (get (:known updated-known) (:selected updated-known))))
                          (dissoc updated-known :selected)
                          updated-known)]
    (when (not= old-info controller-info)
      (swap! clients assoc-in [page-id :controller-info] controller-info)
      controller-info)))

(defn- build-link-select
  "Creates the list needed by the template which renders the HTML
  select object allowing the user to link to one of the currently
  available controllers, with the current selection, if any, properly
  identified."
  [controller-info]
  (let [known (:known controller-info)
        selected (:selected controller-info)]
    (loop [sorted (into (sorted-map-by (fn [key1 key2] (compare [(get known key2) key2]
                                                                [(get known key1) key1])))
                        known)
           result [{:label "" :value "" :selected (nil? selected)}]]
      (if-not (seq sorted)
        result
        (let [[controller id] (first sorted)]
          (recur (rest sorted)
                 (conj result {:label (controllers/display-name controller)
                               :value id
                               :selected (= controller selected)})))))))

(defn- link-menu-changes
  "Returns the changes which need to be sent to a page to update its
  link menu since it was last displayed, and updates the record in
  process."
  [page-id]
  (when-let [new-info (update-known-controllers page-id)]
    {:link-menu-changes (parser/render-file "link_menu.html" {:link-menu (build-link-select new-info)})}))

(defn show-page
  "Renders the web interface for interacting with the specified show."
  [show-id]
  (let [[show description] (get @show/shows (Integer/valueOf show-id))
        grid (cue-view show 0 0 8 8)
        page-id (:counter (swap! clients update-in [:counter] inc))]
    (swap! clients update-in [page-id] assoc :show show :id page-id)
    (record-page-grid page-id grid 0 0 8 8)
    (layout/render "show.html" {:show show :title description :grid grid :page-id page-id
                                :link-menu (build-link-select (update-known-controllers page-id))
                                :csrf-token *anti-forgery-token*})))

(defn- contrasting-text-color
  "If the default text color of white will be hard to read against a
  cell assigned the specified color, returns black. Otherwise returns
  an empty string so the text color is used."
  [color]
  (if (and color
           (> (colors/lightness color) 60.0))
    "#000"
    ""))

(defn- grid-changes
  "Returns the changes which need to be sent to a page to update its
  cue grid display since it was last rendered, and updates the
  record."
  [page-id left bottom width height]
  (let [last-info (get @clients page-id)
        grid (cue-view (:show last-info) left bottom width height)
        changes (filter identity (flatten (for [[last-row row] (map list (:grid last-info) grid)]
                                            (for [[last-cell cell] (map list last-row row)]
                                              (when (not= last-cell cell)
                                                {:id (:id cell)
                                                 :name (:name cell)
                                                 :color (if (:current-color cell)
                                                          (colors/rgb-hexstr (:current-color cell))
                                                          "")
                                                 :textColor (contrasting-text-color (:current-color cell))})))))]
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

(defn- find-page-in-cache
  "Looks up the cached show page status for the specified ID, cleaning
  out any other entries which have not been used for a few minutes."
  [page-id]
  (doseq [[k v] @clients]
    (when (and (number? k)
               (not= k page-id)
               (> (- (now) (:when v)) 12000))
      (swap! clients dissoc k)))
  (get @clients page-id))

(defn get-ui-updates [id]
  "Route which delivers any changes which need to be applied to a show
  web interface to reflect differences in the current show state
  compared to when it was last updated."
  (let [page-id(Integer/valueOf id)]
    (if-let [last-info (find-page-in-cache page-id)]
      ;; Found the page tracking information, send an update.
      (let [[left bottom width height] (:view last-info)]
        (response (merge {}
                         (grid-changes page-id left bottom width height)
                         (button-changes page-id left bottom width height)
                         (link-menu-changes page-id))))
      ;; Found no page tracking information, advise the page to reload itself.
      (response {:reload "Page ID not found"}))))

(defn- handle-cue-click-event
  "Process an interaction with a cue grid cell."
  [page-info kind]
  (let [[left bottom] (:view page-info)
        [_ column row] (clojure.string/split kind #"-")
        [x y] (map + (map #(Integer/valueOf %) [column row]) [left bottom])
        [cue active] (show/find-cue-grid-active-effect (:show page-info) x y)]
    (if cue
      (with-show (:show page-info)
        (if active
          (show/end-effect! (:key cue))
          (show/add-effect-from-cue-grid! x y))
        {:hit kind})
      {:error (str "No cue found for cell: " kind)})))

(defn- move-view
  "Updates the origin of our view rectangle, and if it actually
  changed, also moves any linked controller."
  [page-info new-left new-bottom]
  (let [[left bottom width height] (:view page-info)]
    (when (or (not= left new-left) (not= bottom new-bottom))
      (swap! clients assoc-in [(:id page-info) :view] [new-left new-bottom width height])
      (when-let [controller (:linked-controller page-info)]
        (when (not= left new-left) (controllers/current-left controller new-left))
        (when (not= bottom new-bottom) (controllers/current-bottom controller new-bottom))))))

(defn- handle-cue-move-event
  "Process a request to scroll the cue grid."
  [page-info kind]
  (let [[left bottom width height] (:view page-info)]
    (if (case kind
          "cues-up" (when (> (- (controllers/grid-height (:cue-grid (:show page-info))) bottom) (dec height))
                      (move-view page-info left (+ bottom height)))
          "cues-down" (when (pos? bottom)
                        (move-view page-info left (- bottom (min bottom height))))
          "cues-right" (when (> (- (controllers/grid-width (:cue-grid (:show page-info))) left) (dec width))
                         (move-view page-info (+ left width) bottom))
          "cues-left" (when (pos? left)
                        (move-view page-info (- left (min left width)) bottom))
          nil) ;; We did not recognize the direction
      {:moved kind}
      {:error (str "Unable to move cue grid in direction: " kind)})))

(defn- linked-controller-update
  "Called when a linked physical controller has scrolled, or is being
  deactivated."
  [old-page-info controller action]
  (case action
    :moved
    (if-let [page-info (get @clients (:id old-page-info))]
      ;; Page is still active, so update its view origin.
      (move-view page-info (controllers/current-left controller) (controllers/current-bottom controller))
      ;; Page is no longer active, remove our listener so we can be garbage collected.
      (controllers/remove-move-listener controller (:move-handler old-page-info)))
    
    :deactivated
    (when-let [page-info (get @clients (:id old-page-info))]
      (swap! clients update-in [(:id page-info)] dissoc :move-handler :linked-controller))))

(defn unlink-controller
  "Remove any physical grid controller currently linked to scroll in
  tandem."
  [page-id]
  (when-let [page-info (get @clients page-id)]
    (when-let [controller (:linked-controller page-info)]
      (controllers/remove-move-listener controller (:move-handler page-info)))
    (swap! clients update-in [page-id] dissoc :move-handler :linked-controller)
    (swap! clients update-in [page-id :controller-info] dissoc :selected)))

;; TODO: Reload the window with the right number of rows and columns if the
;; physical controller has a different number?
(defn link-controller
  "Tie the cue grid display to that of a physical grid controller, so
  that they scroll in tandem."
  [page-id controller]
  (when-let [page-info (get @clients page-id)]
    (unlink-controller page-id)  ;; In case there was a previous link
    (let [move-handler (partial linked-controller-update page-info)]
      (swap! clients update-in [page-id] assoc :move-handler move-handler :linked-controller controller)
      (swap! clients assoc-in [page-id :controller-info :selected] controller)
      (controllers/add-move-listener controller move-handler)
      (move-view page-info (controllers/current-left controller) (controllers/current-bottom controller)))))

(defn- handle-link-controller-event
  "Process a request to specify a controller to link to."
  [page-info id]
  (if (clojure.string/blank? id)
    (do (unlink-controller (:id page-info))
        {:linked ""})
    (let [id (Long/valueOf id)]
      (loop [choices (:known (:controller-info page-info))]
        (if-not (seq choices)
          {:error (str "Unrecognized controller id: " id)}
          (let [[k v] (first choices)]
            (if (= v id)
              (do (link-controller (:id page-info) k)
                  {:linked id})
              (recur (rest choices)))))))))

(defn post-ui-event
  "Route which reports a user interaction with the show web
  interface."
  [id kind req]
  (when-let [page-id (Integer/valueOf id)]
    (if-let [page-info (get @clients page-id)]
      ;; Found the page tracking information, process the event.
      (response
       (cond (.startsWith kind "cue-")
             (handle-cue-click-event page-info kind)
             
             (.startsWith kind "cues-")
             (handle-cue-move-event page-info kind)

             (= kind "link-select")
             (handle-link-controller-event page-info (get-in req [:params :value]))

             ;; We do no recognize this kind of request
             :else
             (do
               (warn "Unrecognized UI event posted:" kind)
               {:error (str "Unrecognized UI event kind: " kind)})))
      ;; Found no page tracking information, advise the page to reload itself.
      (response {:reload "Page ID not found"}))))
