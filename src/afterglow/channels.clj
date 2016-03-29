(ns afterglow.channels
  "Functions for modeling DMX channels"
  {:author "James Elliott"}
  (:require [afterglow.fixtures.qxf :refer [sanitize-name]]
            [camel-snake-kebab.core :as csk]
            [com.evocomputing.colors :as colors]))

(defn channel
  "Creates a minimal channel specification, containing just the
  address offset within the fixture's list of channels. The first
  channel used by a fixture is, by convention, given offset 1.

  You probably want to use [[fine-channel]] rather than this function
  to create even channels which do not have a `:fine-offset` because
  of the other helpful features it offers, such as setting up the
  channel function specification for you."
  [offset]
  {:offset offset})

(defn- assign-channel
  "Given a universe and DMX address at which a fixture is being
  patched, and a raw channel description from the fixture definition,
  calculate and assign the channel's actual DMX address and universe
  that it will have in a show."
  [universe start-address raw-channel]
  (cond-> raw-channel
    (:offset raw-channel) (assoc :address (+ (:offset raw-channel) (dec start-address))
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
  "Assigns the heads of a fixture to a DMX universe and starting
  channel; resolves all of their channel assignments."
  [fixture channel-assigner id-fn]
  (let [assigner (partial patch-head channel-assigner fixture id-fn)]
    (update-in fixture [:heads] #(map assigner %))))

(defn patch-fixture
  "Assign a fixture to a DMX universe and starting channel; resolves
  all of its channel assignments."
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
          (mapcat :channels (expand-heads fixtures))))

(defn extract-heads-with-some-matching-channel
  "Given a fixture list, returns all heads (which may be top-level fixtures too)
  whose channels contain a match for the specified predicate."
  [fixtures pred]
  (filter #(some pred (:channels %)) (expand-heads fixtures)))

(defn find-rgb-heads
  "Returns all heads of the supplied fixtures which are capable of
  mixing RGB color, in other words they have at least a red, green,
  and blue color channel. If the second argument is present and
  `true`, also returns heads with color wheels."
  ([fixtures]
   (find-rgb-heads fixtures false))
  ([fixtures include-color-wheels?]
   (filter #(or (= 3 (count (filter #{:red :green :blue} (map :color (:channels %)))))
                (and include-color-wheels? (seq (:color-wheel-hue-map %))))
           (expand-heads fixtures))))

(defn has-rgb-heads?
  "Given a fixture, returns a truthy value if it has any heads capable
  of mixing RGB color. If the second argument is present and `true`,
  having a head with a color wheel is good enough."
  ([fixture]
   (has-rgb-heads? fixture false))
  ([fixture include-color-wheels?]
   (seq (find-rgb-heads [fixture] include-color-wheels?))))

(defn build-function
  "Returns a function spefication that encompasses a range of possible
  DMX values for a channel. If start and end are not specified, the
  function uses the full range of the channel."
  [range-type function-type label & {:keys [start end var-label] :or {start 0 end 255}}]
  (merge {:start start
          :end end
          :range range-type
          :type function-type
          :label label}
         (when var-label {:var-label var-label})))

(defn fine-channel
  "Defines a channel of type `chan-type` which may be paired with a
  second channel in order to support multi-byte values. When a value
  is passed in with `:fine-offset`, the channel specified by `offset`
  is understood as containing the most-significant byte of a two-byte
  value, with the least-significant byte carried in the channel whose
  offset followed `:fine-offset`.

  Automatically creates a [function
  specification](https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#function-specifications)
  which spans all the legal DMX values for the channel. By default,
  the function type is taken to be the same as `chan-type`, but this
  can be changed by passing in a different keyword with
  `:function-type`.

  Similarly, the name of the function created is, by default, a
  capitalized version of the function type (without its leading
  colon). Since this name is displayed in the [web
  interface](https://github.com/brunchboy/afterglow/blob/master/doc/README.adoc#the-embedded-web-interface)
  as the text label in the cue grid cell for [Function
  Cues](https://github.com/brunchboy/afterglow/blob/master/doc/cues.adoc#creating-function-cues)
  created for the function, you may wish to specify a more readable
  name, which you can do by passing it with `:function-name`.

  Finally, you may specify a label to be used when creating a user
  interface for adjusting the value associated with this
  function. [Function
  Cues](https://github.com/brunchboy/afterglow/blob/master/doc/cues.adoc#creating-function-cues)
  will use this as the label for the cue-local variable they create,
  and it will appear in places like the [Ableton Push Effect Control
  interface](https://github.com/brunchboy/afterglow/blob/master/doc/push2.adoc#effect-control).
  You can specify what this variable label should be with
  `:var-label`; if you do not, the generic label `Level` will be
  used."
  [chan-type offset & {:keys [fine-offset function-type function-name var-label] :or {function-type chan-type}}]
  {:pre [(some? chan-type) (integer? offset) (<= 1 offset 512)]}
  (let [chan-type (keyword chan-type)
        function-type (keyword function-type)
        function-name (or function-name (clojure.string/capitalize (name function-type)))
        base (assoc (channel offset)
                    :type chan-type
                    :functions [(build-function :variable function-type function-name :var-label var-label)])]
    (merge base
           (when fine-offset
             {:fine-offset fine-offset}))))

(defn- expand-function-spec
  "Expands the specification for a function at a particular starting
  address. If a simple keyword was given for it, creates a map with
  default contents for a variable-value range. A string is turned into
  a keyword, but creates a fixed-value range. A nil specification is
  expanded into a no-function range. Otherwise, adds any missing
  pieces to the supplied map. In either case, assigns the range's
  starting value."
  [[start spec]]
  (cond (keyword? spec)
        {:start start
         :range :variable
         :type spec
         :label (clojure.string/replace (csk/->Camel_Snake_Case (name spec)) "_" " ")}

        (string? spec)
        {:start start
         :range :fixed
         :type (keyword (sanitize-name spec))
         :label spec}

        (nil? spec)
        {:start start
         :range :fixed
         :type :no-function
         :label "No function"}
        
        (map? spec)
        (merge {:range :variable
                :label (clojure.string/replace (csk/->Camel_Snake_Case (name (:type spec))) "_" " ")}
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
      (when-not (<= 0 end 255)
        (throw (IllegalArgumentException.
                (str "Function ends outside of legal DMX range: " end))))
      (if (< (:start current) end)
        (assoc current :end end)
        (if (= (:start current) end)
          (assoc current :end end :range :fixed)
          (throw (IllegalArgumentException.
                  (str "No range values available for function " (:type current)))))))))

(defn- expand-function-range
  "Expands a sequence of function ranges. If a sequence was passed for
  the starting point, expands it, either appending a sequential index
  to the spec (if it is a keyword or string), or pairing up successive
  values if the spec is itself a sequence. The starting value and spec
  pairs are then expanded in turn by calling expand-function-spec. If
  start is not a sequence, expand-function-range simply delegates to
  expand-function-spec."
  [[start spec]]
  (if (sequential? start)
    (if (sequential? spec)
      (map #(expand-function-spec [%1 %2]) start spec)
      (map-indexed (fn [index start]
                     (cond (string? spec)
                           (expand-function-spec [start (str spec " " (inc index))])

                           (keyword? spec)
                           (expand-function-spec [start (keyword (str (name spec) "-" (inc index)))])

                           :else
                           (throw (IllegalArgumentException.
                                   (str "Don't know how to expand function range for spec " spec)))))
                   start))
    [(expand-function-spec [start spec])]))

(defn functions
  "Defines a channel whose values are divided up into different ranges
  which perform different functions. After the channel type and DMX
  offset, pass a list of starting values and function specifications.

  The simplest form of specification is a keyword or string
  identifying the function type; this will be expanded into a
  variable-range (for keywords) or fixed-range (for strings) function
  of that type.

  For more complex functions, pass in a map containing the `:type`
  keyword and any other settings you need to make (e.g. `:label`,
  `:range`, `:var-label`), and the rest will be filled in for you.

  To skip a range, pass `nil` for its specification.

  The ranges need to be in order of increasing starting
  values, and the ending values for each will be figured out by
  context, e.g.

  ```
  (functions :strobe 40
             0 nil
             10 \"Strobe Random\"
             20 :strobe)
  ```

  See the [online
  documentation](https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#function-channels)
  for more details and examples."
  [chan-type offset & functions]
  {:pre [(some? chan-type) (integer? offset) (<= 1 offset 512)]}
  (let [chan-type (keyword chan-type)]
    (assoc (channel offset)
           :type chan-type
           :functions (vec (map assign-ends
                                (partition 2 1 [:end] (mapcat expand-function-range
                                                              (partition 2 functions))))))))

(defn color-wheel-hue
  "Creates a function specification which identifies a color wheel
  position with a particular hue, so it can participate in Afterglow's
  color effects. The hue can be specified as a number,
  a [jolby/colors](https://github.com/jolby/colors) object, or a
  string which is passed to the jolby/colors `create-color` function.

  The label to assign the function spec can be passed via the `:label`
  optional keyword argument, or it will be inferred from the hue value
  supplied. The function spec will be considered a fixed range unless
  you specify `:range :variable`.

  If hue is a sequence, then returns a sequence of the results of
  calling `color-wheel-hue` on each of the elements in that sequence,
  with the same optional arguments."
  [hue & {:keys [range label] :or {range :fixed}}]
  {:pre [(some? hue)]}
  (if (sequential? hue)
    (if (some? label)
      (map #(color-wheel-hue % :range range :label label) hue)
      (map #(color-wheel-hue % :range range) hue))
    ;; Was not a sequence, so return a single function spec
    (assoc (cond (number? hue)
                 {:color-wheel-hue (colors/clamp-hue hue)
                  :label (or label (str "Color wheel hue " (colors/clamp-hue hue)))
                  :type (keyword (str "color-wheel-hue-" (colors/clamp-hue hue)))}

                 (string? hue)
                 {:color-wheel-hue (colors/hue (colors/create-color hue))
                  :label (or label (str "Color wheel hue " hue))
                  :type (keyword (str "color-wheel-hue-" hue))}

                 (instance? com.evocomputing.colors/color hue)
                 {:color-wheel-hue (colors/hue hue)
                  :label (or label (str "Color wheel hue " hue))
                  :type (keyword (str "color-wheel-hue-" (colors/hue hue)))}

                 :else
                 (throw (IllegalArgumentException.
                         (str "Don't know how to create hue from " hue))))
           :range range)))

(defn dimmer
  "A channel which controls a dimmer.

  Normal dimmers are dark at zero, and get brighter as the channel
  value increases, to a maximum brightness at 255. However, some
  fixtures have inverted dimmers. If that is the case, pass the DMX
  value at which the inversion takes place with `:inverted-from`. For
  example, fixtures which are brightest at zero and darken as the
  value approaches 255 would be specified as `:inverted-from 0`, while
  fixtures which are dark at zero, jump to maximum brightness at 1,
  then dim as the value grows towards 255 would be specified as
  `:inverted-from 1`.

  If the fixture uses two-byte values for the dimmer level, pass
  the offset of the channel containing the most-significant byte in
  `offset`, and specify the offset of the channel containing the
  least-significant byte with `:fine-offset`."
  [offset & {:keys [inverted-from fine-offset]}]
  (merge (fine-channel :dimmer offset :fine-offset fine-offset :range-label "Intensity")
         (when inverted-from
           {:inverted-from inverted-from})))

(defn color
  "A channel which controls a color component. If `:hue` is supplied
  along with a hue value, this channel will participate in color
  mixing even if `color` is not one of the standard values `:red`,
  `:green`, `:blue`, or `:white` whose hues and contributions to color
  mixing are automatically understood.

  By default, the function created for the channel uses the name of
  the `color` keyword as its function label. Since this label is
  displayed in the [web
  interface](https://github.com/brunchboy/afterglow/blob/master/doc/README.adoc#the-embedded-web-interface)
  as the text label in the cue grid cell for [Function
  Cues](https://github.com/brunchboy/afterglow/blob/master/doc/cues.adoc#creating-function-cues)
  created for the function, you may wish to specify a more readable
  name, which you can do by passing it in with `:function-label`.

  If the fixture uses two-byte values for this color component, pass
  the offset of the channel containing the most-significant byte in
  `offset`, and specify the offset of the channel containing the
  least-significant byte with `:fine-offset`."
  [offset color & {:keys [hue function-label fine-offset]}]
  {:pre [(some? color)]}
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
   (fine-channel :pan offset :fine-offset fine-offset :var-label "Pan")))

(defn tilt
  "A channel which tilts a moving head, with an optional second channel
  for fine control."
  ([offset]
   (tilt offset nil))
  ([offset fine-offset]
   (fine-channel :tilt offset :fine-offset fine-offset :var-label "Tilt")))

(defn focus
  "A channel which adjusts focus, with an optional second channel for
  fine control."
  ([offset]
   (focus offset nil))
  ([offset fine-offset]
   (fine-channel :focus offset :fine-offset fine-offset :var-label "Focus")))

(defn iris
  "A channel which controls an iris, with an optional second channel
  for fine control."
  ([offset]
   (iris offset nil))
  ([offset fine-offset]
   (fine-channel :iris offset :fine-offset fine-offset :var-label "Iris")))

(defn zoom
  "A channel which adjusts zoom, with an optional second channel for
  fine control."
  ([offset]
   (zoom offset nil))
  ([offset fine-offset]
   (fine-channel :zoom offset :fine-offset fine-offset :var-label "Zoom")))

(defn frost
  "A channel which adjusts frost, with an optional second channel for
  fine control."
  ([offset]
   (frost offset nil))
  ([offset fine-offset]
   (fine-channel :frost offset :fine-offset fine-offset :var-label "Frost")))
