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
  [channel-assigner fixture id-fn raw-head]
  (assoc (update-in raw-head [:channels] #(map channel-assigner %))
         :fixture fixture :id (id-fn)))

(defn- patch-heads
  "Assigns the heads of a fixture to a DMX universe and starting channel; resolves all of
  their channel assignments."
  [fixture channel-assigner id-fn]
  (let [assigner (partial patch-head channel-assigner fixture id-fn)]
    (update-in fixture [:heads] #(map assigner %))))

(defn patch-fixture
  "Assign a fixture to a DMX universe and starting channel; resolves all of its channel assignments."
  [fixture universe start-address id-fn]
  (let [assigner (partial assign-channel universe start-address)]
    (update-in (patch-heads fixture assigner id-fn) [:channels] #(map assigner %))))

(defn extract-channels
  "Given a fixture list, returns the channels matching the specified predicate."
  [fixtures pred]
  (filter pred (mapcat :channels fixtures)))

(defn expand-heads
  "Given a list of fixtures, expands it to include the heads."
  [fixtures]
  (mapcat #(concat [%] (:heads %)) fixtures))

(defn all-addresses
  "Returns all the addresses being used by a list of patched fixtures,
  including those used by any fixture heads."
  [fixtures]
  (mapcat #(vals (select-keys % [:address :fine-address]))
          (mapcat :channels (afterglow.channels/expand-heads fixtures))))


(defn extract-heads-with-some-matching-channel
  "Given a fixture list, returns all heads (which may be top-level fixtures too)
  whose channels contain a match for the specified predicate."
  [fixtures pred]
  (filter #(some pred (:channels %)) (expand-heads fixtures)))

;; TODO: is this a good range data structure for finding which one a value falls into?
(defn build-function
  "Returns a function spefication that encompasses a range of possible
  DMX values for a channel. If start and end are not specified, the
  function uses the full range of the channel."
  [range-type function-type label & {:keys [start end] :or {start 0 end 255}}]
  {:start start
   :end end
   :range range-type
   :type function-type
   :label label})

(defn fine-channel
  "Defines a channel for which sometimes multi-byte values are
  desired, via a separate channel which specifies the fractional value
  to be added to the main channel."
  [chan-type offset & {:keys [fine-offset function-type function-name] :or {function-type chan-type}}]
  {:pre (some? chan-type) (integer? offset) (<= 1 offset 512)}
  (let [chan-type (keyword chan-type)
        function-type (keyword function-type)
        function-name (or function-name (clojure.string/capitalize (name function-type)))
        base (assoc (channel offset)
                    :type chan-type
                    :functions [(build-function :variable function-type function-name)])]
    (if fine-offset
      (assoc base :fine-offset fine-offset)
      base)))

(defn- expand-function
  "Expands the specification for a function range. If a simple keyword
  was given for it, creates a map with default contents for a
  variable-value range. A string is turned into a keyword, but creates
  a fixed-value range. A nil specification is expanded into a
  no-function range. Otherwise, adds any missing pieces to the
  supplied map. In either case, assigns the range's starting value."
  [[start spec]]
  (cond (keyword? spec)
        {:start start
         :range :variable
         :type spec
         :label (clojure.string/capitalize (name spec))}

        (string? spec)
        {:start start
         :range :fixed
         :type (keyword spec)
         :label (clojure.string/capitalize (name spec))}

        (nil? spec)
        {:start start
         :range :fixed
         :type :no-function
         :label "No function"}
        
        (map? spec)
        (merge {:range :variable
                :label (clojure.string/capitalize (name (:type spec)))}
               spec
               {:start start})

        :else
        (throw (IllegalArgumentException.
                (str "Don't know how to build a function specification from " spec)))))

(defn- assign-ends
  "Used to figure out the ends of the ranges that make up a function
  channel, by ending each range at the value one less than where the
  next range begins."
  [[current next]]
  (if (= :end next)
    (assoc current :end 255)
    (let [end (dec (:start next))]
      (if (< (:start current) end)
        (assoc current :end end)
        (if (= (:start current) end)
          (assoc current :end end :range :fixed)
          (throw (IllegalArgumentException. "No range values available for function " (:type current))))))))

(defn functions
  "Defines a channel whose values are divided up into different ranges
  which perform different functions. After the channel type and DMX
  offset, pass a list of starting values and function specifications.
  The simplest form of specification is a keyword identifying the
  function type; this will be expanded into a variable-range function
  of that type. For more complex functions, pass in a map containing
  the :type keyword and any other settings you need to
  make (e.g. :label), and the rest will be filled in for you. The
  ranges need to be in order of increasing starting values, and the
  ending values for each will be figured out by context, e.g.
  (functions :strobe 40
             0 nil
             10 \"strobe-on\"
             20 :strobe-variable-speed)"
  [chan-type offset & functions]
  {:pre (some? chan-type) (integer? offset) (<= 1 offset 512)}
  (let [chan-type (keyword chan-type)]
    (assoc (channel offset)
           :type chan-type
           :functions (into [] (map assign-ends (partition 2 1 [:end] (map expand-function (partition 2 functions))))))))

(defn dimmer
  "A channel which controls a dimmer, with an optional second channel
  for fine control."
  ([offset]
   (dimmer offset nil))
  ([offset fine-offset]
   (fine-channel :dimmer offset :fine-offset fine-offset :range-label "Intensity")))

(defn color
  "A channel which controls a color component, with an optional second
  channel for fine control."
  [offset color & {:keys [hue function-label fine-offset]}]
  {:pre (some? color)}
  (let [color (keyword color)]
    (-> (fine-channel :color offset :fine-offset fine-offset
                      :function-type color :function-label (or function-label (clojure.string/capitalize (name color))))
        (assoc :color (keyword color))
        (cond-> hue (assoc :hue hue)))))

(defn pan
  "A channel which pans a moving head, with an optional second channel
  for fine control."
  ([offset]
   (pan offset nil))
  ([offset fine-offset]
   (fine-channel :pan offset :fine-offset fine-offset)))

(defn tilt
  "A channel which tilts a moving head, with an optional second channel
  for fine control."
  ([offset]
   (tilt offset nil))
  ([offset fine-offset]
   (fine-channel :tilt offset :fine-offset fine-offset)))

(defn focus
  "A channel which adjusts focus, with an optional second channel for
  fine control."
  ([offset]
   (focus offset nil))
  ([offset fine-offset]
   (fine-channel :focus offset :fine-offset fine-offset)))

(defn iris
  "A channel which controls an iris, with an optional second channel
  for fine control."
  ([offset]
   (iris offset nil))
  ([offset fine-offset]
   (fine-channel :iris offset :fine-offset fine-offset)))

(defn zoom
  "A channel which adjusts zoom, with an optional second channel for
  fine control."
  ([offset]
   (zoom offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))

(defn frost
  "A channel which adjusts frost, with an optional second channel for
  fine control."
  ([offset]
   (frost offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset)))


;; TODO: control channels

;; TODO: gobo wheel and color wheel channels special variants of control channels?
