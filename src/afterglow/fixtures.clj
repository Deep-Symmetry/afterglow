(ns afterglow.fixtures
  "Utility functions common to fixture definitions."
  {:author "James Elliott"}
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :as zip-xml]
            [afterglow.channels :as chan]))

(defn- build-function-map
  "Gathers all the functions defined on the channels a fixture or head
  into a map indexed by the function name which references the channel
  and function specs."
  [fixture-or-head]
  (into {} (apply concat (for [c (:channels fixture-or-head)]
                           (for [f (:functions c)]
                             [(:type f) [c f]])))))

(defn index-functions
  "Associates function maps with the fixture and all its heads, for
  easy lookup by function-oriented effects."
  [fixture]
  (update-in (assoc fixture :function-map (build-function-map fixture))
             [:heads]
             #(map (fn [head] (assoc head :function-map (build-function-map head))) %)))

(defn- build-color-wheel-hue-map
  "Gathers all functions which assign a color wheel hue on a fixture
  or head into a sorted map indexed by the hue which references the
  channel and function specs."
  [fixture-or-head]
  (into (sorted-map) (apply concat (for [c (:channels fixture-or-head)]
                                     (for [f (filter :color-wheel-hue (:functions c))]
                                       [(:color-wheel-hue f) [c f]])))))

(defn index-color-wheel-hues
  "Associates color wheel hue maps with the fixture and all its heads,
  for easy lookup when assigning color at the end of a color effect
  chain."
  [fixture]
  (update-in (assoc fixture :color-wheel-hue-map (build-color-wheel-hue-map fixture))
             [:heads]
             #(map (fn [head] (assoc head :color-wheel-hue-map (build-color-wheel-hue-map head))) %)))

(defn printable
  "Strips a mapped fixture (or fixture list) of keys which make it a pain to print,
  such as the back links from the heads to the entire fixture, and the function
  maps."
  [fixture-or-fixture-list]
  (if (sequential? fixture-or-fixture-list)
    (map (fn [fixture] (update-in (dissoc fixture :function-map) [:heads]
                                  #(map (fn [head] (dissoc head :fixture :function-map)) %)))
         fixture-or-fixture-list)
    (printable [fixture-or-fixture-list])))

(defn visualizer-relevant
 "Returns all the fixtures or heads which might appear in the
 visualizer. If the fixture or head has an  explicit
 :visualizer-visible boolean, that is honored, otherwise it is assumed
 that fixtures without heads are themselves relevant, while for
 fixtures with heads, only the heads are relevant."
 [fixtures-or-heads]
 (mapcat #(if-let [heads (seq (:heads %))]
         (concat (visualizer-relevant heads)
                 (when (:visualizer-visible %) [%]))
         (when (:visualizer-visible % true) [%]))
      fixtures-or-heads))

(defn generic-dimmmer
  "A fixture definition where a single channel controls the power
  level of a socket to which an incandescent light source can be
  connected. If you are using a product such as the [Chauvet
  DMX-4](http://www.chauvetlighting.com/dmx-4.html), its channels can
  be patched as four of these, or four [[generic-switch]], or some
  combination."
  {:doc/format :markdown}
  []
  {:channels [(chan/dimmer 1)]
   :name "Generic dimmer"})

(defn generic-switch
  "A fixture definition where a single channel turns on or off the
  power at a socket to which an incandescent light source or other
  non-DMX-enabled equipment can be connected. If you are using a
  product such as the [Chauvet
  DMX-4](http://www.chauvetlighting.com/dmx-4.html), its channels can
  be patched as four of these, or four [[generic-dimmer]], or some
  combination."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :switch 1 0 "off" 1 nil 255 "on")]
   :name "Generic switch"})

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
  {:name (zip-xml/attr ch :Name)
   :group (zip-xml/xml1-> ch :Group zip-xml/text)
   :byte (Integer/valueOf (zip-xml/attr (zip-xml/xml1-> ch :Group) :Byte))
   :capabilities (mapv qxf-capability->map
                       (zip-xml/xml-> ch :Capability))})

(defn- qxf-mode->map
  "Builds a map containing a mode specification from a QLC+ fixture
  definition. Currently ignores the Physical documentation."
  [m]
  {:name (zip-xml/attr m :Name)
   :channels (mapv zip-xml/text (zip-xml/xml-> m :Channel))})

(defn convert-qxf
  "Read a fixture definition file in the format (.qxf) used by
  [QLC+](http://www.qlcplus.org/), and use it as the starting point of
  an Afterglow fixture definition."
  {:doc/format :markdown}
  [path]
  (let [doc (-> path io/file xml/parse)
        root (zip/xml-zip doc)]
    (when-not (= (:tag doc) :FixtureDefinition)
      (throw (Exception. "Root element is not FixtureDefinition")))
    (when-not (= (get-in doc [:attrs :xmlns]) "http://qlcplus.sourceforge.net/FixtureDefinition")
      (throw (Exception. "File does not use XML Namespace http://qlcplus.sourceforge.net/FixtureDefinition")))
    {:creator (qxf-creator->map (zip-xml/xml1-> root :Creator))
     :manufacturer (zip-xml/text (zip-xml/xml1-> root :Manufacturer))
     :model (zip-xml/text (zip-xml/xml1-> root :Model))
     :type (zip-xml/text (zip-xml/xml1-> root :Type))
     :channels (into {}
                     (for [ch (zip-xml/xml-> root :Channel)]
                       [(zip-xml/attr ch :Name) (qxf-channel->map ch)]))
     :modes (into {}
                  (for [m (zip-xml/xml-> root :Mode)]
                    [(zip-xml/attr m :Name) (qxf-mode->map m)]))}))

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
  (reduce conj #{} (map k (vals (:channels (convert-qxf f))))))

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
