(ns afterglow.web.routes.show-control
  (:require [afterglow.controllers :as controllers]
            [afterglow.dj-link :as dj-link]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.web.layout :as layout]
            [clojure.data.json :refer [read-json write-str]]
            [com.evocomputing.colors :as colors]
            [org.httpkit.server :refer [with-channel on-receive on-close]]
            [overtone.at-at :refer [now after]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [ring.util.http-response :refer [ok]]
            [ring.util.response :refer [response]]
            [selmer.parser :as parser]
            [taoensso.timbre :as timbre :refer [warn error]]))

(defn- current-cue-color
  "Given a show, the set of keys identifying effects that are
  currently running in that show, a cue, and the currently-running
  effect launched by that cue (if any), determines the color with
  which that cue cell should be drawn in the web interface."
  [show active-keys cue cue-effect held?]
  (let [ending (and cue-effect (:ending cue-effect))
        l-boost (if (zero? (colors/saturation (:color cue))) 20.0 0.0)]
    (colors/create-color
     :h (colors/hue (:color cue))
     ;; Figure the lightness. Held cues are the lightest, followed by active, non-ending
     ;; cues. When ending, cues blink between middle and low. If they are not active,
     ;; they are at middle lightness unless there is another active effect with the same
     ;; keyword, in which case they are dim.
     :s (colors/saturation (:color cue))
     :l (+ (if cue-effect
             (if ending
               (if (> (rhythm/metro-beat-phase (:metronome show)) 0.4) 20.0 40.0)
               (if held? 80.0 65.0))
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
  [show left bottom width height holding]
  (let [active-keys (show/active-effect-keys show)]
    (for [y (range (dec (+ bottom height)) (dec bottom) -1)]
      (for [x (range left (+ left width))]
        (assoc
         (if-let [[cue active] (show/find-cue-grid-active-effect show x y)]
           (let [held? (and holding (= holding [x y (:id active)]))
                 color (current-cue-color show active-keys cue active held?)]
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

(defn update-known-midi-sync-sources
  "Scans for sources of MIDI clock pulses, and updates the cached page
  information to contain current list, assigning each a unique id
  number, so they can be selected in the sync interface."
  [page-id]
  (let [page-info (get @clients page-id)
        old-info (:midi-sync page-info)]
    (try
      (let [found (amidi/current-clock-sources)
            source-info (loop [result (update-in old-info [:known] select-keys found)
                               new-sources (clojure.set/difference found
                                                                   (set (keys (:known result))))
                               counter (inc (:counter result 0))]
                          (if-not (seq new-sources)
                            result
                            (recur (-> result
                                       (assoc-in [:known (first new-sources)] counter)
                                       (assoc-in [:counter] counter))
                                   (rest new-sources)
                                   (inc counter))))]
        (when (not= old-info source-info)
          (swap! clients assoc-in [page-id :midi-sync] source-info)))
      (catch Throwable t
        (error t "Problem updating list of MIDI clock sources")))
    controllers/pool))

(defn update-known-dj-link-sync-sources
  "Updates the cached information to contain current list of Pro DJ
  Link sync sources seen on the network, assigning each a unique id
  number, so they can be selected in the sync interface."
  [page-id]
  (let [page-info (get @clients page-id)
        old-info (:dj-link-sync page-info)
        found (dj-link/current-dj-link-sources)
        source-info (loop [result (update-in old-info [:known] select-keys found)
                           new-sources (clojure.set/difference found
                                                               (set (keys (:known result))))
                           counter (inc (:counter result 0))]
                      (if-not (seq new-sources)
                        result
                        (recur (-> result
                                   (assoc-in [:known (first new-sources)] counter)
                                   (assoc-in [:counter] counter))
                               (rest new-sources)
                               (inc counter))))]
    (when (not= old-info source-info)
      (swap! clients assoc-in [page-id :dj-link-sync] source-info))))

(defn show-page
  "Renders the web interface for interacting with the specified show."
  [show-id]
  (try
    (amidi/open-inputs-if-needed!) ; Make sure we are watching for clock messages, for the sync UI
    (catch Throwable t
      (error t "Problem opening MIDI inputs")))
  (let [[show description] (get @show/shows (Integer/valueOf show-id))
        grid (cue-view show 0 0 8 8 nil)
        page-id (:counter (swap! clients update-in [:counter] inc))]
    (swap! clients update-in [page-id] assoc :show show :id page-id
           :tap-tempo-handler (amidi/create-tempo-tap-handler (:metronome show)))
    (record-page-grid page-id grid 0 0 8 8)
    (layout/render "show.html" {:show show :title description :grid grid :page-id page-id
                                :min-bpm controllers/minimum-bpm :max-bpm controllers/maximum-bpm
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
        grid (cue-view (:show last-info) left bottom width height (:holding last-info))
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
  "Return the list of changes that should be applied to the cue scroll
  buttons since the last time they were updated."
  [page-id left bottom width height]
  (let [last-info (get @clients page-id)
        last-states (:button-states last-info)
        next-states (button-states (:show last-info) left bottom width height)
        changes (filter identity (for [[k _] next-states]
                                   (when (not= (k last-states) (k next-states))
                                     {:id (name k)
                                      :disabled (not (k next-states))})))]
    (swap! clients update-in [page-id] assoc :button-states next-states)
    (when (seq changes) {:button-changes changes})))

(defn metronome-states
  "Gather details about the current state of the main show metronome."
  [show last-states]
  (with-show show
    (let [snap (rhythm/metro-snapshot (:metronome show))
          position {:phrase (:phrase snap)
                    :bar (rhythm/snapshot-bar-within-phrase snap)
                    :beat (rhythm/snapshot-beat-within-bar snap)}]
      (merge {:bpm (format "%.1f" (float (:bpm snap)))
              :blink (or (not= position (select-keys last-states [:phrase :bar :beat]))
                         (< (rhythm/snapshot-beat-phase snap) 0.2))
              :sync (dissoc (show/sync-status) :status :source)}
             position))))

(defn metronome-changes
  "Return the list of changes that should be applied to the show
  metronome section since the last time it was updated."
  [page-id]
  (let [last-info (get @clients page-id)
        last-states (:metronome last-info)
        next-states (metronome-states (:show last-info) last-states)
        changes (filter identity (for [[k v] next-states]
                                   (when (not= (k last-states) (k next-states))
                                     {:id (name k) :val v})))]
    (swap! clients update-in [page-id] assoc :metronome next-states)
    (when (seq changes) {:metronome-changes changes})))

(defn- name-for-sync-sorting
  "Sort entries in the sync selection menu first by name, then if that
  is not unique, by player number if there is one."
  [v]
  (str (:name v) (:player v)))

(defn- build-sync-list
  "Builds a list of sync sources of a particular type for constructing
  the user interface."
  [known kind name-fn selected]
  (loop [sorted (into (sorted-map-by (fn [key1 key2] (compare [(name-for-sync-sorting key1) key1]
                                                              [(name-for-sync-sorting key2) key2])))
                      known)
           result []]
    (if-not (seq sorted)
      result
      (let [[source id] (first sorted)]
        (recur (rest sorted)
               (conj result {:label (name-fn source)
                             :value (str (name kind) "-" id)
                             :selected (= source selected)}))))))

(defn build-sync-select
  "Creates the list needed by the template which renders the HTML
  interface allowing the user to link to one of the currently
  available sources of metronome synchronization, with the current
  selection, if any, properly identified."
  [page-id]
  (let [page-info (get @clients page-id)
        known-midi (:known (:midi-sync page-info))
        known-dj (:known (:dj-link-sync page-info))
        traktor-beat-phase (amidi/current-traktor-beat-phase-sources)]
    (with-show (:show page-info)
      (concat [{:label "Manual (no automatic sync)."
                :value "manual"
                :selected (= :manual (:type (show/sync-status)))}]
              (build-sync-list known-midi :midi #(str (:name %)
                                                      (if (traktor-beat-phase %)
                                                        " (Traktor, sync BPM and beat phase)."
                                                        " (MIDI, sync BPM only)."))
                               (:source (show/sync-status)))
              (build-sync-list known-dj :dj-link #(str (:name %)
                                                       (when (.startsWith (:name %) "CDJ")
                                                         (str ", Player " (:player %)))
                                                       " (DJ Link Pro, sync precise BPM and beat grid).")
                               (:source (show/sync-status)))))))

(defn sync-menu-changes
  "Return any changes that should be applied to the menu of available
  metronome sync options since the last time it was updated."
  [page-id]
  (let [last-info (get @clients page-id)
        next-menu (parser/render-file "sync_menu.html" {:sync-menu (build-sync-select page-id)})]
    (when (> (- (now) (:last-sync-refresh last-info 0)) 2000)
      (swap! clients assoc-in [page-id :last-sync-refresh] (now))
      (update-known-dj-link-sync-sources page-id)
      (update-known-midi-sync-sources page-id))
    (when (not= next-menu (:sync-menu last-info))
      (swap! clients assoc-in [page-id :sync-menu] next-menu)
      {:sync-menu-changes next-menu})))

(defn load-update
  "If the show is running and we haven't sent a load update in the
  last half second, send one."
  [page-id]
  (let [last-info (get @clients page-id)]
    (with-show (:show last-info)
      (when (and (show/running?) (> (- (now) (:last-load-update last-info 0)) 500))
        (swap! clients assoc-in [page-id :last-load-update] (now))
        {:load-level (show/current-load)}))))

(defn status-update
  "If the running or error status of the show has changed, send an
  update about it."
  [page-id]
  (let [last-info (get @clients page-id)]
    (with-show (:show last-info)
      (let [ola-failure (show/ola-failure-description)
            status (merge {:running (show/running?)}
                          (when ola-failure {:error ola-failure}))]
        (when (not= status (:last-status last-info))
          (swap! clients assoc-in [page-id :last-status] status)
          {:show-status status})))))

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

(defn get-ui-updates
  "Route which delivers any changes which need to be applied to a show
  web interface to reflect differences in the current show state
  compared to when it was last updated."
  [id]
  (try
    (let [page-id(Integer/valueOf id)]
      (if-let [last-info (find-page-in-cache page-id)]
        ;; Found the page tracking information, send an update.
        (let [[left bottom width height] (:view last-info)]
          (response (merge {}
                           (grid-changes page-id left bottom width height)
                           (button-changes page-id left bottom width height)
                           (sync-menu-changes page-id)
                           (link-menu-changes page-id)
                           (metronome-changes page-id)
                           (load-update page-id)
                           (status-update page-id))))
        ;; Found no page tracking information, advise the page to reload itself.
        (response {:reload "Page ID not found"})))
    (catch Throwable t
           (warn t "Problem building web UI updates.")
           (response {:error (str "Unable to build updates:" (.getMessage t))}))))

(defn- handle-cue-click-event
  "Process a mouse down on a cue grid cell."
  [page-info kind req]
  (let [[left bottom] (:view page-info)
        [_ column row] (clojure.string/split kind #"-")
        [x y] (map + (map #(Integer/valueOf %) [column row]) [left bottom])
        [cue active] (show/find-cue-grid-active-effect (:show page-info) x y)
        shift (get-in req [:params :shift])]
    (if cue
      (with-show (:show page-info)
        (if active
          (do (show/end-effect! (:key cue))
              {:ended kind})
          (let [id (show/add-effect-from-cue-grid! x y)]
            (if (and (:held cue) (not shift))
              (do
                ;; Let the grid know a momentary cue is being held, so proper feedback can be shown
                (swap! clients assoc-in [(:id page-info) :holding] [x y id])
                {:holding {:x x :y y :id id}})
              {:started id}))))
      {:error (str "No cue found for cell: " kind)})))

(defn- handle-cue-release-event
  "Process a mouse up after clicking a momentary cue grid cell."
  [page-info kind]
  (let [[left bottom] (:view page-info)
        [_ x y id] (clojure.string/split kind #"-")
        [x y id] (map #(Integer/valueOf %) [x y id])
        [cue active] (show/find-cue-grid-active-effect (:show page-info) x y)]
    (swap! clients update-in [(:id page-info)] dissoc :holding)
    (if cue
      (with-show (:show page-info)
        (if (:held cue)
          (do (show/end-effect! (:key cue) :when-id id)
              {:ended id})
          {:error (str "Cue was not held for cell: " kind)}))
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

(defn- handle-sync-choice-event
  "Process a request to specify a metronome sync source."
  [page-info value]
  (with-show (:show page-info)
    (cond (or (clojure.string/blank? value) (= "manual" value))
          (do (show/sync-to-external-clock nil)
              {:sync "manual"})

          (.startsWith value "midi-")
          (let [id (Long/valueOf (second (clojure.string/split value #"-")))]
            (loop [choices (:known (:midi-sync page-info))]
              (if-not (seq choices)
                {:error (str "Unrecognized MIDI sync source id: " id)}
                (let [[k v] (first choices)]
                  (if (= v id)
                    (do (show/sync-to-external-clock (amidi/sync-to-midi-clock (:name k)))
                        {:linked value})
                    (recur (rest choices)))))))

          (.startsWith value "dj-link-")
          (let [id (Long/valueOf (get (clojure.string/split value #"-") 2))]
            (loop [choices (:known (:dj-link-sync page-info))]
              (if-not (seq choices)
                {:error (str "Unrecognized DJ Link sync source id: " id)}
                (let [[k v] (first choices)]
                  (if (= v id)
                    (do (show/sync-to-external-clock (dj-link/sync-to-dj-link k))
                        {:linked value})
                    (recur (rest choices)))))))
          :else
          {:error (str "Unrecognized sync option" value)})))

(defn metronome-delta-for-event
  "If the UI event name submitted by a show page corresponds to a
  metronome shift, return the appropriate number of milliseconds."
  [page-info kind]
  (let [metro (:metronome (:show page-info))]
    (when-let [delta (get {"beat-up" (- (rhythm/metro-tick metro))
                           "beat-down" (rhythm/metro-tick metro)
                           "bar-up" (- (rhythm/metro-tock metro))
                           "bar-down" (rhythm/metro-tock metro)}
                          kind)]
      [delta metro])))

(defn metronome-bpm-delta-for-event
  "If the UI event name submitted by a show page corresponds to a
  metronome bpm shift, and the metronome is not synced, return the
  appropriate bpm adjustment."
  [page-info kind]
  (with-show (:show page-info)
    (when (= (:type (show/sync-status)) :manual)
      (let [metro (:metronome (:show page-info))]
        (when-let [delta (get {"bpm-up" (/ 1 10)
                               "bpm-down" (- (/ 1 10))}
                              kind)]
          [delta metro])))))

(defn- interpret-tempo-tap
  "React appropriately to a tempo tap, based on the sync mode of the
  show metronome. If it is manual, invoke the metronome tap-tempo
  handler. If MIDI, align the current beat to the tap. If DJ Link, set
  the current beat to be a down beat (first beat of a bar)."
  [page-info]
  (with-show (:show page-info)
    (let [metronome (get-in page-info [:show :metronome])]
      (case (:level (show/sync-status))
        nil (do ((:tap-tempo-handler page-info))
                {:tempo "adjusting"})
        :bpm (do (rhythm/metro-beat-phase metronome 0)
                 {:started "beat"})
        :beat (do (rhythm/metro-bar-start metronome (rhythm/metro-bar metronome))
                  {:started "bar"})
        :bar (do (rhythm/metro-phrase-start metronome (rhythm/metro-bar metronome))
                 {:started "phrase"})
        (let [warning (str "Don't know how to tap tempo for sync type" (show/sync-status))]
          (warn warning)
          {:error warning})))))

(defn post-ui-event
  "Route which reports a user interaction with the show web
  interface."
  [id kind req]
  (try (when-let [page-id (Integer/valueOf id)]
         (if-let [page-info (get @clients page-id)]
           ;; Found the page tracking information, process the event.
           (response
            (cond (.startsWith kind "cue-")
                  (handle-cue-click-event page-info kind req)
                  
                  (.startsWith kind "release-")
                  (handle-cue-release-event page-info kind)
                  
                  (.startsWith kind "cues-")
                  (handle-cue-move-event page-info kind)

                  (= kind "link-select")
                  (handle-link-controller-event page-info (get-in req [:params :value]))

                  (= kind "choose-sync")
                  (handle-sync-choice-event page-info (get-in req [:params :value]))

                  (when-let [[delta metro] (metronome-delta-for-event page-info kind)]
                    (rhythm/metro-adjust metro delta))
                  {:adjusted kind}

                  (when-let [[delta metro] (metronome-bpm-delta-for-event page-info kind)]
                    (rhythm/metro-bpm metro (+ (rhythm/metro-bpm metro) delta)))
                  {:adjusted kind}

                  (= kind "bpm-slider")
                  (rhythm/metro-bpm (:metronome (:show page-info))
                                    (Float/valueOf (get-in req [:params :value])))

                  (= kind "phrase-reset")
                  (do (rhythm/metro-phrase-start (:metronome (:show page-info)) 1)
                      {:adjusted kind})

                  (= kind "tap-tempo")
                  (interpret-tempo-tap page-info)

                  (= kind "startButton")
                  (with-show (:show page-info)
                    (show/start!)
                    {:running true})

                  (= kind "stopButton")
                  (with-show (:show page-info)
                    (show/stop!)
                    (Thread/sleep (:refresh-interval (:show page-info)))
                    (show/blackout-show)
                    {:running false})

                  ;; We do no recognize this kind of request
                  :else
                  (do
                    (warn "Unrecognized UI event posted:" kind)
                    {:error (str "Unrecognized UI event kind: " kind)})))
           ;; Found no page tracking information, advise the page to reload itself.
           (response {:reload "Page ID not found"})))
       (catch Throwable t
           (warn t "Problem processing web UI event.")
           (response {:error (str "Unable to process event:" (.getMessage t))}))))
