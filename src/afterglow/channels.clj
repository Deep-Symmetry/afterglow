(ns afterglow.channels
  "Functions for modeling DMX channels"
  {:author "James Elliott"})

(defn channel [offset]
  {:offset offset})

(defn- assign-channel [universe start-address raw-channel]
  (cond-> raw-channel
    true (assoc :address (+ (:offset raw-channel) (dec start-address))
                :index (+ (dec (:offset raw-channel)) (dec start-address))
                :universe universe)
    (:fine-offset raw-channel) (assoc :fine-address (+ (:fine-offset raw-channel) (dec start-address))
                                      :fine-index (+ (dec (:fine-offset raw-channel)) (dec start-address)))))

(defn- patch-head
  "Assigns a single head to a DMX universe and starting channel; resolves all of its
  channel assignments."
  [channel-assigner raw-head]
  (update-in raw-head [:channels] #(map channel-assigner %)))

(defn- patch-heads
  "Assigns the heads of a fixture to a DMX universe and starting channel; resolves all of
  their channel assignments."
  [fixture channel-assigner]
  (let [assigner (partial patch-head channel-assigner)]
    (update-in fixture [:heads] #(map assigner %))))

(defn patch-fixture
  "Assign a fixture to a DMX universe and starting channel; resolves all of its channel assignments."
  [fixture universe start-address]
  (let [assigner (partial assign-channel universe start-address)]
    (update-in (patch-heads fixture assigner) [:channels] #(map assigner %))))

(defn extract-channels
  "Given a fixture list, returns the channels matching the specified predicate."
  [fixtures pred]
  (filter pred (mapcat :channels fixtures)))

(defn expand-heads
  "Given a list of fixtures, expands it to include the heads."
  [fixtures]
  (mapcat #(concat [%] (:heads %)) fixtures))

(defn extract-heads-with-some-matching-channel
  "Given a fixture list, returns all heads (which may be top-level fixtures too)
  whose channels contain a match for the specified predicate."
  [fixtures pred]
  (filter #(some pred (:channels %)) (expand-heads fixtures)))

(defn full-range
  "Returns a range spefication that encompasses all possible DMX values as a single variable setting."
  [range-type label]
  {:start 0
   :end 255
   :type range-type
   :label label})

;; TODO is this a good range data structure for finding which one a value falls into?
(defn fine-channel
  "Defines a channel for which sometimes multi-byte values are desired, via a separate
channel which specifies the fractional value to be added to the main channel."
  [chan-type offset & {:keys [fine-offset range-label]}]
  (let [base (assoc (channel offset)
                    :type chan-type
                    :ranges [(full-range :variable (or range-label (clojure.string/capitalize (name chan-type))))])]
    (if fine-offset
      (assoc base :fine-offset fine-offset)
      base)))


(defn dimmer
  ([offset]
   (dimmer offset nil))
  ([offset fine-offset]
   (fine-channel :dimmer offset :fine-offset fine-offset :range-label "Intensity")))

(defn color
  [offset kwd & {:keys [hue label]}]
  (-> (fine-channel :color offset :range-label (or label (clojure.string/capitalize (name kwd))))
      (assoc :color (keyword kwd))
      (cond-> hue (assoc :hue hue))))

(defn pan
  ([offset]
   (pan offset nil))
  ([offset fine-offset]
   (fine-channel :pan offset :fine-offset fine-offset)))

(defn tilt
  ([offset]
   (tilt offset nil))
  ([offset fine-offset]
   (fine-channel :tilt offset :fine-offset fine-offset)))

(defn focus
  ([offset]
   (focus offset nil))
  ([offset fine-offset]
   (fine-channel :focus offset :fine-offset fine-offset)))

(defn iris
  ([offset]
   (iris offset nil))
  ([offset fine-offset]
   (fine-channel :iris offset :fine-offset fine-offset)))

(defn zoom
  ([offset]
   (zoom offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))

(defn frost
  ([offset]
   (frost offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))


;; TODO control channels

;; TODO gobo wheel and color wheel channels special variants of control channels?
