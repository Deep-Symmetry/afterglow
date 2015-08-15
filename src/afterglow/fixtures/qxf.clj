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
            [selmer.filters :as filters]))

(filters/add-filter! :kebab csk/->kebab-case)

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
    (let [modes (into {}
                      (for [m (zip-xml/xml-> root :Mode)]
                        [(zip-xml/attr m :Name) (qxf-mode->map m)]))]
      {:creator (qxf-creator->map (zip-xml/xml1-> root :Creator))
               :manufacturer (zip-xml/text (zip-xml/xml1-> root :Manufacturer))
               :model (zip-xml/text (zip-xml/xml1-> root :Model))
               :type (zip-xml/text (zip-xml/xml1-> root :Type))
               :channels (mapv qxf-channel->map (zip-xml/xml-> root :Channel))
               :modes modes
               :default-mode (first (keys modes))})))

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
