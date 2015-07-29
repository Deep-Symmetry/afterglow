(ns afterglow.version
  "Allows the runtime environment to determine the project name and
  version which built it."
  (:require [afterglow.show-context :as context]
            [environ.core :refer [env]]))

(defn tag
  "Returns the version tag from the project.clj file, either from the
  environment variable set up by Leiningen, if running in development
  mode, or from the JAR manifest if running from a production build."
  []
  (or (env :afterglow-version)
      (.getSpecificationVersion (.getPackage (class context/set-default-show!)))
      "DEV")) ; Must be running in dev mode embedded in another project

(defn title
  "Returns the project name from the project.clj file, either from the
  environment variable set up by Leiningen, if running in development
  mode, or from the JAR manifest if running from a production build."
  []
  (or (env :afterglow-title)
      (.getSpecificationTitle (.getPackage (class context/set-default-show!)))
      "afterglow"))
