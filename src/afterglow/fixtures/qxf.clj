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

(defn qxf-channnel-assigment->vector
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
  "Adds an flag to a fixture or head when its channel map
  includes a pan or tilt channel."
  [channel-specs h]
  (merge h (when (some #{"Pan" "Tilt"} (map :group (map #(get channel-specs (second %)) (:channels h))))
             {:has-pan-tilt true})))

(defn- qxf-process-heads
  "Extracts any head-specific channels from a QLC+ mode, given a
  sequence of Head nodes and the vector of mode channel assignments."
  ([heads channel-map channel-specs]
   ;; Kick off recursive arity with an empty response vector
   (qxf-process-heads heads channel-map channel-specs []))

  ([remaining-heads remaining-channel-map channel-specs results]
   ;; Recursive head processing
   (if (empty? remaining-heads)
     [results (vec remaining-channel-map)] ; Finished, return results
     ;; Process the next head
     (loop [head-channel-numbers (sort (qxf-head->vector (first remaining-heads)))
            head-channel-result []
            channels-left remaining-channel-map]
       (if (empty? head-channel-numbers)  ; Finished processing this head
         (qxf-process-heads (rest remaining-heads) channels-left channel-specs
                            (conj results (qxf-mark-pan-tilt channel-specs {:channels head-channel-result})))
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
  (clojure.pprint/pprint qxf)
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
       :has-pan-channel (some #(= "Pan" (:group %)) channels)
       :has-tilt-channel (some #(= "Tilt" (:group %)) channels)})))

(defn convert-qxf
  "Read a fixture definition file in the format (.qxf) used by
  [QLC+](http://www.qlcplus.org/), and write a Clojure file based on
  it that can be used as the starting point of an Afterglow fixture
  definition." {:doc/format :markdown}
  [path]
  (let [source (io/file path)
        qxf (parse-qxf source)
        dest (io/file (.getParent source) (str (csk/->kebab-case (:model qxf)) ".clj"))]
    (spit dest (translate-definition qxf))
    (println "Translated fixture definition written to" (.getCanonicalPath dest))))

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
