(ns afterglow.fixtures.qxf
  "Functions to work with Fixture Definition Files from
  the [QLC+](http://www.qlcplus.org/) open-source lighting controller
  project. While these do not contain all of the information Afterglow
  needs to fully control a fixture with its geometric reasoning, they
  can form a good starting point and save you a lot of tedious
  capability translation. You can find the available `.qxf` files
  on [Github](https://github.com/mcallegari/qlcplus/tree/master/resources/fixtures)."
  {:doc/format :markdown}
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :as zip-xml]
            [camel-snake-kebab.core :as csk]
            [afterglow.web.layout]  ; To set up the Selmer template path
            [selmer.parser :as parser]
            [selmer.filters :as filters]
            [selmer.filter-parser :refer [compile-filter-body]]
            [taoensso.timbre :as timbre]))

(defn sanitize-name
  "Removes non-alphanumeric characters in a string, then turns it into
  a suitable Clojure identifier."
  [s]
  (-> s
      (clojure.string/replace #"[^a-zA-Z0-9]+" " ")
      csk/->kebab-case))

(filters/add-filter! :kebab csk/->kebab-case)
(filters/add-filter! :sanitize sanitize-name)

(defn- define-color-channel
  "If the supplied channel specification map is a recognizable color
  channel, emit a function which defines it at the specified offset."
  [specs offset fine-offset]
  (when (and (= "Intensity" (:group specs)) (:color specs))
    (let [color (keyword (sanitize-name (:color specs)))]
      (str "(chan/color " offset " " color
           (when fine-offset (str " :fine-offset " fine-offset)) ")"
           (when-not (#{:red :green :blue :white} color) "  ; TODO: add :hue key if you want to color mix this")))))

(defn- define-dimmer-channel
  "If the supplied channel specification map seems to be a dimmer
  channel, emit a function which defines it at the specified offsets."
  [specs offset fine-offset]
  (when (and (= "Intensity" (:group specs)) (re-find #"(?i)dimmer" (:name specs)))
    (str "(chan/dimmer " offset (when fine-offset (str " " fine-offset)) ")")))

(defn- define-named-channel
  "If the supplied channel specification map seems to be another type
  we have a special name for, emit a function which defines it at the
  specified offsets."
  [specs offset fine-offset type-name]
  (when (re-find (re-pattern (str "(?i)" type-name)) (:name specs))
    (str "(chan/" type-name " " offset (when fine-offset (str " " fine-offset)) ")")))

(defn- define-pan-tilt-channel
  "If the supplied channel specification map seems to be a pan or tilt
  channel, emit a function which defines it at the specified offsets."
  [specs offset fine-offset]
  (when (#{"Pan" "Tilt"} (:group specs))
    (str "(chan/" (clojure.string/lower-case (:group specs)) " " offset
         (when fine-offset (str " " fine-offset)) ")")))

(defn- define-single-function-channel
  "If the supplied channel specification map contains a single
  function using the entire range, emit a function which defines it at
  the specified offsets. If it is one of the special kinds of channels
  we recognize, use the corresponding generator."
  [specs offset fine-offset]
  (let [caps (:capabilities specs)]
    (when (and (= 1 (count caps)) (zero? (:min (first caps))) (= 255 (:max (first caps))))
      (or (define-color-channel specs offset fine-offset)
          (define-dimmer-channel specs offset fine-offset)
          (define-pan-tilt-channel specs offset fine-offset)
          (some (partial define-named-channel specs offset fine-offset) ["focus" "frost" "iris" "zoom"])
          (str "(chan/fine-channel " (keyword (sanitize-name (:name specs))) " " offset
               (when fine-offset (str " :fine-offset " fine-offset))
               "\n                                 :function-name \"" (:name specs)
               "\"\n                                 :var-label \"" (:label (first caps)) "\")")))))

(defn- check-capabilities
  "Makes sure a capability list is sorted in increasing order and has
  no gaps, adding nil sections for any unused ranges, and adding
  trailing numbers to make all labels unique if needed."
  [caps]
  (loop [remaining (sort-by :min caps)
         last-end -1
         labels {}
         result []]
    (if (empty? remaining)
      (if (< last-end 255)  ; Fill in final gap
        (conj result {:min (inc last-end) :max 255})
        result)
      (let [current (first remaining)
            adjusted (if (< (inc last-end) (:min current))  ; Fill in a gap in capabilities
                       (conj result {:min (inc last-end) :max (dec (:min current))})
                       result)
            label (:label current)
            label-count (when label (inc (get labels (:label current) 0)))
            unique (if (and label (> label-count 1))
                     (assoc current :label (str label " " label-count))
                     current)
            updated-labels (if label (assoc labels label label-count) labels)]
        (recur (rest remaining) (:max current) updated-labels (conj adjusted unique))))))

(defn- expand-capability
  "Returns an Afterglow function specification corresponding to a
  QLC+ capability range."
  [cap prefix]
  (str "\n" (apply str (repeat 30 " ")) (:min cap)
       (if (some? (:label cap))
         (str " {:type " (keyword (sanitize-name (str prefix (:label cap))))
              "\n" (apply str (repeat (+ 32 (count (str (:min cap)))) " ")) ":label \"" (:label cap)
              "\"\n" (apply str (repeat (+ 32 (count (str (:min cap)))) " ")) ":range :variable}")
         " nil")))

(defn- define-channel
  "Generates a function call which defines the specified
  channel (given its specification map), at the specified offsets."
  [specs offset fine-offset]
  (or (define-single-function-channel specs offset fine-offset)
      (str "(chan/functions " (keyword (sanitize-name (:name specs))) " " offset
           (apply str (for [c (check-capabilities (:capabilities specs))]
                        (expand-capability c (str (:name specs) " ")))) ")")))

(defn- channel-tag
  "A Selmer custom tag that generates a channel definition at a
  specified offset and optional fine-offset, looking up the channel
  specification by name."
  [args context-map]
  (let [[chan-expr] args
        chan-fn (compile-filter-body chan-expr false)
        [offset channel-name fine-offset] (chan-fn context-map)
        found (filter #(= channel-name (:name %)) (:channels context-map))]
    (case (count found)
      0 (throw (IllegalStateException. (str "Could not find a channel named " channel-name)))
      1 (define-channel (first found) offset fine-offset)
      (throw (IllegalStateException. (str "Found more than one channel named " channel-name))))))

(parser/add-tag! :channel channel-tag)

(defn- qxf-creator->map
  "Builds a map containing the creator information from a QLC+ fixture
  definition."
  [creator]
  {:name (zip-xml/xml1-> creator :Name zip-xml/text)
   :version (zip-xml/xml1-> creator :Version zip-xml/text)
   :author (zip-xml/xml1-> creator :Author zip-xml/text)})

(defn- qxf-capability->map
  "Builds a map containing a channel specification from a QLC+ fixture
  definition."
  [c]
  {:min (Integer/valueOf (zip-xml/attr c :Min))
   :max (Integer/valueOf (zip-xml/attr c :Max))
   :label (zip-xml/text c)})

(defn- qxf-channel->map
  "Builds a map containing a channel specification from a QLC+ fixture
  definition."
  [ch]
  (let [color (zip-xml/xml1-> ch :Colour zip-xml/text)]
    (merge {:name (zip-xml/attr ch :Name)
            :group (zip-xml/xml1-> ch :Group zip-xml/text)
            :byte (Integer/valueOf (zip-xml/attr (zip-xml/xml1-> ch :Group) :Byte))
            :capabilities (mapv qxf-capability->map (zip-xml/xml-> ch :Capability))}
           (when color {:color color}))))

(defn- qxf-channnel-assigment->vector
  "Builds a vector containing the offset at which a channel exists in
  a mode and the channel name. Offsets are one-based, to parallel
  fixture manuals. In other words, the DMX address assigned to the
  fixture corresponds to offset 1, the next address to offset 2, and
  so on."
  [a]
  [(inc (Integer/parseInt (zip-xml/attr a :Number))) (zip-xml/text a)])

(defn- qxf-head->vector
  "Builds a vector containing the channel offsets belonging to a
  single head."
  [h]
  (mapv #(inc (Integer/parseInt %)) (zip-xml/xml-> h :Channel zip-xml/text)))

(defn- qxf-mark-pan-tilt
  "Adds flags to a fixture or head when its channel map includes pan
  or tilt channels."
  [channel-specs h]
  (let [groups (set (map :group (map #(get channel-specs (second %)) (:channels h))))]
    (merge h
           (when (groups "Pan") {:has-pan-channel true})
           (when (groups "Tilt") {:has-tilt-channel true}))))

(defn- potential-pair?
  "Check whether a pair of channel specs could potentially be a pair
  controlling two bytes of the same value. Since QLC+ doesn't make
  this explicit the way Afterglow does, be conservative."
  [spec-1 spec-2]
  (and
   (not= spec-1 spec-2)
   (not= (:byte spec-1) (:byte spec-2))
   (= (:group spec-1) (:group spec-2))
   (case (:group spec-1)
     ("Pan" "Tilt") true
     "Intensity" (or (= (:color spec-1) (:color spec-2))
                     (and (re-find #"(?i)dimmer" (:name spec-1))
                          (re-find #"(?i)dimmer" (:name spec-2))))
     false)))


(defn- paired-channel
  "Try to identify a channel which is paired as two bytes controlling
  a single value. Since QLC+ does not make this explicit the way
  Afterglow does, we can't be certain, so be a bit conservative. If
  we find a single potential match, return its name."
  [name related-specs]
  (let [specs (get related-specs name)
        candidates (filter (partial potential-pair? specs) (vals related-specs))]
    (when (= 1 (count candidates))
      (:name (first candidates)))))

(defn- merge-fine-channels
  "Try to identify any channels which are paired as two bytes
  controlling a single value. QLC+ does not make this explicit, like
  Afterglow does, so this is not going to be perfect. Takes the map of
  all channel specifications found in the QXF file, as well as the set
  which are being mapped in the current mode and perhaps head; that
  narrowing of focus can hopefully reduce ambiguity."
  [channel-specs channels]
  (let [relevant-specs (select-keys channel-specs (map second channels))
        offsets (into {} (for [[offset name] channels] [name offset]))
        merged (filter identity (for [[offset name] channels]
                                  (if-let [paired-name (paired-channel name relevant-specs)]
                                    (let [specs (get relevant-specs name)]
                                      (when (zero? (:byte specs))
                                        [offset name (get offsets paired-name)]))
                                    [offset name])))]
    (vec (sort-by first merged))))

(defn- qxf-process-heads
  "Extracts any head-specific channels from a QLC+ mode, given a
  sequence of Head nodes and the vector of mode channel assignments,
  then tries to merge any fine channels found in the resulting smaller
  channel groupings."
  ([heads channel-map channel-specs]
   ;; Kick off recursive arity with an empty response vector
   (qxf-process-heads heads channel-map channel-specs []))

  ([remaining-heads remaining-channel-map channel-specs results]
   ;; Recursive head processing
   (if (empty? remaining-heads)
     [results (merge-fine-channels channel-specs remaining-channel-map)] ; Finished, return results
     ;; Process the next head
     (loop [head-channel-numbers (sort (qxf-head->vector (first remaining-heads)))
            head-channel-result []
            channels-left remaining-channel-map]
       (if (empty? head-channel-numbers)  ; Finished processing this head
         (let [merged-channels (merge-fine-channels channel-specs head-channel-result)]
           (qxf-process-heads (rest remaining-heads) channels-left channel-specs
                              (conj results (qxf-mark-pan-tilt channel-specs {:channels merged-channels}))))
         (let [current (first head-channel-numbers)]
           (recur (rest head-channel-numbers)
                  (conj head-channel-result [current (get channels-left current)])
                  (dissoc channels-left current))))))))

(defn- qxf-mode->map
  "Builds a map containing a mode specification from a QLC+ fixture
  definition. Currently ignores the Physical documentation."
  [channel-specs m]
  (let [channel-specs (into {} (for [spec channel-specs]  ; Turn specs into a map for efficient searching
                                 [(:name spec) spec]))
        all-channels (mapv qxf-channnel-assigment->vector (zip-xml/xml-> m :Channel))
        channel-map (into {} all-channels)
        [heads other-channels] (qxf-process-heads (zip-xml/xml-> m :Head) channel-map channel-specs)]
    (qxf-mark-pan-tilt channel-specs {:name (zip-xml/attr m :Name)
                                      :channels other-channels
                                      :heads heads})))

(defn translate-definition
  "Converts a map read by [[convert-qxf]] into an Afterglow fixture
  definition."
  [qxf]
  (parser/render-file "fixture-definition.clj" qxf))

(defn parse-qxf
  "Read a fixture definition file in the format (.qxf) used by
  [QLC+](http://www.qlcplus.org/), and create a map from which it can
  conveniently be translated into an Afterglow fixture definition."
  {:doc/format :markdown}
  [source]
  (let [doc (xml/parse source)
        root (zip/xml-zip doc)]
    (when-not (= (:tag doc) :FixtureDefinition)
      (throw (Exception. "Root element is not FixtureDefinition")))
    (when-not (= (get-in doc [:attrs :xmlns]) "http://qlcplus.sourceforge.net/FixtureDefinition")
      (throw (Exception. "File does not use XML Namespace http://qlcplus.sourceforge.net/FixtureDefinition")))
    (let [channels (mapv qxf-channel->map (zip-xml/xml-> root :Channel))]
      {:creator (qxf-creator->map (zip-xml/xml1-> root :Creator))
       :manufacturer (zip-xml/text (zip-xml/xml1-> root :Manufacturer))
       :model (zip-xml/text (zip-xml/xml1-> root :Model))
       :type (zip-xml/text (zip-xml/xml1-> root :Type))
       :channels channels
       :modes (mapv (partial qxf-mode->map channels) (zip-xml/xml-> root :Mode))
       :has-pan-tilt (some? (some #(#{"Pan" "Tilt"} (:group %)) channels))
       :open-curly "{"})))

(defn convert-qxf
  "Read a fixture definition file in the format (.qxf) used by
  [QLC+](http://www.qlcplus.org/), and write a Clojure file based on
  it that can be used as the starting point of an Afterglow fixture
  definition. Returns an exit status and message for the user."
 {:doc/format :markdown}
  [path]
  (let [source (io/file path)
        qxf (parse-qxf source)
        dest (io/file (.getParent source) (str (csk/->kebab-case (:model qxf)) ".clj"))]
    (cond
      (.exists dest) [1 (str "Will not replace existing file " (.getCanonicalPath dest))]
      (not (.canWrite (.getParentFile dest))) [1 (str "Cannot write to " (.getCanonicalPath dest))]
      :else (do
              (spit dest (translate-definition qxf))
              [0 (str "Translated fixture definition written to " (.getCanonicalPath dest))]))))

;; These functions were used to help analyze the contents of all QLC+ fixture definitions.
;; They are left here in case the format changes or expands in the future and they become
;; useful again, either directly, or as examples.

(def ^:private qlc-fixtures-path
  "The directory in which all the QLC+ fixture definitions can be found. You will need to set this."
  "/Users/jim/git/qlcplus/resources/fixtures")

(defn- qlc-fixture-definitions
  "Returns a sequence of all QLC+ fixture definitions."
  []
  (let [files (file-seq (clojure.java.io/file qlc-fixtures-path))]
    (filter #(.endsWith (.getName %) ".qxf") files)))

(defn- qlc-fixture-channel-values
  "Returns the set of distinct values found under a particular keyword
  in any channel in a fixture definition."
  [k f]
  (reduce conj #{} (map k (:channels (parse-qxf f)))))

(defn- qlc-gather-groups
  "Find all the different channel group values in the QLC fixture
  definitions."
  []
  (let [files (file-seq (clojure.java.io/file qlc-fixtures-path))]
    (reduce clojure.set/union (map (partial qlc-fixture-channel-values :group) (qlc-fixture-definitions)))))

(defn- qlc-gather-bytes
  "Find all the different channel byte values in the QLC fixture
  definitions."
  []
  (let [files (file-seq (clojure.java.io/file qlc-fixtures-path))]
    (reduce clojure.set/union (map (partial qlc-fixture-channel-values :byte) (qlc-fixture-definitions)))))
